package io.github.hatake716.omochi

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.util.Base64
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
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
    private val authLock = Any()

    data class LaunchSpec(
        val executable: String,
        val cwd: String,
        val args: Array<String>,
        val env: Array<String>,
        val title: String
    )

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
                val testResult = runGuestCommandSync(
                    context = appContext,
                    command = "${serverGuestBinary()} --version",
                    timeoutSeconds = 60
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
        val password = authPassword(context)

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
            "/workspace"
        )

        val proot = ensureHostRuntime(context).getOrThrow()
        LaunchSpec(
            executable = proot.absolutePath,
            cwd = runtimeDir(context).absolutePath,
            args = args.toTypedArray(),
            env = hostEnvironment(context, rootfs, verbose = false)
                .map { "${it.key}=${it.value}" }
                .toTypedArray(),
            title = "Omochi — /workspace"
        )
    }

    fun serverUrl(port: Int): String {
        require(port in 1024..65535) { "Invalid loopback port: $port" }
        return "http://127.0.0.1:$port/?folder=/workspace"
    }

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
        val claudeTest = runGuestCommandSync(
            context = context,
            command = "claude --version",
            timeoutSeconds = 120,
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
        val rootfs = rootfsDir(context, DEFAULT_CONTAINER)
        val userDir = File(rootfs, "root/.local/share/omochi/User").apply { mkdirs() }
        val colors = JSONObject()
            .put("titleBar.activeBackground", "#E9E4DF")
            .put("titleBar.activeForeground", "#24262B")
            .put("activityBar.background", "#EEEAE6")
            .put("activityBar.foreground", "#24262B")
            .put("activityBar.inactiveForeground", "#777A82")
            .put("sideBar.background", "#F4F1ED")
            .put("sideBar.foreground", "#2F3035")
            .put("sideBar.border", "#D5CFC9")
            .put("editorGroupHeader.tabsBackground", "#ECE8E3")
            .put("tab.activeBackground", "#FFFFFF")
            .put("tab.inactiveBackground", "#ECE8E3")
            .put("tab.border", "#D5CFC9")
            .put("statusBar.background", "#C96954")
            .put("statusBar.foreground", "#FFFFFF")
            .put("focusBorder", "#C96954")
            .put("button.background", "#C96954")
            .put("button.hoverBackground", "#B85B48")
            .put("list.activeSelectionBackground", "#D9CFCA")
            .put("list.hoverBackground", "#E8E2DD")

        val settings = JSONObject()
            .put("workbench.startupEditor", "none")
            .put("workbench.colorTheme", "Default Light Modern")
            .put("workbench.iconTheme", "vs-seti")
            .put("workbench.commandPalette.experimental.suggestCommands", true)
            .put("workbench.editor.showTabs", "multiple")
            .put("workbench.editor.tabSizing", "shrink")
            .put("workbench.list.smoothScrolling", true)
            .put("workbench.tree.indent", 16)
            .put("workbench.colorCustomizations", colors)
            .put("window.commandCenter", true)
            .put("window.menuBarVisibility", "compact")
            .put("editor.fontSize", 14)
            .put("editor.lineHeight", 22)
            .put("editor.fontLigatures", true)
            .put("editor.cursorSmoothCaretAnimation", "on")
            .put("editor.cursorBlinking", "smooth")
            .put("editor.smoothScrolling", true)
            .put("editor.stickyScroll.enabled", true)
            .put("editor.minimap.enabled", true)
            .put("editor.wordWrap", "on")
            .put("editor.multiCursorModifier", "ctrlCmd")
            .put("terminal.integrated.fontSize", 13)
            .put("terminal.integrated.cursorBlinking", true)
            .put("terminal.integrated.smoothScrolling", true)
            .put("terminal.integrated.defaultProfile.linux", "bash")
            .put("files.autoSave", "afterDelay")
            .put("files.autoSaveDelay", 1_000)
            .put("files.trimTrailingWhitespace", false)
            .put("search.useIgnoreFiles", true)
            .put("git.autofetch", false)
            .put("git.confirmSync", true)
            .put("extensions.autoCheckUpdates", false)
            .put("extensions.autoUpdate", false)
            .put("telemetry.telemetryLevel", "off")
        File(userDir, "settings.json").writeText(settings.toString(2) + "\n")

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
        onLine: (String) -> Unit = {}
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
            putAll(hostEnvironment(context, rootfs, verbose = false))
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
            verbose = false
        )
        check(result.exitCode == 0) {
            "Android版PRoot本体を実行できません (exit=${result.exitCode}): ${result.output.ifBlank { "no output" }}"
        }
        result.output.lineSequence().firstOrNull().orEmpty().ifBlank { "proot executable OK" }
    }

    fun selfTestContainer(context: Context, name: String): Result<String> = runCatching {
        ensureHostRuntime(context).getOrThrow()
        val rootfs = rootfsDir(context, name)
        check(File(rootfs, "bin/sh").isFile) {
            "Linux rootfsに /bin/sh がありません。展開に失敗しています。"
        }

        val result = runHostProcess(
            context = context,
            command = baseProotArgs(context, rootfs) + listOf(
                "/bin/sh", "-c", "printf 'embedded-runtime-ok'"
            ),
            rootfs = rootfs,
            timeoutSeconds = 25,
            verbose = true
        )
        check(result.exitCode == 0) {
            "PRootセルフテスト失敗 (exit=${result.exitCode}): ${result.output.ifBlank { "no output" }}"
        }
        check(result.output.contains("embedded-runtime-ok")) {
            "Linuxセルフテストの応答が不正です: ${result.output.ifBlank { "no output" }}"
        }
        "embedded-runtime-ok"
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

        LaunchSpec(
            executable = proot.absolutePath,
            cwd = runtimeDir(context).absolutePath,
            args = args.toTypedArray(),
            env = hostEnvironment(context, rootfs, verbose = false)
                .map { "${it.key}=${it.value}" }
                .toTypedArray(),
            title = when (mode) {
                LaunchMode.SHELL -> "$container — Linux"
                LaunchMode.COMMAND -> "$container — Task"
            }
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
        verbose: Boolean
    ): ProcessResult {
        val builder = ProcessBuilder(command)
            .directory(runtimeDir(context))
            .redirectErrorStream(true)
        builder.environment().apply {
            clear()
            putAll(hostEnvironment(context, rootfs, verbose))
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
        verbose: Boolean
    ): MutableMap<String, String> = linkedMapOf<String, String>().apply {
        put("HOME", context.filesDir.canonical())
        put("TMPDIR", tempDir(context).canonical())
        put("PROOT_TMP_DIR", tempDir(context).canonical())
        put("PROOT_LOADER", prootLoader(context).canonical())
        put("PROOT_NO_SECCOMP", "1")
        put("LD_LIBRARY_PATH", nativeDir(context).canonical())
        put("PATH", "/system/bin:/system/xbin")
        put("ANDROID_DATA", "/data")
        put("ANDROID_ROOT", "/system")
        put("TERM", "xterm-256color")
        if (rootfs != null) {
            val l2s = File(rootfs, ".l2s").apply { mkdirs() }
            // canonicalPath 必須: PRoot は --rootfs を realpath で正規化する一方、
            // PROOT_L2S_DIR は文字列をそのまま使うため、綴りを揃えないと
            // link2symlink の疑似ハードリンクが解決不能になる(canonical() の説明参照)。
            put("PROOT_L2S_DIR", l2s.canonical())
        }
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
