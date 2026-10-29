package io.github.hatake716.omochi

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Owns Omochi's Linux and Code - OSS runtime completely inside the app sandbox.
 *
 * The PRoot bootstrap is derived from CCFA's Android/Bionic runtime. Omochi
 * never reads another app's Termux prefix and never requires root privileges.
 */
object OmochiRuntime {
    const val DEFAULT_CONTAINER = "omochi-linux"
    const val UBUNTU_RELEASE = "24.04.4"

    private const val UBUNTU_BASE_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz"
    private const val STATE_FILE = "active-container"
    private const val INSTALL_MARKER = ".omochi-rootfs"
    private const val IDE_MARKER = ".omochi-code-server"
    private const val EXPERIENCE_MARKER = ".omochi-japanese-claude-v1"
    private const val EXPERIENCE_SCHEMA = "2"
    private const val AUTH_FILE = "code-server-password"
    private const val JAPANESE_LANGUAGE_PACK_ID =
        "ms-ceintl.vscode-language-pack-ja"
    private const val OMOCHI_USER_DATA_GUEST = "/root/.local/share/omochi"
    private const val OMOCHI_EXTENSIONS_GUEST = "$OMOCHI_USER_DATA_GUEST/extensions"
    private const val CLAUDE_CODE_KEY_URL =
        "https://downloads.claude.ai/keys/claude-code.asc"
    private const val CLAUDE_CODE_KEY_FINGERPRINT =
        "31DDDE24DDFAB679F42D7BD2BAA929FF1A7ECACE"
    private const val CLAUDE_CODE_APT_SOURCE =
        "deb [signed-by=/etc/apt/keyrings/claude-code.asc] " +
            "https://downloads.claude.ai/claude-code/apt/stable stable main"
    private const val TAG = "OmochiRuntime"
    private const val RUNTIME_PREFERENCES = "omochi-runtime"
    private const val PREF_PROOT_SECCOMP = "proot-seccomp-v1"
    private const val PREF_PROOT_SECCOMP_FALLBACK_REASON = "proot-seccomp-fallback-reason-v1"
    private const val PREF_PROOT_SECCOMP_FALLBACK_AT = "proot-seccomp-fallback-at-v1"
    private const val SECCOMP_FALLBACK_REASON_LIMIT = 600
    private const val SELF_TEST_LOG = "proot-self-test.log"

    /** Upper bound for diagnostic output embedded in an exception shown by the UI. */
    internal const val UI_DIAGNOSTIC_LIMIT = 8 * 1024
    private const val SETTINGS_MIGRATION_MARKER = ".omochi-settings-migration"
    private val authLock = Any()
    private val settingsLock = Any()

    data class LaunchSpec(
        val executable: String,
        val cwd: String,
        val args: Array<String>,
        val env: Array<String>,
        val title: String,
        /** Whether this launch lets PRoot use its seccomp-bpf fast path. */
        val prootSeccomp: Boolean = false
    )

    /**
     * Whether PRoot may use seccomp-bpf to skip ptrace stops for syscalls it does not
     * rewrite. Without it every syscall of code-server, the extension host, ptyHost,
     * bash, git, rg and claude stops in the tracer, which makes the whole IDE slow.
     *
     * Enabled by default. It is off when the user turned it off, or after
     * [disableProotSeccomp] recorded an automatic fallback on this device.
     */
    fun prootSeccompEnabled(context: Context): Boolean {
        val preferences = runtimePreferences(context)
        return preferences.getBoolean(PREF_PROOT_SECCOMP, true) &&
            preferences.getString(PREF_PROOT_SECCOMP_FALLBACK_REASON, null) == null
    }

    /**
     * Persistently falls back to PROOT_NO_SECCOMP=1 after a launch that used seccomp
     * failed in a way that seccomp could explain. The reason and time are kept so the
     * dashboard can explain why the fast path is off; [setProotSeccompPreference] with
     * `true` clears the record.
     */
    fun disableProotSeccomp(context: Context, reason: String) {
        val compact = reason.trim().ifBlank { "原因不明の起動失敗" }
            .take(SECCOMP_FALLBACK_REASON_LIMIT)
        runtimePreferences(context).edit(commit = true) {
            putString(PREF_PROOT_SECCOMP_FALLBACK_REASON, compact)
            putLong(PREF_PROOT_SECCOMP_FALLBACK_AT, System.currentTimeMillis())
        }
        Log.w(TAG, "PRoot seccomp fast path disabled: $compact")
    }

    /**
     * Dashboard toggle. `true` also forgets an automatic fallback so the next session
     * launch tries seccomp again; a running session keeps the mode it started with.
     */
    fun setProotSeccompPreference(context: Context, enabled: Boolean) {
        runtimePreferences(context).edit(commit = true) {
            putBoolean(PREF_PROOT_SECCOMP, enabled)
            if (enabled) {
                remove(PREF_PROOT_SECCOMP_FALLBACK_REASON)
                remove(PREF_PROOT_SECCOMP_FALLBACK_AT)
            }
        }
    }

    /** Why seccomp was automatically disabled on this device (for display), or null. */
    fun prootSeccompFallbackReason(context: Context): String? =
        runtimePreferences(context).getString(PREF_PROOT_SECCOMP_FALLBACK_REASON, null)

    private fun runtimePreferences(context: Context) =
        context.getSharedPreferences(RUNTIME_PREFERENCES, Context.MODE_PRIVATE)

    data class InstallProgress(
        val phase: String,
        val message: String,
        val percent: Int? = null
    )

    enum class LaunchMode { SHELL, COMMAND }

    fun runtimeDir(context: Context) = File(context.filesDir, "embedded-runtime")
    fun cacheDir(context: Context) = File(runtimeDir(context), "cache")
    fun tempDir(context: Context) = File(runtimeDir(context), "tmp")
    fun containersDir(context: Context) = File(runtimeDir(context), "containers")
    fun workspaceDir(context: Context) = File(context.filesDir, "workspace")
    private fun activeFile(context: Context) = File(runtimeDir(context), STATE_FILE)
    fun containerDir(context: Context, name: String) = File(containersDir(context), name)
    fun rootfsDir(context: Context, name: String) = File(containerDir(context, name), "rootfs")

    /**
     * PRoot へ渡すホストパスの正規形。
     *
     * 現代の Android では Context.filesDir が "/data/user/0/<pkg>/..." を返す一方、
     * カーネル/bionic の realpath はアプリのマウント名前空間で同じ場所を
     * "/data/data/<pkg>/..." と名付けることがある(端末・バージョン依存)。
     * PRoot は --rootfs を起動時に realpath で正規化するが、PROOT_L2S_DIR は
     * 環境変数の文字列をそのまま使う。両者の綴りが食い違うと、link2symlink が
     * 生成する疑似ハードリンク(絶対パスのシンボリックリンク)を PRoot 自身が
     * ホストパスと認識できず、ゲストパスとして誤翻訳して ENOENT になる。
     * 具体的には初回セットアップの dpkg が perl-base のハードリンク展開
     * (link → fchownat)で「error setting ownership ... No such file or
     * directory」で必ず失敗する(Pixel 10a / Android 17 実機で確認)。
     *
     * 対策: PRoot に渡すすべてのホストパスを canonicalPath(= PRoot 内部の
     * realpath と同じ答え)に揃え、プレフィックスの不一致を構造的に防ぐ。
     */
    private fun File.canonical(): String =
        runCatching { canonicalPath }.getOrDefault(absolutePath)

    private fun nativeDir(context: Context) = File(context.applicationInfo.nativeLibraryDir)
    private fun prootBinary(context: Context) = File(nativeDir(context), "libproot.so")
    private fun prootLoader(context: Context) = File(nativeDir(context), "libproot-loader.so")
    private fun shmemLibrary(context: Context) = File(nativeDir(context), "libandroid-shmem.so")
    private fun tallocLibrary(context: Context) = File(nativeDir(context), "libtalloc.so")

    fun isSupportedAbi(): Boolean = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    fun ensureHostRuntime(context: Context): Result<File> = runCatching {
        check(isSupportedAbi()) { "現在の内蔵LinuxランタイムはARM64端末のみ対応しています。" }
        runtimeDir(context).mkdirs()
        cacheDir(context).mkdirs()
        tempDir(context).mkdirs()
        containersDir(context).mkdirs()
        workspaceDir(context).mkdirs()

        val required = listOf(
            prootBinary(context) to 50_000L,
            prootLoader(context) to 1_000L,
            shmemLibrary(context) to 1_000L,
            tallocLibrary(context) to 10_000L
        )
        required.forEach { (file, minimum) ->
            check(file.isFile && file.length() > minimum) {
                "Android向けPRootランタイム ${file.name} がAPK内にありません。Omochiを再インストールしてください。"
            }
        }
        prootBinary(context)
    }

    fun listContainers(context: Context): List<String> =
        containersDir(context).listFiles()
            ?.filter { dir ->
                dir.isDirectory && File(dir, "rootfs/$INSTALL_MARKER").isFile
            }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    fun activeContainer(context: Context): String? {
        val containers = listContainers(context)
        if (containers.isEmpty()) return null
        val saved = activeFile(context).readTextSafely().trim()
        if (saved in containers) return saved
        return containers.first().also { setActiveContainer(context, it) }
    }

    fun setActiveContainer(context: Context, name: String): Result<Unit> = runCatching {
        require(name in listContainers(context)) { "コンテナ '$name' は存在しません。" }
        runtimeDir(context).mkdirs()
        activeFile(context).writeText("$name\n")
    }

    fun deleteContainer(context: Context, name: String): Result<Unit> = runCatching {
        require(isValidContainerName(name)) { "無効なコンテナ名です。" }
        val target = containerDir(context, name)
        require(target.isDirectory) { "コンテナ '$name' は存在しません。" }
        check(target.deleteRecursively()) { "コンテナ '$name' を削除できませんでした。" }
        if (activeFile(context).readTextSafely().trim() == name) {
            val next = listContainers(context).firstOrNull()
            if (next == null) activeFile(context).delete()
            else activeFile(context).writeText("$next\n")
        }
        Unit
    }

    fun installUbuntuContainer(
        context: Context,
        name: String,
        onProgress: (InstallProgress) -> Unit,
        onComplete: (Result<Unit>) -> Unit
    ) {
        val appContext = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        fun progress(value: InstallProgress) = main.post { onProgress(value) }

        Thread({
            val result = runCatching {
                require(isValidContainerName(name)) {
                    "コンテナ名は英数字で開始し、英数字・.・_・- のみ使用できます。"
                }

                progress(InstallProgress("runtime", "Android/Bionic PRootランタイムを確認しています…", 2))
                ensureHostRuntime(appContext).getOrThrow()
                val hostVersion = hostProotSelfTest(appContext).getOrThrow()
                progress(InstallProgress("runtime", "PRoot本体テスト成功: $hostVersion", 4))
                check(name !in listContainers(appContext)) { "同名のコンテナがすでにあります。" }

                val archive = File(cacheDir(appContext), "ubuntu-base-$UBUNTU_RELEASE-arm64.tar.gz")
                if (!archive.isFile || !hasExpectedSha256(archive, BuildConfig.UBUNTU_BASE_SHA256)) {
                    archive.delete()
                    progress(InstallProgress("download", "Linux Baseのダウンロードを開始します…", 5))
                    downloadWithRetry(
                        UBUNTU_BASE_URL,
                        archive,
                        onAttempt = { attempt ->
                            if (attempt > 1) {
                                progress(InstallProgress("download", "Linux Baseを再試行しています ($attempt/3)…"))
                            }
                        },
                        onBytes = { current, total ->
                            val mib = current / (1024.0 * 1024.0)
                            if (total > 0) {
                                val raw = (current * 100L / total).toInt().coerceIn(0, 100)
                                val overall = 5 + raw * 40 / 100
                                val totalMib = total / (1024.0 * 1024.0)
                                progress(
                                    InstallProgress(
                                        "download",
                                        "Linux Baseをダウンロード中… %.1f / %.1f MiB (%d%%)".format(
                                            mib,
                                            totalMib,
                                            raw
                                        ),
                                        overall
                                    )
                                )
                            } else {
                                progress(
                                    InstallProgress(
                                        "download",
                                        "Linux Baseをダウンロード中… %.1f MiB".format(mib)
                                    )
                                )
                            }
                        }
                    )
                } else {
                    progress(InstallProgress("download", "検証済みLinux Baseキャッシュを使用します。", 45))
                }

                progress(InstallProgress("integrity", "Linux BaseのSHA-256を検証しています…", 47))
                check(hasExpectedSha256(archive, BuildConfig.UBUNTU_BASE_SHA256)) {
                    "Linux BaseアーカイブのSHA-256が一致しません。"
                }

                val rootfs = rootfsDir(appContext, name)
                if (rootfs.exists()) rootfs.deleteRecursively()
                check(rootfs.mkdirs() || rootfs.isDirectory) {
                    "Linux rootfs用ディレクトリを作成できません。"
                }

                progress(InstallProgress("extract", "Linux rootfsを展開しています…"))
                extractRootfs(archive, rootfs) { count ->
                    if (count % 500 == 0) {
                        progress(InstallProgress("extract", "Linux rootfsを展開中… $count files"))
                    }
                }

                progress(InstallProgress("configure", "Linux環境の基本設定を作成しています…", 82))
                configureRootfs(rootfs)

                progress(InstallProgress("self-test", "Android版PRootで /bin/sh を起動しています…", 88))
                val testOutput = selfTestContainer(appContext, name).getOrThrow()
                progress(InstallProgress("self-test", "Linux起動テスト成功: $testOutput", 96))

                File(rootfs, INSTALL_MARKER).writeText(
                    "base=ubuntu-$UBUNTU_RELEASE\n" +
                        "sha256=${BuildConfig.UBUNTU_BASE_SHA256}\n" +
                        "installed=${System.currentTimeMillis()}\n" +
                        "self_test=ok\n" +
                        "proot=termux-android\n"
                )
                setActiveContainer(appContext, name).getOrThrow()
                progress(InstallProgress("complete", "コンテナ '$name' の作成が完了しました。", 100))
                Unit
            }.onFailure {
                File(rootfsDir(appContext, name), INSTALL_MARKER).delete()
            }
            main.post { onComplete(result) }
        }, "OmochiLinuxInstaller").start()
    }

    /** Install both the private Ubuntu runtime and the pinned Code - OSS workbench. */
    fun installAll(
        context: Context,
        onProgress: (InstallProgress) -> Unit,
        onComplete: (Result<Unit>) -> Unit
    ) {
        fun installIde() {
            installCodeServer(
                context = context,
                onProgress = { value ->
                    val mapped = value.percent?.let { 35 + (it * 65 / 100) }
                    onProgress(value.copy(percent = mapped))
                },
                onComplete = onComplete
            )
        }

        if (isLinuxInstalled(context)) {
            onProgress(InstallProgress("linux", "Linuxランタイムはセットアップ済みです。", 35))
            installIde()
        } else {
            installUbuntuContainer(
                context = context,
                name = DEFAULT_CONTAINER,
                onProgress = { value ->
                    val mapped = value.percent?.let { it * 35 / 100 }
                    onProgress(value.copy(percent = mapped))
                },
                onComplete = { result ->
                    result.onSuccess { installIde() }
                        .onFailure { onComplete(Result.failure(it)) }
                }
            )
        }
    }

    fun isLinuxInstalled(context: Context): Boolean =
        File(rootfsDir(context, DEFAULT_CONTAINER), INSTALL_MARKER).isFile

    fun isInstalled(context: Context): Boolean {
        val rootfs = rootfsDir(context, DEFAULT_CONTAINER)
        return File(rootfs, INSTALL_MARKER).isFile &&
            File(rootfs, "opt/omochi/$IDE_MARKER").isFile &&
            serverHostBinary(context).isFile
    }

    fun installedVersion(context: Context): String? {
        val marker = File(rootfsDir(context, DEFAULT_CONTAINER), "opt/omochi/$IDE_MARKER")
        if (!marker.isFile) return null
        return marker.readLines()
            .firstOrNull { it.startsWith("version=") }
            ?.substringAfter('=')
    }

    fun isJapaneseClaudeInstalled(context: Context): Boolean {
        val rootfs = rootfsDir(context, DEFAULT_CONTAINER)
        val marker = markerValues(
            File(rootfs, "opt/omochi/$EXPERIENCE_MARKER").readTextSafely()
        )
        return marker["schema"] == EXPERIENCE_SCHEMA &&
            marker["locale"] == "ja" &&
            marker["language_pack_version"] == BuildConfig.VSCODE_JA_LANGUAGE_PACK_VERSION &&
            !marker["claude_code_version"].isNullOrBlank() &&
            File(rootfs, "usr/bin/claude").isFile &&
            hasJapaneseLanguagePack(rootfs)
    }

    fun installedClaudeCodeVersion(context: Context): String? {
        val marker = File(
            rootfsDir(context, DEFAULT_CONTAINER),
            "opt/omochi/$EXPERIENCE_MARKER"
        )
        return markerValues(marker.readTextSafely())["claude_code_version"]
    }

    internal fun markerValues(value: String): Map<String, String> = value
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith('#') && '=' in it }
        .associate { line -> line.substringBefore('=') to line.substringAfter('=') }

    private fun installCodeServer(
        context: Context,
        onProgress: (InstallProgress) -> Unit,
        onComplete: (Result<Unit>) -> Unit
    ) {
        val appContext = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        fun progress(value: InstallProgress) = main.post { onProgress(value) }

        Thread({
            val result = runCatching {
                ensureHostRuntime(appContext).getOrThrow()
                check(isLinuxInstalled(appContext)) { "Linuxランタイムがありません。" }

                if (isInstalled(appContext) && isJapaneseClaudeInstalled(appContext)) {
                    progress(
                        InstallProgress(
                            "complete",
                            "日本語ワークベンチとClaude Codeはセットアップ済みです。",
                            100
                        )
                    )
                    return@runCatching Unit
                }

                if (isInstalled(appContext)) {
                    progress(
                        InstallProgress(
                            "upgrade",
                            "IDEサーバーを停止して追加セットアップを開始します…",
                            55
                        )
                    )
                    OmochiServerManager.stop()
                    installJapaneseAndClaude(appContext, ::progress)
                    progress(
                        InstallProgress(
                            "complete",
                            "日本語ワークベンチとClaude Codeの準備が完了しました。",
                            100
                        )
                    )
                    return@runCatching Unit
                }

                val archiveName =
                    "code-server-${BuildConfig.CODE_SERVER_VERSION}-linux-arm64.tar.gz"
                val archive = File(cacheDir(appContext), archiveName)
                if (!archive.isFile || !hasExpectedSha256(
                        archive,
                        BuildConfig.CODE_SERVER_ARCHIVE_SHA256
                    )
                ) {
                    archive.delete()
                    progress(InstallProgress("ide-download", "Code - OSSエンジンを取得しています…", 2))
                    downloadWithRetry(
                        BuildConfig.CODE_SERVER_ARCHIVE_URL,
                        archive,
                        onAttempt = { attempt ->
                            if (attempt > 1) {
                                progress(
                                    InstallProgress(
                                        "ide-download",
                                        "IDE本体の取得を再試行しています ($attempt/3)…"
                                    )
                                )
                            }
                        },
                        onBytes = { current, total ->
                            val raw = if (total > 0) {
                                (current * 100L / total).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            val currentMiB = current / (1024.0 * 1024.0)
                            val message = if (total > 0) {
                                val totalMiB = total / (1024.0 * 1024.0)
                                "IDE本体を取得中… %.1f / %.1f MiB (%d%%)".format(
                                    currentMiB,
                                    totalMiB,
                                    raw
                                )
                            } else {
                                "IDE本体を取得中… %.1f MiB".format(currentMiB)
                            }
                            progress(
                                InstallProgress(
                                    "ide-download",
                                    message,
                                    2 + raw * 50 / 100
                                )
                            )
                        }
                    )
                } else {
                    progress(InstallProgress("ide-download", "検証済みキャッシュを使用します。", 52))
                }

                progress(InstallProgress("integrity", "IDE本体のSHA-256を検証しています…", 54))
                check(hasExpectedSha256(archive, BuildConfig.CODE_SERVER_ARCHIVE_SHA256)) {
                    "IDEアーカイブのSHA-256が一致しません。キャッシュを削除して再試行してください。"
                }

                val rootfs = rootfsDir(appContext, DEFAULT_CONTAINER)
                val opt = File(rootfs, "opt/omochi").apply { mkdirs() }
                val expected = serverHostDir(appContext)
                if (expected.exists()) {
                    check(expected.deleteRecursively()) {
                        "以前のIDE本体を置き換えられませんでした。"
                    }
                }
                File(opt, IDE_MARKER).delete()

                progress(InstallProgress("ide-extract", "IDE本体を展開しています…", 56))
                extractRootfs(archive, opt) { count ->
                    if (count % 1_000 == 0) {
                        progress(
                            InstallProgress(
                                "ide-extract",
                                "IDE本体を展開中… $count files",
                                (56 + count / 1_500).coerceAtMost(78)
                            )
                        )
                    }
                }
                check(serverHostBinary(appContext).isFile) {
                    "展開後のcode-server実行ファイルがありません。"
                }

                progress(InstallProgress("linux-tools", "Git・SSH・検索ツールを導入しています…", 80))
                val packageCommand = """
                    export DEBIAN_FRONTEND=noninteractive
                    apt-get -o Acquire::Retries=3 update
                    apt-get -o Acquire::Retries=3 install -y --no-install-recommends \
                      bash ca-certificates git openssh-client ripgrep curl wget \
                      zip unzip xz-utils locales libstdc++6 libatomic1 gnupg
                    apt-get clean
                    rm -rf /var/lib/apt/lists/*
                """.trimIndent()
                val packageResult = runGuestCommandSync(
                    context = appContext,
                    command = packageCommand,
                    timeoutSeconds = 1_200,
                    onLine = { line ->
                        val compact = line.trim().take(160)
                        if (compact.isNotEmpty()) {
                            progress(InstallProgress("linux-tools", compact, 86))
                        }
                    }
                )
                check(packageResult.exitCode == 0) {
                    "Linux開発ツールの導入に失敗しました (exit=${packageResult.exitCode}): " +
                        packageResult.output.takeLast(2_000)
                }

                progress(InstallProgress("configure", "タッチUIとmacOS風テーマを構成しています…", 88))
                configureCodeServer(appContext)
                createWelcomeFile(appContext)

                installJapaneseAndClaude(appContext, ::progress)

                progress(InstallProgress("self-test", "Code - OSSエンジンを検証しています…", 99))
                val testResult = runGuestCheckWithSeccompFallback(
                    context = appContext,
                    command = "${serverGuestBinary()} --version",
                    timeoutSeconds = 60,
                    label = "code-serverセルフテスト"
                )
                check(testResult.exitCode == 0) {
                    "code-serverセルフテストに失敗しました (exit=${testResult.exitCode}): " +
                        testResult.output.takeLast(2_000)
                }

                File(opt, IDE_MARKER).writeText(
                    "version=${BuildConfig.CODE_SERVER_VERSION}\n" +
                        "sha256=${BuildConfig.CODE_SERVER_ARCHIVE_SHA256}\n" +
                        "installed=${System.currentTimeMillis()}\n" +
                        "self_test=ok\n"
                )
                progress(
                    InstallProgress(
                        "complete",
                        "日本語ワークベンチとClaude Codeを含むセットアップが完了しました。",
                        100
                    )
                )
                Unit
            }
            main.post { onComplete(result) }
        }, "OmochiIdeInstaller").start()
    }

    fun buildServerLaunchSpec(context: Context, port: Int): Result<LaunchSpec> = runCatching {
        val rootfs = rootfsDir(context, DEFAULT_CONTAINER)
        check(isInstalled(context)) { "Omochiの初回セットアップが完了していません。" }
        require(port in 1024..65535) { "無効なloopbackポートです: $port" }
        // code-server が settings.json を読む前に、版更新で増えた既定値だけを反映する。
        // ユーザー設定の保全が優先なので、失敗しても起動は止めない。
        runCatching { ensureWorkbenchSettings(context, writeDefaultsIfMissing = false) }
            .onFailure { Log.w(TAG, "Workbench settings migration skipped", it) }
        val password = authPassword(context)
        val workspaceFolder = WorkspaceSession.selectedGuestFolder(context)

        val args = baseProotArgs(context, rootfs).toMutableList()
        args += listOf(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            "SHELL=/bin/bash",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "XDG_CONFIG_HOME=/root/.config",
            "XDG_DATA_HOME=/root/.local/share",
            "CS_DISABLE_GETTING_STARTED_OVERRIDE=1",
            "CODE_SERVER_APP_NAME=Omochi",
            "EXTENSIONS_GALLERY={}",
            "PASSWORD=$password",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            serverGuestBinary(),
            "--bind-addr", "127.0.0.1:$port",
            "--auth", "password",
            "--disable-telemetry",
            "--disable-update-check",
            "--disable-getting-started-override",
            "--locale", "ja",
            "--app-name", "Omochi",
            "--welcome-text", "Omochi ローカルワークベンチ",
            "--user-data-dir", "/root/.local/share/omochi",
            "--extensions-dir", "/root/.local/share/omochi/extensions",
            workspaceFolder
        )

        val proot = ensureHostRuntime(context).getOrThrow()
        val seccomp = prootSeccompEnabled(context)
        LaunchSpec(
            executable = proot.absolutePath,
            cwd = runtimeDir(context).absolutePath,
            args = args.toTypedArray(),
            env = hostEnvironment(context, rootfs, verbose = false, seccomp = seccomp)
                .map { "${it.key}=${it.value}" }
                .toTypedArray(),
            title = "Omochi — $workspaceFolder",
            prootSeccomp = seccomp
        )
    }

    fun serverUrl(port: Int, guestFolder: String = WorkspaceSession.GUEST_ROOT): String =
        WorkspaceSession.buildWorkbenchUrl(port, guestFolder)

    internal fun authPassword(context: Context): String = synchronized(authLock) {
        val file = File(runtimeDir(context), AUTH_FILE)
        val existing = file.readTextSafely().trim()
        if (existing.matches(Regex("[A-Za-z0-9_-]{43}"))) return@synchronized existing

        runtimeDir(context).mkdirs()
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val generated = Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        check(generated.length == 43) { "認証パスワードを生成できませんでした。" }
        val temporary = File(runtimeDir(context), "$AUTH_FILE.part")
        temporary.writeText("$generated\n")
        runCatching { Os.chmod(temporary.absolutePath, 0b110_000_000) }
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
        runCatching { Os.chmod(file.absolutePath, 0b110_000_000) }
        generated
    }

    private fun serverHostDir(context: Context): File = File(
        rootfsDir(context, DEFAULT_CONTAINER),
        "opt/omochi/code-server-${BuildConfig.CODE_SERVER_VERSION}-linux-arm64"
    )

    private fun serverHostBinary(context: Context): File =
        File(serverHostDir(context), "bin/code-server")

    private fun serverGuestBinary(): String =
        "/opt/omochi/code-server-${BuildConfig.CODE_SERVER_VERSION}-linux-arm64/bin/code-server"

    private fun findJapaneseLanguagePack(rootfs: File): File? {
        val extensions = File(rootfs, "root/.local/share/omochi/extensions")
        return extensions.listFiles().orEmpty().firstOrNull { directory ->
            if (!directory.isDirectory) return@firstOrNull false
            val manifest = File(directory, "package.json")
            runCatching {
                val json = JSONObject(manifest.readText())
                json.optString("publisher").equals("MS-CEINTL", ignoreCase = true) &&
                    json.optString("name") == "vscode-language-pack-ja" &&
                    json.optString("version") == BuildConfig.VSCODE_JA_LANGUAGE_PACK_VERSION
            }.getOrDefault(false)
        }
    }

    private fun hasJapaneseLanguagePack(rootfs: File): Boolean {
        val extension = findJapaneseLanguagePack(rootfs) ?: return false
        val mainTranslation = File(extension, "translations/main.i18n.json")
        if (!mainTranslation.isFile) return false

        val languagePacks = File(rootfs, "root/.local/share/omochi/languagepacks.json")
        return runCatching {
            val japanese = JSONObject(languagePacks.readText()).getJSONObject("ja")
            val expectedPath = "$OMOCHI_EXTENSIONS_GUEST/${extension.name}/translations/main.i18n.json"
            japanese.getString("hash") == languagePackHash(
                JAPANESE_LANGUAGE_PACK_ID,
                BuildConfig.VSCODE_JA_LANGUAGE_PACK_VERSION,
            ) && japanese.getJSONObject("translations").getString("vscode") == expectedPath
        }.getOrDefault(false)
    }

    /**
     * code-server's command-line installer extracts a language pack but does not
     * instantiate VS Code's language-pack cache participant.  The web workbench
     * resolves translations before extensions start, so generate the same
     * userData/languagepacks.json index that VS Code's NativeLanguagePackService
     * normally writes during an interactive extension install.
     */
    private fun configureJapaneseLanguagePack(rootfs: File) {
        val extension = findJapaneseLanguagePack(rootfs)
            ?: error("導入済み日本語Language Packの展開先を確認できません。")
        val manifest = JSONObject(File(extension, "package.json").readText())
        val localizations = manifest
            .getJSONObject("contributes")
            .getJSONArray("localizations")
        val japanese = (0 until localizations.length())
            .map { localizations.getJSONObject(it) }
            .firstOrNull { it.optString("languageId") == "ja" }
            ?: error("日本語Language Packにjaローカライズ定義がありません。")

        val extensionRoot = extension.canonicalFile
        val translations = JSONObject()
        val contributedTranslations = japanese.getJSONArray("translations")
        for (index in 0 until contributedTranslations.length()) {
            val contribution = contributedTranslations.getJSONObject(index)
            val id = contribution.getString("id")
            val relative = contribution.getString("path")
                .replace('\\', '/')
                .removePrefix("./")
            val hostFile = File(extensionRoot, relative).canonicalFile
            check(
                hostFile.path.startsWith(extensionRoot.path + File.separator) &&
                    hostFile.isFile
            ) {
                "日本語Language Packの翻訳ファイルが不正です: $relative"
            }
            translations.put(id, "$OMOCHI_EXTENSIONS_GUEST/${extension.name}/$relative")
        }
        check(translations.has("vscode")) {
            "日本語Language PackにCode - OSS本体の翻訳がありません。"
        }

        val hash = languagePackHash(
            JAPANESE_LANGUAGE_PACK_ID,
            BuildConfig.VSCODE_JA_LANGUAGE_PACK_VERSION,
        )
        val languagePack = JSONObject()
            .put("hash", hash)
            .put(
                "label",
                japanese.optString("localizedLanguageName")
                    .ifBlank { japanese.optString("languageName", "日本語") },
            )
            .put(
                "extensions",
                JSONArray().put(
                    JSONObject()
                        .put(
                            "extensionIdentifier",
                            JSONObject().put("id", JAPANESE_LANGUAGE_PACK_ID),
                        )
                        .put("version", BuildConfig.VSCODE_JA_LANGUAGE_PACK_VERSION),
                ),
            )
            .put("translations", translations)

        val userData = File(rootfs, "root/.local/share/omochi").apply { mkdirs() }
        val cache = File(userData, "clp/$hash.ja")
        if (cache.exists()) {
            check(cache.deleteRecursively()) {
                "以前の日本語UIキャッシュを更新できませんでした。"
            }
        }
        val destination = File(userData, "languagepacks.json")
        val temporary = File(userData, "languagepacks.json.part")
        temporary.writeText(JSONObject().put("ja", languagePack).toString(2) + "\n")
        Files.move(
            temporary.toPath(),
            destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    internal fun languagePackHash(identifier: String, version: String): String =
        MessageDigest.getInstance("MD5")
            .digest("$identifier$version".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun installJapaneseAndClaude(
        context: Context,
        progress: (InstallProgress) -> Unit,
    ) {
        val rootfs = rootfsDir(context, DEFAULT_CONTAINER)
        val opt = File(rootfs, "opt/omochi").apply { mkdirs() }
        check(serverHostBinary(context).isFile) {
            "日本語Language Packを導入するCode - OSSエンジンがありません。"
        }

        val vsixName =
            "vscode-language-pack-ja-${BuildConfig.VSCODE_JA_LANGUAGE_PACK_VERSION}.vsix"
        val cachedVsix = File(cacheDir(context), vsixName)
        if (!cachedVsix.isFile || !hasExpectedSha256(
                cachedVsix,
                BuildConfig.VSCODE_JA_LANGUAGE_PACK_SHA256,
            )
        ) {
            cachedVsix.delete()
            progress(InstallProgress("ja-download", "日本語UIを取得しています…", 89))
            downloadWithRetry(
                url = BuildConfig.VSCODE_JA_LANGUAGE_PACK_URL,
                destination = cachedVsix,
                minimumBytes = 500_000L,
                onAttempt = { attempt ->
                    if (attempt > 1) {
                        progress(
                            InstallProgress(
                                "ja-download",
                                "日本語UIの取得を再試行しています ($attempt/3)…",
                                89,
                            )
                        )
                    }
                },
                onBytes = { current, total ->
                    val message = if (total > 0) {
                        "日本語UIを取得中… %.1f / %.1f KiB".format(
                            current / 1024.0,
                            total / 1024.0,
                        )
                    } else {
                        "日本語UIを取得中… %.1f KiB".format(current / 1024.0)
                    }
                    progress(InstallProgress("ja-download", message, 90))
                },
            )
        } else {
            progress(InstallProgress("ja-download", "検証済み日本語UIを使用します。", 90))
        }
        check(hasExpectedSha256(cachedVsix, BuildConfig.VSCODE_JA_LANGUAGE_PACK_SHA256)) {
            "日本語Language PackのSHA-256が一致しません。"
        }

        progress(InstallProgress("ja-install", "設定・メニューの日本語表示を構成しています…", 91))
        val guestVsix = File(opt, vsixName)
        cachedVsix.copyTo(guestVsix, overwrite = true)
        try {
            val installResult = runGuestCommandSync(
                context = context,
                command = "${serverGuestBinary()} " +
                    "--user-data-dir /root/.local/share/omochi " +
                    "--extensions-dir /root/.local/share/omochi/extensions " +
                    "--install-extension /opt/omochi/$vsixName --force",
                timeoutSeconds = 180,
            )
            check(installResult.exitCode == 0) {
                "日本語Language Packの導入に失敗しました (exit=${installResult.exitCode}): " +
                    installResult.output.takeLast(2_000)
            }
        } finally {
            guestVsix.delete()
        }

        val listResult = runGuestCommandSync(
            context = context,
            command = "${serverGuestBinary()} " +
                "--user-data-dir /root/.local/share/omochi " +
                "--extensions-dir /root/.local/share/omochi/extensions " +
                "--list-extensions --show-versions",
            timeoutSeconds = 60,
        )
        val expectedLanguagePack =
            "ms-ceintl.vscode-language-pack-ja@${BuildConfig.VSCODE_JA_LANGUAGE_PACK_VERSION}"
        check(
            listResult.exitCode == 0 &&
                listResult.output.lineSequence().any {
                    it.trim().equals(expectedLanguagePack, ignoreCase = true)
                }
        ) {
            "日本語Language PackをCode - OSSが認識できません: " +
                listResult.output.takeLast(2_000)
        }

        configureJapaneseLanguagePack(rootfs)
        check(hasJapaneseLanguagePack(rootfs)) {
            "日本語Language Packの起動前インデックスを作成できませんでした。"
        }

        val userDir = File(rootfs, "root/.local/share/omochi/User").apply { mkdirs() }
        val argvFile = File(userDir, "argv.json")
        val argv = runCatching { JSONObject(argvFile.readTextSafely()) }.getOrElse { JSONObject() }
        argv.put("locale", "ja")
        argvFile.writeText(argv.toString(2) + "\n")

        progress(InstallProgress("claude-install", "Claude Code公式署名鍵を確認しています…", 93))
        val claudeCommand = """
            set -eu
            export DEBIAN_FRONTEND=noninteractive
            apt-get -o Acquire::Retries=3 update
            apt-get -o Acquire::Retries=3 install -y --no-install-recommends \
              ca-certificates curl gnupg
            install -d -m 0755 /etc/apt/keyrings
            curl -fsSL '${CLAUDE_CODE_KEY_URL}' -o /etc/apt/keyrings/claude-code.asc
            gpg --batch --show-keys --with-colons /etc/apt/keyrings/claude-code.asc \
              | grep -Fqx 'fpr:::::::::${CLAUDE_CODE_KEY_FINGERPRINT}:'
            printf '%s\n' '${CLAUDE_CODE_APT_SOURCE}' \
              > /etc/apt/sources.list.d/claude-code.list
            apt-get -o Acquire::Retries=3 update
            apt-get -o Acquire::Retries=3 install -y --no-install-recommends claude-code
            apt-get clean
            rm -rf /var/lib/apt/lists/*
        """.trimIndent()
        val claudeInstall = runGuestCommandSync(
            context = context,
            command = claudeCommand,
            timeoutSeconds = 1_200,
            onLine = { line ->
                val compact = line.trim().take(160)
                if (compact.isNotEmpty()) {
                    progress(InstallProgress("claude-install", compact, 96))
                }
            },
        )
        check(claudeInstall.exitCode == 0) {
            "Claude Codeの導入に失敗しました (exit=${claudeInstall.exitCode}): " +
                claudeInstall.output.takeLast(2_000)
        }

        progress(InstallProgress("claude-test", "Claude Codeを実行して確認しています…", 98))
        val claudeTest = runGuestCheckWithSeccompFallback(
            context = context,
            command = "claude --version",
            timeoutSeconds = 120,
            label = "Claude Codeセルフテスト",
        )
        check(claudeTest.exitCode == 0) {
            "Claude Codeセルフテストに失敗しました (exit=${claudeTest.exitCode}): " +
                claudeTest.output.takeLast(2_000)
        }
        val claudeVersion = Regex("""\d+\.\d+\.\d+""")
            .find(claudeTest.output)
            ?.value
            ?: error("Claude Codeのバージョンを確認できません: ${claudeTest.output.take(500)}")

        createClaudeGuideFile(context)
        File(opt, EXPERIENCE_MARKER).writeText(
            "schema=$EXPERIENCE_SCHEMA\n" +
                "locale=ja\n" +
                "language_pack_version=${BuildConfig.VSCODE_JA_LANGUAGE_PACK_VERSION}\n" +
                "language_pack_sha256=${BuildConfig.VSCODE_JA_LANGUAGE_PACK_SHA256}\n" +
                "claude_code_version=$claudeVersion\n" +
                "claude_code_channel=stable\n" +
                "claude_signing_key_fingerprint=$CLAUDE_CODE_KEY_FINGERPRINT\n" +
                "installed=${System.currentTimeMillis()}\n" +
                "self_test=ok\n"
        )
    }

    private fun configureCodeServer(context: Context) {
        // 再セットアップ(code-server の版更新を含む)でユーザー設定を消さない。
        // 既存の settings.json には移行だけを適用する。
        ensureWorkbenchSettings(context, writeDefaultsIfMissing = true)

        val product = File(serverHostDir(context), "lib/vscode/product.json")
        if (product.isFile) {
            val json = JSONObject(product.readText())
            json.remove("extensionsGallery")
            json.put("nameShort", "Omochi")
            json.put("nameLong", "Omochi Code Workbench")
            json.put("applicationName", "omochi")
            product.writeText(json.toString(2) + "\n")
        }
    }

    /**
     * Keeps User/settings.json in step with Omochi's defaults without taking it over.
     *
     * A missing or empty file receives [WorkbenchSettings.defaultSettingsText] when
     * [writeDefaultsIfMissing]. An existing file only gets [WorkbenchSettings.migrate],
     * once per [WorkbenchSettings.VERSION]: the marker next to the user data lets later
     * launches skip the work and keeps a key the user deletes afterwards from coming
     * back. A file the migration declines (comments, syntax errors) is not marked, so
     * it is looked at again once the user fixes it.
     */
    internal fun ensureWorkbenchSettings(context: Context, writeDefaultsIfMissing: Boolean) {
        val userData = File(rootfsDir(context, DEFAULT_CONTAINER), "root/.local/share/omochi")
        val action = synchronized(settingsLock) {
            syncWorkbenchSettings(
                settingsFile = File(userData, "User/settings.json"),
                marker = File(userData, SETTINGS_MIGRATION_MARKER),
                writeDefaultsIfMissing = writeDefaultsIfMissing
            )
        }
        if (action != null) Log.i(TAG, "Workbench settings: $action")
    }

    /**
     * File side of [ensureWorkbenchSettings], free of Android APIs so JVM tests run it.
     * Returns a short description of what it did, or null when nothing was needed.
     */
    internal fun syncWorkbenchSettings(
        settingsFile: File,
        marker: File,
        writeDefaultsIfMissing: Boolean
    ): String? {
        // ゲスト内のドットファイルへのリンクはゲストの絶対パスを指すため、ホストからは
        // 正しく辿れない。置き換えるとリンクを壊すので触らない。
        if (Files.isSymbolicLink(settingsFile.toPath())) return "symbolic link left as is"
        if (settingsFile.exists() && !settingsFile.isFile) return "not a regular file"
        val markerText = "version=${WorkbenchSettings.VERSION}\n"
        if (!settingsFile.exists() || settingsFile.length() == 0L) {
            if (!writeDefaultsIfMissing) return null
            writeAtomically(settingsFile, WorkbenchSettings.defaultSettingsText())
            writeAtomically(marker, markerText)
            return "defaults written"
        }

        val applied = markerValues(marker.readTextSafely())["version"]?.toIntOrNull() ?: 0
        if (applied >= WorkbenchSettings.VERSION) return null
        return when (val outcome = WorkbenchSettings.migrate(settingsFile.readText())) {
            is WorkbenchSettings.Outcome.Updated -> {
                writeAtomically(settingsFile, outcome.text)
                writeAtomically(marker, markerText)
                "migrated ${outcome.changedKeys}"
            }
            WorkbenchSettings.Outcome.Unchanged -> {
                writeAtomically(marker, markerText)
                "already current"
            }
            is WorkbenchSettings.Outcome.Skipped -> "left as is: ${outcome.reason}"
        }
    }

    private fun writeAtomically(target: File, text: String) {
        val parent = target.absoluteFile.parentFile ?: error("親フォルダーがありません: $target")
        parent.mkdirs()
        val temporary = File(parent, "${target.name}.part")
        temporary.writeText(text)
        Files.move(
            temporary.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
    }

    private fun createWelcomeFile(context: Context) {
        val workspace = workspaceDir(context).apply { mkdirs() }
        val welcome = File(workspace, "Omochiへようこそ.md")
        if (!welcome.exists()) {
            welcome.writeText(
                """
                # Omochiへようこそ

                このフォルダは端末内のプライベートな `/workspace` です。

                - 左端のExplorerからファイルを作成できます。
                - 上部の虫眼鏡でワークスペース全体を検索できます。
                - `>_` で統合ターミナルを開けます。
                - 画面下のタッチキーバーから Esc / Ctrl / Alt / Tab / 矢印を送れます。
                - Git clone、ステージ、コミット、ブランチ操作はSource Controlから利用できます。
                - 統合ターミナルで `claude` を実行するとClaude Codeを開始できます。

                設定・メニューは日本語Language Packで表示します。外部拡張マーケットは無効です。
                言語処理系やビルドツールは統合ターミナルからUbuntu環境へ導入してください。
                """.trimIndent() + "\n"
            )
        }
    }

    private fun createClaudeGuideFile(context: Context) {
        val workspace = workspaceDir(context).apply { mkdirs() }
        val guide = File(workspace, "Claude Codeを使う.md")
        if (!guide.exists()) {
            guide.writeText(
                """
                # Claude Codeを使う

                Omochiの統合ターミナルを開き、プロジェクトのフォルダで次を実行します。

                ```bash
                claude
                ```

                初回だけAnthropicアカウントでの認証が必要です。表示された案内に従って、
                Claude Pro / Max / Team / Enterprise またはAnthropic Consoleのアカウントで
                ログインしてください。無料のClaude.aiプランだけではClaude Codeを利用できません。

                バージョン確認:

                ```bash
                claude --version
                ```

                Claude CodeはOmochiのUbuntu環境内で動作し、現在開いているプロジェクトの
                ファイルを読み書きできます。実行を許可するコマンドと変更内容を確認してから
                承認してください。
                """.trimIndent() + "\n"
            )
        }
    }

    internal data class GuestCommandResult(val exitCode: Int, val output: String)

    private fun runGuestCommandSync(
        context: Context,
        command: String,
        timeoutSeconds: Long,
        onLine: (String) -> Unit = {},
        seccomp: Boolean = prootSeccompEnabled(context)
    ): GuestCommandResult {
        val rootfs = rootfsDir(context, DEFAULT_CONTAINER)
        val fullCommand = baseProotArgs(context, rootfs) + listOf(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "TERM=xterm-256color",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "/bin/bash", "-lc", command
        )
        val builder = ProcessBuilder(fullCommand)
            .directory(runtimeDir(context))
            .redirectErrorStream(true)
        builder.environment().apply {
            clear()
            putAll(hostEnvironment(context, rootfs, verbose = false, seccomp = seccomp))
        }
        val process = builder.start()
        val output = StringBuilder()
        val reader = Thread({
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(output) {
                        if (output.length > 2_000_000) {
                            output.delete(0, output.length - 1_000_000)
                        }
                        output.appendLine(line)
                    }
                    onLine(line)
                }
            }
        }, "OmochiGuestOutput").apply { start() }

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            reader.join(2_000)
            error("Linuxコマンドが${timeoutSeconds}秒以内に完了しませんでした。")
        }
        reader.join(5_000)
        return GuestCommandResult(
            exitCode = process.exitValue(),
            output = synchronized(output) { output.toString().trim() }
        )
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun hasExpectedSha256(file: File, expected: String): Boolean =
        file.isFile && sha256(file).equals(expected, ignoreCase = true)

    private fun hostProotSelfTest(context: Context): Result<String> = runCatching {
        val proot = ensureHostRuntime(context).getOrThrow()
        val result = runHostProcess(
            context = context,
            command = listOf(proot.absolutePath, "--version"),
            rootfs = null,
            timeoutSeconds = 10,
            verbose = false,
            seccomp = prootSeccompEnabled(context)
        )
        check(result.exitCode == 0) {
            "Android版PRoot本体を実行できません (exit=${result.exitCode}): " +
                diagnosticExcerpt(result.output.ifBlank { "no output" })
        }
        result.output.lineSequence().firstOrNull().orEmpty().ifBlank { "proot executable OK" }
    }

    /**
     * Boots /bin/sh through PRoot with PROOT_VERBOSE. When the seccomp fast path is on
     * and the probe fails, it is retried once with PROOT_NO_SECCOMP=1; if only that
     * succeeds, this device is switched to the ptrace-only mode for good (see
     * [disableProotSeccomp]) and setup continues.
     */
    fun selfTestContainer(context: Context, name: String): Result<String> = runCatching {
        ensureHostRuntime(context).getOrThrow()
        val rootfs = rootfsDir(context, name)
        check(File(rootfs, "bin/sh").isFile) {
            "Linux rootfsに /bin/sh がありません。展開に失敗しています。"
        }

        val log = selfTestLogFile(context)
        runCatching { log.parentFile?.mkdirs(); log.writeText("") }
        val seccomp = prootSeccompEnabled(context)
        val first = containerProbe(context, rootfs, seccomp, log)
        val firstError = first.exceptionOrNull()
        if (firstError != null) {
            if (!seccomp) throw firstError
            // seccomp を切っても失敗するなら seccomp は原因ではない。方針は変えずにその失敗を報告する。
            containerProbe(context, rootfs, seccomp = false, log = log).getOrThrow()
            disableProotSeccomp(
                context,
                "Linux起動テストがseccomp有効時だけ失敗しました: " +
                    firstError.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(200)
            )
        }
        "embedded-runtime-ok"
    }

    private fun containerProbe(
        context: Context,
        rootfs: File,
        seccomp: Boolean,
        log: File
    ): Result<Unit> = runCatching {
        val result = runHostProcess(
            context = context,
            command = baseProotArgs(context, rootfs) + listOf(
                "/bin/sh", "-c", "printf 'embedded-runtime-ok'"
            ),
            rootfs = rootfs,
            timeoutSeconds = 25,
            verbose = true,
            seccomp = seccomp
        )
        // PROOT_VERBOSE=9 の出力は数百KBになる。全文はファイルへ残し、UI には抜粋だけ渡す。
        runCatching {
            log.appendText(
                "== seccomp=${if (seccomp) "on" else "off"} exit=${result.exitCode} ==\n" +
                    result.output + "\n"
            )
        }
        val output = result.output.ifBlank { "no output" }
        check(result.exitCode == 0) {
            "PRootセルフテスト失敗 (exit=${result.exitCode}, seccomp=${if (seccomp) "on" else "off"}): " +
                diagnosticExcerpt(output, log)
        }
        check(result.output.contains("embedded-runtime-ok")) {
            "Linuxセルフテストの応答が不正です: " + diagnosticExcerpt(output, log)
        }
    }

    private fun selfTestLogFile(context: Context) =
        File(File(context.filesDir, "logs"), SELF_TEST_LOG)

    private fun diagnosticExcerpt(output: String, log: File? = null): String =
        boundedDiagnostic(output, UI_DIAGNOSTIC_LIMIT, log?.absolutePath)

    /**
     * Shrinks tool output for an exception message that ends up in the UI. The tail
     * carries the actual error, so it keeps a short head (PRoot's configuration dump)
     * and the rest from the end, naming the file that holds the full log.
     */
    internal fun boundedDiagnostic(output: String, limit: Int, fullLogPath: String?): String {
        if (output.length <= limit) return output
        val headLength = (limit / 8).coerceAtMost(1_024)
        val tailLength = (limit - headLength).coerceAtLeast(0)
        var headEnd = headLength
        if (headEnd in 1 until output.length && output[headEnd - 1].isHighSurrogate()) headEnd--
        var tailStart = output.length - tailLength
        if (tailStart in 1 until output.length && output[tailStart].isLowSurrogate()) tailStart++
        val omitted = tailStart - headEnd
        val where = fullLogPath?.let { "。完全なログ: $it" }.orEmpty()
        return output.substring(0, headEnd) +
            "\n… ($omitted 文字省略$where) …\n" +
            output.substring(tailStart)
    }

    /**
     * Runs a short guest check (for example `code-server --version`). With seccomp on,
     * a failure is retried once with PROOT_NO_SECCOMP=1, and a pass there records the
     * automatic fallback. Returns the result that decides the check.
     */
    private fun runGuestCheckWithSeccompFallback(
        context: Context,
        command: String,
        timeoutSeconds: Long,
        label: String
    ): GuestCommandResult {
        val seccomp = prootSeccompEnabled(context)
        val first = runCatching {
            runGuestCommandSync(context, command, timeoutSeconds, seccomp = seccomp)
        }
        if (!seccomp || first.getOrNull()?.exitCode == 0) return first.getOrThrow()

        val retry = runGuestCommandSync(context, command, timeoutSeconds, seccomp = false)
        if (retry.exitCode == 0) {
            val cause = first.fold(
                onSuccess = { "exit=${it.exitCode}" },
                onFailure = { it.message.orEmpty().take(200) }
            )
            disableProotSeccomp(context, "${label}がseccomp有効時だけ失敗しました ($cause)")
        }
        return retry
    }

    fun buildLaunchSpec(
        context: Context,
        container: String,
        mode: LaunchMode,
        command: String? = null
    ): Result<LaunchSpec> = runCatching {
        val d = '$'
        val proot = ensureHostRuntime(context).getOrThrow()
        require(container in listContainers(context)) { "コンテナ '$container' がありません。" }
        val rootfs = rootfsDir(context, container)

        val guestCommand = when (mode) {
            LaunchMode.SHELL -> "exec /bin/bash -l"
            LaunchMode.COMMAND -> {
                require(!command.isNullOrBlank()) { "コマンドが空です。" }
                "$command; rc=${d}?; printf '\\n\\n[exit: %s] Enterでシェルへ戻ります...' \"${d}rc\"; read _; exec /bin/bash -l"
            }
        }

        val args = baseProotArgs(context, rootfs).toMutableList()
        args += listOf(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "TERM=xterm-256color",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/root/.local/bin",
            "/bin/bash", "-lc", guestCommand
        )

        val seccomp = prootSeccompEnabled(context)
        LaunchSpec(
            executable = proot.absolutePath,
            cwd = runtimeDir(context).absolutePath,
            args = args.toTypedArray(),
            env = hostEnvironment(context, rootfs, verbose = false, seccomp = seccomp)
                .map { "${it.key}=${it.value}" }
                .toTypedArray(),
            title = when (mode) {
                LaunchMode.SHELL -> "$container — Linux"
                LaunchMode.COMMAND -> "$container — Task"
            },
            prootSeccomp = seccomp
        )
    }

    private fun baseProotArgs(context: Context, rootfs: File): List<String> {
        workspaceDir(context).mkdirs()
        File(rootfs, ".l2s").mkdirs()
        return listOf(
            prootBinary(context).canonical(),
            "--kill-on-exit",
            "--link2symlink",
            "-L",
            "--change-id=0:0",
            // canonicalPath 必須: PROOT_L2S_DIR と同じ綴りでなければならない(上記 canonical() 参照)
            "--rootfs=${rootfs.canonical()}",
            "--cwd=/workspace",
            "--bind=/dev",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=${workspaceDir(context).canonical()}:/workspace"
        )
    }

    fun isValidContainerName(name: String): Boolean =
        name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))

    private fun configureRootfs(rootfs: File) {
        val etc = File(rootfs, "etc").apply { mkdirs() }
        File(etc, "resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        File(etc, "hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(rootfs, "root").mkdirs()
        File(rootfs, "workspace").mkdirs()
        File(rootfs, ".l2s").mkdirs()
        File(rootfs, "tmp").apply {
            mkdirs()
            runCatching { Os.chmod(absolutePath, 0b111_111_111) }
        }
    }

    private fun downloadWithRetry(
        url: String,
        destination: File,
        minimumBytes: Long = 20_000_000L,
        onAttempt: (Int) -> Unit,
        onBytes: (Long, Long) -> Unit
    ) {
        var lastError: Throwable? = null
        for (attempt in 1..3) {
            onAttempt(attempt)
            try {
                downloadTo(url, destination, minimumBytes, onBytes)
                return
            } catch (t: Throwable) {
                lastError = t
                File(destination.parentFile, destination.name + ".part").delete()
                if (attempt < 3) Thread.sleep(1500L * attempt)
            }
        }
        throw IllegalStateException(
            "ダウンロードを3回試行しましたが完了できませんでした: ${lastError?.message ?: "unknown error"}",
            lastError
        )
    }

    private fun downloadTo(
        url: String,
        destination: File,
        minimumBytes: Long,
        progress: (Long, Long) -> Unit
    ) {
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, destination.name + ".part")
        temp.delete()
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Omochi/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.connect()
            check(connection.responseCode in 200..299) {
                "HTTP ${connection.responseCode} ${connection.responseMessage}"
            }
            val total = connection.contentLengthLong
            var written = 0L
            var lastReport = 0L
            progress(0, total)
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (written - lastReport >= 1024 * 1024) {
                            lastReport = written
                            progress(written, total)
                        }
                    }
                    output.fd.sync()
                }
            }
            progress(written, total)
            check(written >= minimumBytes) {
                "ダウンロードサイズが不正です (${written} bytes、最低${minimumBytes} bytes)。"
            }
            Files.move(
                temp.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun extractRootfs(archive: File, rootfs: File, progress: (Int) -> Unit) {
        val rootCanonical = rootfs.canonicalFile
        val pendingHardLinks = mutableListOf<Pair<File, String>>()
        var count = 0
        GzipCompressorInputStream(BufferedInputStream(archive.inputStream())).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                while (true) {
                    val entry = tar.nextEntry as? TarArchiveEntry ?: break
                    val output = safeArchiveTarget(rootCanonical, entry.name)
                    progress(++count)
                    when {
                        entry.isDirectory -> output.mkdirs()
                        entry.isSymbolicLink -> {
                            output.parentFile?.mkdirs()
                            Files.deleteIfExists(output.toPath())
                            Files.createSymbolicLink(output.toPath(), File(entry.linkName).toPath())
                        }
                        entry.isLink -> pendingHardLinks += output to entry.linkName
                        entry.isFile -> {
                            output.parentFile?.mkdirs()
                            FileOutputStream(output).use { tar.copyTo(it) }
                            runCatching { Os.chmod(output.absolutePath, entry.mode and 0x1ff) }
                        }
                    }
                }
            }
        }
        pendingHardLinks.forEach { (output, linkName) ->
            val source = safeArchiveTarget(rootCanonical, linkName)
            if (source.exists()) {
                output.parentFile?.mkdirs()
                Files.deleteIfExists(output.toPath())
                Files.copy(
                    source.toPath(),
                    output.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        }
    }

    private data class ProcessResult(val exitCode: Int, val output: String)

    private fun runHostProcess(
        context: Context,
        command: List<String>,
        rootfs: File?,
        timeoutSeconds: Long,
        verbose: Boolean,
        seccomp: Boolean
    ): ProcessResult {
        val builder = ProcessBuilder(command)
            .directory(runtimeDir(context))
            .redirectErrorStream(true)
        builder.environment().apply {
            clear()
            putAll(hostEnvironment(context, rootfs, verbose, seccomp))
        }
        val process = builder.start()
        val captured = StringBuilder()
        val reader = Thread({
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(captured) {
                        if (captured.length > 1_000_000) {
                            captured.delete(0, captured.length - 500_000)
                        }
                        captured.appendLine(line)
                    }
                }
            }
        }, "OmochiHostProcessOutput").apply { start() }
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            reader.join(2_000)
            error("PRootテストが${timeoutSeconds}秒以内に完了しませんでした。")
        }
        reader.join(5_000)
        val output = synchronized(captured) { captured.toString().trim() }
        return ProcessResult(process.exitValue(), output)
    }

    private fun hostEnvironment(
        context: Context,
        rootfs: File?,
        verbose: Boolean,
        seccomp: Boolean
    ): MutableMap<String, String> = prootHostEnvironment(
        home = context.filesDir.canonical(),
        tempDir = tempDir(context).canonical(),
        loader = prootLoader(context).canonical(),
        libraryPath = nativeDir(context).canonical(),
        // canonicalPath 必須: PRoot は --rootfs を realpath で正規化する一方、
        // PROOT_L2S_DIR は文字列をそのまま使うため、綴りを揃えないと
        // link2symlink の疑似ハードリンクが解決不能になる(canonical() の説明参照)。
        l2sDir = rootfs?.let { File(it, ".l2s").apply { mkdirs() }.canonical() },
        seccomp = seccomp,
        verbose = verbose
    )

    /**
     * The host-side environment PRoot itself runs with. Pure so JVM tests can pin
     * which variables each mode sets. PROOT_NO_SECCOMP is present only when the
     * seccomp-bpf fast path is off: PRoot checks the variable for existence, so any
     * value (even "0") would disable seccomp.
     */
    internal fun prootHostEnvironment(
        home: String,
        tempDir: String,
        loader: String,
        libraryPath: String,
        l2sDir: String?,
        seccomp: Boolean,
        verbose: Boolean
    ): MutableMap<String, String> = linkedMapOf<String, String>().apply {
        put("HOME", home)
        put("TMPDIR", tempDir)
        put("PROOT_TMP_DIR", tempDir)
        put("PROOT_LOADER", loader)
        if (!seccomp) put("PROOT_NO_SECCOMP", "1")
        put("LD_LIBRARY_PATH", libraryPath)
        put("PATH", "/system/bin:/system/xbin")
        put("ANDROID_DATA", "/data")
        put("ANDROID_ROOT", "/system")
        put("TERM", "xterm-256color")
        if (l2sDir != null) put("PROOT_L2S_DIR", l2sDir)
        if (verbose) put("PROOT_VERBOSE", "9")
    }

    internal fun safeArchiveTarget(root: File, name: String): File {
        val cleaned = name.removePrefix("./").removePrefix("/")
        val output = File(root, cleaned).canonicalFile
        check(output.path == root.path || output.path.startsWith(root.path + File.separator)) {
            "Unsafe archive path: $name"
        }
        return output
    }

    private fun File.readTextSafely(): String =
        runCatching { if (isFile) readText() else "" }.getOrDefault("")
}

/**
 * Omochi's defaults for the Code - OSS user settings and their versioned migration.
 *
 * settings.json belongs to the user once it exists. [migrate] only adds keys the file
 * does not have and replaces values that still exactly equal an older Omochi default.
 * It edits the original text in place, so the user's formatting, key order and other
 * values survive byte for byte. Files with comments (JSONC) are left alone rather than
 * risk losing the comments.
 *
 * Pure Kotlin with its own small JSON reader so JVM tests exercise exactly the code
 * that runs on the device (Android's org.json is only a stub there).
 */
internal object WorkbenchSettings {
    /** Bump together with a new rule in [replacements] or [additions]. */
    const val VERSION = 2

    const val LIGHT_THEME = "Default Light Modern"

    private const val MAX_DEPTH = 64

    /** The warm palette Omochi 0.x applied to every theme (the old global default). */
    val legacyWarmPalette: Map<String, Any?> = linkedMapOf(
        "titleBar.activeBackground" to "#E9E4DF",
        "titleBar.activeForeground" to "#24262B",
        "activityBar.background" to "#EEEAE6",
        "activityBar.foreground" to "#24262B",
        "activityBar.inactiveForeground" to "#777A82",
        "sideBar.background" to "#F4F1ED",
        "sideBar.foreground" to "#2F3035",
        "sideBar.border" to "#D5CFC9",
        "editorGroupHeader.tabsBackground" to "#ECE8E3",
        "tab.activeBackground" to "#FFFFFF",
        "tab.inactiveBackground" to "#ECE8E3",
        "tab.border" to "#D5CFC9",
        "statusBar.background" to "#C96954",
        "statusBar.foreground" to "#FFFFFF",
        "focusBorder" to "#C96954",
        "button.background" to "#C96954",
        "button.hoverBackground" to "#B85B48",
        "list.activeSelectionBackground" to "#D9CFCA",
        "list.hoverBackground" to "#E8E2DD",
    )

    /** iOS system colours for [LIGHT_THEME]. */
    val iosLightPalette: Map<String, Any?> = linkedMapOf(
        "titleBar.activeBackground" to "#F2F2F7",
        "titleBar.activeForeground" to "#000000",
        "titleBar.inactiveBackground" to "#F2F2F7",
        "activityBar.background" to "#F2F2F7",
        "activityBar.foreground" to "#007AFF",
        "activityBar.inactiveForeground" to "#8E8E93",
        "activityBar.activeBorder" to "#007AFF",
        "activityBarBadge.background" to "#FF3B30",
        "activityBarBadge.foreground" to "#FFFFFF",
        "sideBar.background" to "#F2F2F7",
        "sideBar.foreground" to "#1C1C1E",
        "sideBar.border" to "#D1D1D6",
        "sideBarSectionHeader.background" to "#F2F2F7",
        "editorGroupHeader.tabsBackground" to "#F2F2F7",
        "tab.activeBackground" to "#FFFFFF",
        "tab.inactiveBackground" to "#F2F2F7",
        "tab.activeForeground" to "#000000",
        "tab.inactiveForeground" to "#8E8E93",
        "tab.border" to "#E5E5EA",
        "tab.activeBorderTop" to "#007AFF",
        "statusBar.background" to "#F2F2F7",
        "statusBar.foreground" to "#3C3C43",
        "statusBar.border" to "#D1D1D6",
        "statusBar.noFolderBackground" to "#F2F2F7",
        "statusBarItem.remoteBackground" to "#007AFF",
        "statusBarItem.remoteForeground" to "#FFFFFF",
        "focusBorder" to "#007AFF",
        "button.background" to "#007AFF",
        "button.hoverBackground" to "#0062CC",
        "button.foreground" to "#FFFFFF",
        "button.secondaryBackground" to "#E5E5EA",
        "button.secondaryForeground" to "#007AFF",
        "badge.background" to "#007AFF",
        "badge.foreground" to "#FFFFFF",
        "progressBar.background" to "#007AFF",
        "textLink.foreground" to "#007AFF",
        "textLink.activeForeground" to "#0062CC",
        "list.activeSelectionBackground" to "#D6E6FF",
        "list.activeSelectionForeground" to "#000000",
        "list.inactiveSelectionBackground" to "#E5E5EA",
        "list.hoverBackground" to "#EDEDF2",
        "list.focusOutline" to "#007AFF",
        "input.background" to "#FFFFFF",
        "input.border" to "#D1D1D6",
        "quickInput.background" to "#FFFFFF",
        "quickInputList.focusBackground" to "#D6E6FF",
        "menu.background" to "#FFFFFF",
        "menu.selectionBackground" to "#007AFF",
        "menu.selectionForeground" to "#FFFFFF",
        "menu.separatorBackground" to "#E5E5EA",
        "panel.background" to "#FFFFFF",
        "panel.border" to "#D1D1D6",
        "panelTitle.activeBorder" to "#007AFF",
        "editor.background" to "#FFFFFF",
        "editorLineNumber.foreground" to "#AEAEB2",
        "editorLineNumber.activeForeground" to "#3C3C43",
        "editorCursor.foreground" to "#007AFF",
        "editor.selectionBackground" to "#B3D7FF",
        "editor.lineHighlightBackground" to "#F2F2F7",
        "notifications.background" to "#FFFFFF",
        "widget.shadow" to "#0000001F",
        "scrollbarSlider.background" to "#3C3C4333",
        "scrollbarSlider.hoverBackground" to "#3C3C4355",
        "scrollbarSlider.activeBackground" to "#3C3C4377",
    )

    /** Scoped to the light theme so a dark theme the user picks keeps its own colours. */
    val colorCustomizations: Map<String, Any?> = linkedMapOf("[$LIGHT_THEME]" to iosLightPalette)

    data class Replacement(val key: String, val previous: Any?, val next: Any?)

    /** Applied only while the current value still equals [Replacement.previous]. */
    val replacements: List<Replacement> = listOf(
        // 滑らかなキャレット移動は入力のたびにアニメーションを走らせ、PRoot 越しの端末では重い。
        Replacement("editor.cursorSmoothCaretAnimation", "on", "off"),
        // スマホ幅ではミニマップの描画コストに見合う情報量がない。
        Replacement("editor.minimap.enabled", true, false),
        Replacement("workbench.colorCustomizations", legacyWarmPalette, colorCustomizations),
    )

    /** Added only when the key is absent. */
    val additions: List<Pair<String, Any?>> = listOf(
        "chat.disableAIFeatures" to true,
        "extensions.autoCheckUpdates" to false,
        "extensions.autoUpdate" to false,
        "extensions.ignoreRecommendations" to true,
        "workbench.enableExperiments" to false,
        "workbench.settings.enableNaturalLanguageSearch" to false,
        "search.followSymlinks" to false,
        "workbench.tips.enabled" to false,
    )

    /** Settings a fresh install starts with (equal to the old defaults after [migrate]). */
    fun defaults(): Map<String, Any?> = linkedMapOf(
        "workbench.startupEditor" to "none",
        "workbench.colorTheme" to LIGHT_THEME,
        "workbench.iconTheme" to "vs-seti",
        "workbench.commandPalette.experimental.suggestCommands" to true,
        "workbench.editor.showTabs" to "multiple",
        "workbench.editor.tabSizing" to "shrink",
        "workbench.list.smoothScrolling" to true,
        "workbench.tree.indent" to 16,
        "workbench.colorCustomizations" to colorCustomizations,
        "workbench.enableExperiments" to false,
        "workbench.settings.enableNaturalLanguageSearch" to false,
        "workbench.tips.enabled" to false,
        "window.commandCenter" to true,
        "window.menuBarVisibility" to "compact",
        "editor.fontSize" to 14,
        "editor.lineHeight" to 22,
        "editor.fontLigatures" to true,
        "editor.cursorSmoothCaretAnimation" to "off",
        "editor.cursorBlinking" to "smooth",
        "editor.smoothScrolling" to true,
        "editor.stickyScroll.enabled" to true,
        "editor.minimap.enabled" to false,
        "editor.wordWrap" to "on",
        "editor.multiCursorModifier" to "ctrlCmd",
        "terminal.integrated.fontSize" to 13,
        "terminal.integrated.cursorBlinking" to true,
        "terminal.integrated.smoothScrolling" to true,
        "terminal.integrated.defaultProfile.linux" to "bash",
        "files.autoSave" to "afterDelay",
        "files.autoSaveDelay" to 1_000,
        "files.trimTrailingWhitespace" to false,
        "search.useIgnoreFiles" to true,
        "search.followSymlinks" to false,
        "git.autofetch" to false,
        "git.confirmSync" to true,
        "extensions.autoCheckUpdates" to false,
        "extensions.autoUpdate" to false,
        "extensions.ignoreRecommendations" to true,
        "chat.disableAIFeatures" to true,
        "telemetry.telemetryLevel" to "off",
    )

    fun defaultSettingsText(): String = render(defaults(), "", "  ", "\n") + "\n"

    sealed interface Outcome {
        data class Updated(val text: String, val changedKeys: List<String>) : Outcome
        data object Unchanged : Outcome
        data class Skipped(val reason: String) : Outcome
    }

    fun migrate(text: String): Outcome {
        val document = try {
            parseDocument(text)
        } catch (_: CommentFound) {
            return Outcome.Skipped("コメントを含む設定ファイルは書き換えません")
        } catch (failure: ParseFailure) {
            return Outcome.Skipped("JSONとして読めません: ${failure.message}")
        }

        val newline = if ("\r\n" in text) "\r\n" else "\n"
        val firstIndent = document.members.firstOrNull()?.let { lineIndent(text, it.keyStart) }
        val unit = firstIndent?.takeIf { it.isNotEmpty() } ?: "  "
        val memberIndent = firstIndent ?: unit

        val edits = mutableListOf<Edit>()
        val changed = mutableListOf<String>()
        for (rule in replacements) {
            // 重複キーは Code - OSS と同じく最後の値が有効。
            val member = document.members.lastOrNull { it.key == rule.key } ?: continue
            if (!jsonEquals(member.value, rule.previous)) continue
            val indent = lineIndent(text, member.keyStart) ?: memberIndent
            edits += Edit(member.valueStart, member.valueEnd, render(rule.next, indent, unit, newline))
            changed += rule.key
        }

        val missing = additions.filter { (key, _) -> document.members.none { it.key == key } }
        if (missing.isNotEmpty()) {
            val last = document.members.lastOrNull()
            if (last == null) {
                // 空のオブジェクトは波括弧ごと描き直す。
                val body = LinkedHashMap<String, Any?>().apply { missing.forEach { put(it.first, it.second) } }
                edits += Edit(document.openIndex, document.closeIndex + 1, render(body, "", unit, newline))
            } else {
                val inserted = missing.joinToString("") { (key, value) ->
                    ",$newline$memberIndent${quote(key)}: ${render(value, memberIndent, unit, newline)}"
                }
                edits += Edit(last.valueEnd, last.valueEnd, inserted)
            }
            changed += missing.map { it.first }
        }

        if (edits.isEmpty()) return Outcome.Unchanged
        val result = StringBuilder(text)
        edits.sortedByDescending { it.start }.forEach { result.replace(it.start, it.end, it.text) }
        val migrated = result.toString()
        // 念のため書き込む前に結果を読み直す。壊れた JSON は決して書かない。
        try {
            parseDocument(migrated)
        } catch (failure: RuntimeException) {
            return Outcome.Skipped("移行結果を検証できません: ${failure.message}")
        }
        return Outcome.Updated(migrated, changed)
    }

    /** Structural JSON equality: object key order is ignored, numbers compare by value. */
    fun jsonEquals(a: Any?, b: Any?): Boolean = when {
        a is Number && b is Number ->
            BigDecimal(a.toString()).compareTo(BigDecimal(b.toString())) == 0
        a is Map<*, *> && b is Map<*, *> ->
            a.size == b.size && a.all { (key, value) -> b.containsKey(key) && jsonEquals(value, b[key]) }
        a is List<*> && b is List<*> ->
            a.size == b.size && a.indices.all { jsonEquals(a[it], b[it]) }
        else -> a == b
    }

    /** Parses a whole document; the top level must be an object. */
    fun parse(text: String): Map<String, Any?> = parseDocument(text).value

    fun render(value: Any?, indent: String, unit: String, newline: String): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean, is Number -> value.toString()
        is Map<*, *> -> if (value.isEmpty()) "{}" else value.entries.joinToString(
            separator = ",$newline",
            prefix = "{$newline",
            postfix = "$newline$indent}",
        ) { (key, item) ->
            "$indent$unit${quote(key.toString())}: ${render(item, indent + unit, unit, newline)}"
        }
        is List<*> -> if (value.isEmpty()) "[]" else value.joinToString(
            separator = ",$newline",
            prefix = "[$newline",
            postfix = "$newline$indent]",
        ) { item -> "$indent$unit${render(item, indent + unit, unit, newline)}" }
        else -> throw IllegalArgumentException("JSONにできない値です: ${value::class.java.name}")
    }

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c == '\b' -> append("\\b")
                c == '\u000C' -> append("\\f")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    /** Whitespace before [index] on its line, or null when other text precedes it. */
    private fun lineIndent(text: String, index: Int): String? {
        var start = index
        while (start > 0 && text[start - 1] != '\n') {
            val c = text[start - 1]
            if (c != ' ' && c != '\t') return null
            start--
        }
        return text.substring(start, index)
    }

    private data class Edit(val start: Int, val end: Int, val text: String)

    private data class Member(
        val key: String,
        val keyStart: Int,
        val valueStart: Int,
        val valueEnd: Int,
        val value: Any?,
    )

    private class Document(
        val value: Map<String, Any?>,
        val members: List<Member>,
        val openIndex: Int,
        val closeIndex: Int,
    )

    private class CommentFound : RuntimeException()

    private class ParseFailure(message: String) : RuntimeException(message)

    private fun parseDocument(text: String): Document {
        val reader = Reader(text)
        if (text.startsWith('﻿')) reader.pos = 1
        reader.skipWhitespace()
        if (reader.peek() != '{') reader.fail("トップレベルがオブジェクトではありません")
        val openIndex = reader.pos
        val members = mutableListOf<Member>()
        val value = reader.readObject(0, members)
        val closeIndex = reader.pos - 1
        reader.skipWhitespace()
        if (reader.pos != text.length) reader.fail("オブジェクトの後ろに余分な文字があります")
        return Document(value, members, openIndex, closeIndex)
    }

    /** Strict JSON reader that also accepts trailing commas, as Code - OSS does. */
    private class Reader(private val text: String) {
        var pos = 0

        fun fail(message: String): Nothing = throw ParseFailure("$message (offset $pos)")

        fun peek(): Char = if (pos < text.length) text[pos] else '\u0000'

        fun skipWhitespace() {
            while (pos < text.length) {
                when (text[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    '/' -> {
                        val next = text.getOrNull(pos + 1)
                        if (next == '/' || next == '*') throw CommentFound()
                        fail("予期しない '/'")
                    }
                    else -> return
                }
            }
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("入れ子が深すぎます")
            skipWhitespace()
            return when (val c = peek()) {
                '{' -> readObject(depth, null)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (c == '-' || c in '0'..'9') readNumber() else fail("予期しない文字")
            }
        }

        fun readObject(depth: Int, members: MutableList<Member>?): Map<String, Any?> {
            pos++ // '{'
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return result
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("プロパティ名がありません")
                val keyStart = pos
                val key = readString()
                skipWhitespace()
                if (peek() != ':') fail("':' がありません")
                pos++
                skipWhitespace()
                val valueStart = pos
                val value = readValue(depth + 1)
                // 重複キーは最後の値が有効(Code - OSS の jsonc パーサーと同じ)。
                result.remove(key)
                result[key] = value
                members?.add(Member(key, keyStart, valueStart, pos, value))
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        pos++
                        skipWhitespace()
                        if (peek() == '}') {
                            pos++
                            return result
                        }
                    }
                    '}' -> {
                        pos++
                        return result
                    }
                    else -> fail("',' か '}' がありません")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            pos++ // '['
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return result
            }
            while (true) {
                result += readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        pos++
                        skipWhitespace()
                        if (peek() == ']') {
                            pos++
                            return result
                        }
                    }
                    ']' -> {
                        pos++
                        return result
                    }
                    else -> fail("',' か ']' がありません")
                }
            }
        }

        private fun readString(): String {
            pos++ // '"'
            val result = StringBuilder()
            while (true) {
                if (pos >= text.length) fail("文字列が閉じていません")
                val c = text[pos++]
                when {
                    c == '"' -> return result.toString()
                    c == '\\' -> {
                        if (pos >= text.length) fail("文字列が閉じていません")
                        when (text[pos++]) {
                            '"' -> result.append('"')
                            '\\' -> result.append('\\')
                            '/' -> result.append('/')
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                val hex = text.substring(pos, (pos + 4).coerceAtMost(text.length))
                                if (hex.length != 4 || !hex.all { it.isHexDigitChar() }) {
                                    fail("不正な \\u エスケープ")
                                }
                                result.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> fail("不正なエスケープ")
                        }
                    }
                    c < ' ' -> fail("文字列に制御文字があります")
                    else -> result.append(c)
                }
            }
        }

        private fun readNumber(): BigDecimal {
            val start = pos
            if (peek() == '-') pos++
            when {
                peek() == '0' -> pos++
                peek() in '1'..'9' -> while (peek() in '0'..'9') pos++
                else -> fail("不正な数値")
            }
            if (peek() == '.') {
                pos++
                if (peek() !in '0'..'9') fail("不正な数値")
                while (peek() in '0'..'9') pos++
            }
            if (peek() == 'e' || peek() == 'E') {
                pos++
                if (peek() == '+' || peek() == '-') pos++
                if (peek() !in '0'..'9') fail("不正な数値")
                while (peek() in '0'..'9') pos++
            }
            return BigDecimal(text.substring(start, pos))
        }

        private fun readLiteral(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) fail("不正なリテラル")
            pos += word.length
            return value
        }

        private fun Char.isHexDigitChar(): Boolean =
            this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}
