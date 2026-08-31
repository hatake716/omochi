package io.github.hatake716.omochi

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process as AndroidProcess
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Owns the loopback-only code-server process for [OmochiServerService]. */
object OmochiServerManager {
    sealed interface State {
        data object Stopped : State
        data class Starting(val message: String) : State
        data class Running(val url: String) : State
        data class Failed(val message: String) : State
    }

    private const val TAG = "OmochiServer"
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val generation = AtomicLong(0)

    @Volatile
    private var currentState: State = State.Stopped

    @Volatile
    private var process: Process? = null

    @Volatile
    private var activePort: Int = 0

    fun state(): State = currentState

    fun addListener(listener: (State) -> Unit) {
        listeners += listener
        main.post { listener(currentState) }
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners -= listener
    }

    @Synchronized
    fun start(context: Context) {
        val existing = process
        if (existing?.isAlive == true) {
            val port = activePort
            if (port !in 1024..65535) {
                publish(State.Failed("起動中サーバーのloopbackポートが不明です。"))
                return
            }
            if (currentState is State.Running) {
                publish(
                    State.Running(
                        OmochiRuntime.serverUrl(port, WorkspaceSession.selectedGuestFolder(context))
                    )
                )
            } else {
                pollUntilReady(context.applicationContext, generation.get(), port)
            }
            return
        }

        val appContext = context.applicationContext
        val port = selectLoopbackPort().getOrElse {
            publish(State.Failed(it.message ?: "空きloopbackポートを確保できません。"))
            return
        }
        activePort = port
        val launch = OmochiRuntime.buildServerLaunchSpec(appContext, port).getOrElse {
            activePort = 0
            publish(State.Failed(it.message ?: "IDEサーバーの起動構成を作成できません。"))
            return
        }

        publish(State.Starting("端末内のIDEサーバーを起動しています…"))
        val runId = generation.incrementAndGet()
        try {
            val command = launch.args.toList()
            val builder = ProcessBuilder(command)
                .directory(File(launch.cwd))
                .redirectErrorStream(true)
            builder.environment().apply {
                clear()
                launch.env.forEach { item ->
                    val separator = item.indexOf('=')
                    if (separator > 0) {
                        put(item.substring(0, separator), item.substring(separator + 1))
                    }
                }
            }
            process = builder.start()
            consumeOutput(appContext, process!!, runId)
            pollUntilReady(appContext, runId, port)
        } catch (t: Throwable) {
            process = null
            activePort = 0
            publish(State.Failed(t.message ?: "IDEサーバーを起動できませんでした。"))
        }
    }

    @Synchronized
    fun stop() {
        generation.incrementAndGet()
        val port = activePort
        process?.let { running -> terminateProcessTree(running, port) }
        process = null
        activePort = 0
        publish(State.Stopped)
    }

    /**
     * PRoot and code-server form a host-side process tree. Android's regular
     * [Process.destroy] can leave the traced guest and Node children alive, so an
     * explicit user stop must terminate every app-owned descendant before a new
     * session is allowed to start.
     */
    private fun terminateProcessTree(running: Process, port: Int) {
        val serverPids = findManagedServerPids(port)
        val knownPids = serverPids.flatMap(::ownedProcessTree).distinct()

        runCatching { running.destroy() }
        if (knownPids.isEmpty()) {
            val exited = runCatching {
                running.waitFor(500L, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
            if (!exited) {
                runCatching { running.destroyForcibly() }
                runCatching { running.waitFor(750L, TimeUnit.MILLISECONDS) }
            }
            return
        }
        if (waitForProcesses(knownPids, 500L)) return

        signalProcesses(knownPids, OsConstants.SIGTERM)
        if (waitForProcesses(knownPids, 750L)) return

        val remaining = buildList {
            addAll(knownPids)
            findManagedServerPids(port).forEach { serverPid -> addAll(ownedProcessTree(serverPid)) }
        }.distinct()
        signalProcesses(remaining, OsConstants.SIGKILL)
        runCatching { running.destroyForcibly() }
        runCatching { running.waitFor(750L, TimeUnit.MILLISECONDS) }

        val survivors = (remaining + findManagedServerPids(port))
            .distinct()
            .filter(::isOwnedProcess)
        if (survivors.isNotEmpty()) {
            Log.w(TAG, "IDE process tree still has app-owned survivors: $survivors")
        }
    }

    private fun findManagedServerPids(port: Int): List<Int> {
        if (port !in 1024..65535) return emptyList()
        return File("/proc").listFiles().orEmpty().mapNotNull { entry ->
            val pid = entry.name.toIntOrNull() ?: return@mapNotNull null
            if (!isOwnedProcess(pid)) return@mapNotNull null
            val command = runCatching {
                File(entry, "cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
            }.getOrDefault("")
            pid.takeIf { command.contains("/opt/omochi/code-server-") }
        }
    }

    private fun ownedProcessTree(rootPid: Int): List<Int> {
        if (!isOwnedProcess(rootPid)) return emptyList()
        val visited = linkedSetOf<Int>()
        val pending = ArrayDeque<Int>().apply { add(rootPid) }
        while (pending.isNotEmpty() && visited.size < 256) {
            val pid = pending.removeFirst()
            if (!visited.add(pid) || !isOwnedProcess(pid)) continue
            processChildren(pid).forEach { child ->
                if (child !in visited && isOwnedProcess(child)) pending.addLast(child)
            }
        }
        return visited.toList()
    }

    private fun processChildren(pid: Int): List<Int> = runCatching {
        File("/proc/$pid/task/$pid/children")
            .readText()
            .trim()
            .split(Regex("\\s+"))
            .mapNotNull(String::toIntOrNull)
            .filter { it > 1 }
    }.getOrDefault(emptyList())

    private fun isOwnedProcess(pid: Int): Boolean = runCatching {
        val uidLine = File("/proc/$pid/status")
            .useLines { lines -> lines.firstOrNull { it.startsWith("Uid:") } }
            ?: return@runCatching false
        val realUid = uidLine.substringAfter(':').trim().split(Regex("\\s+")).first().toInt()
        realUid == AndroidProcess.myUid()
    }.getOrDefault(false)

    private fun signalProcesses(pids: List<Int>, signal: Int) {
        pids.asReversed().forEach { pid ->
            if (isOwnedProcess(pid)) runCatching { Os.kill(pid, signal) }
        }
    }

    private fun waitForProcesses(pids: List<Int>, timeoutMs: Long): Boolean {
        if (pids.isEmpty()) return true
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (pids.none(::isOwnedProcess)) return true
            try {
                Thread.sleep(25L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return pids.none(::isOwnedProcess)
    }

    private fun consumeOutput(context: Context, running: Process, runId: Long) {
        Thread({
            val logDir = File(context.filesDir, "logs").apply { mkdirs() }
            val logFile = File(logDir, "code-server.log")
            runCatching {
                if (logFile.length() > 2_000_000L) {
                    File(logDir, "code-server.previous.log").also {
                        if (it.exists()) it.delete()
                        logFile.renameTo(it)
                    }
                }
                logFile.bufferedWriter(Charsets.UTF_8, bufferSize = 32 * 1024).use { writer ->
                    running.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (BuildConfig.DEBUG) Log.d(TAG, line)
                            writer.appendLine(line)
                            writer.flush()
                        }
                    }
                }
            }.onFailure {
                if (generation.get() == runId && process === running) {
                    Log.w(TAG, "Could not persist server output", it)
                }
            }

            if (generation.get() == runId && process === running) {
                val exit = runCatching { running.exitValue() }.getOrDefault(-1)
                process = null
                activePort = 0
                val tail = readLogTail(logFile)
                publish(
                    State.Failed(
                        buildString {
                            append("IDEサーバーが終了しました (exit=$exit)")
                            if (tail.isNotBlank()) append("\n").append(tail)
                        }
                    )
                )
            }
        }, "OmochiServerOutput").start()
    }

    private fun pollUntilReady(context: Context, runId: Long, port: Int) {
        Thread({
            val deadline = System.nanoTime() + 90_000_000_000L
            var attempt = 0
            while (generation.get() == runId && System.nanoTime() < deadline) {
                val running = process
                if (running == null || !running.isAlive) return@Thread

                if (isResponding(port)) {
                    publish(
                        State.Running(
                            OmochiRuntime.serverUrl(
                                port,
                                WorkspaceSession.selectedGuestFolder(context),
                            )
                        )
                    )
                    return@Thread
                }

                attempt += 1
                if (attempt % 5 == 0) {
                    publish(State.Starting("IDEエンジンの準備を待っています… ${attempt / 2}秒"))
                }
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }

            if (generation.get() == runId) {
                val log = File(context.filesDir, "logs/code-server.log")
                publish(
                    State.Failed(
                        "IDEサーバーが90秒以内に応答しませんでした。\n${readLogTail(log)}"
                    )
                )
            }
        }, "OmochiServerProbe").start()
    }

    private fun isResponding(port: Int): Boolean {
        val connection = URI("http://127.0.0.1:$port/healthz")
            .toURL()
            .openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 500
            connection.readTimeout = 500
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "Omochi-Android")
            connection.connect()
            if (connection.responseCode != 200) return false
            val response = connection.inputStream.bufferedReader().use { it.readText().take(512) }
            response.contains("\"status\":\"alive\"") ||
                response.contains("\"status\":\"expired\"")
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun selectLoopbackPort(): Result<Int> = runCatching {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
            socket.reuseAddress = false
            socket.localPort.also { port ->
                check(port in 1024..65535) { "OSが無効なloopbackポートを返しました: $port" }
            }
        }
    }

    private fun readLogTail(file: File): String = runCatching {
        if (!file.isFile) return@runCatching ""
        file.readLines().takeLast(12).joinToString("\n").takeLast(2_000)
    }.getOrDefault("")

    private fun publish(state: State) {
        currentState = state
        main.post {
            listeners.forEach { listener ->
                runCatching { listener(state) }
            }
        }
    }
}
