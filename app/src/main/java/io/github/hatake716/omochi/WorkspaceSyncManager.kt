package io.github.hatake716.omochi

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Persisted SAF tree access and near-real-time bidirectional workspace mirroring.
 *
 * A SAF tree URI cannot be opened as a regular Linux path by PRoot. Omochi therefore edits a
 * private mirror under `/workspace/phone/<folder>` and copies changes through ContentResolver.
 * Local FileObserver events are debounced, provider ContentObserver events request a remote
 * verification, and a short periodic scan covers providers that do not emit notifications.
 */
object WorkspaceSyncManager {
    const val TREE_PERMISSION_FLAGS: Int =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    data class Link(
        val treeUri: Uri,
        val label: String,
        val localRelativePath: String,
    ) {
        val workspacePath: String get() = "/workspace/$localRelativePath"
    }

    data class Summary(
        val imported: Int,
        val exported: Int,
        val deletedLocal: Int,
        val deletedRemote: Int,
        val conflicts: Int,
        val bytesCopied: Long,
        val excluded: Int,
        val recoveryPath: String?,
    ) {
        val changed: Int get() = imported + exported + deletedLocal + deletedRemote + conflicts
    }

    sealed interface State {
        data object Disconnected : State

        data class Ready(
            val link: Link,
            val lastSyncedAt: Long,
            val summary: Summary? = null,
        ) : State

        data class Syncing(
            val link: Link?,
            val message: String,
            val filesCopied: Int = 0,
        ) : State

        data class Failed(
            val link: Link?,
            val message: String,
        ) : State
    }

    private data class RawEntry(
        val kind: WorkspaceSyncPolicy.Kind,
        val size: Long,
        val modified: Long,
        val localFile: File? = null,
        val document: DocumentFile? = null,
    )

    private data class Snapshot(
        val entries: Map<String, RawEntry>,
        val excluded: Int,
    )

    private data class SideMetadata(
        val kind: WorkspaceSyncPolicy.Kind,
        val size: Long,
        val modified: Long,
    )

    private data class BaselineRecord(
        val entry: WorkspaceSyncPolicy.Entry,
        val local: SideMetadata,
        val remote: SideMetadata,
    )

    private data class SyncRequest(
        val forceLocal: Boolean,
        val forceRemote: Boolean,
        val localPaths: Set<String>,
        val announce: Boolean,
    )

    private class Counters {
        var imported = 0
        var exported = 0
        var deletedLocal = 0
        var deletedRemote = 0
        var conflicts = 0
        var bytes = 0L
        var excluded = 0
        var recoveryPath: String? = null
        var lastProgressAt = 0L

        fun summary() = Summary(
            imported = imported,
            exported = exported,
            deletedLocal = deletedLocal,
            deletedRemote = deletedRemote,
            conflicts = conflicts,
            bytesCopied = bytes,
            excluded = excluded,
            recoveryPath = recoveryPath,
        )
    }

    private class Recovery(private val context: Context, private val link: Link) {
        private var batchRoot: File? = null

        fun destination(side: String, relativePath: String): File {
            val root = batchRoot ?: createRoot().also { batchRoot = it }
            return File(File(root, side), relativePath).also { target ->
                checkContained(root, target)
                target.parentFile?.mkdirs()
            }
        }

        fun workspacePath(): String? = batchRoot?.let { root ->
            val workspace = OmochiRuntime.workspaceDir(context).absoluteFile
            "/workspace/${root.canonicalFile.relativeTo(workspace).invariantSeparatorsPath}"
        }

        private fun createRoot(): File {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            val linkName = WorkspaceTransfer.safeName(link.localRelativePath.substringAfterLast('/'))
            val workspace = OmochiRuntime.workspaceDir(context).canonicalFile
            val base = File(workspace, "Omochi-Recovery/$linkName")
            ensureNoSymlinkPath(workspace, base)
            for (index in 1..1_000) {
                val name = if (index == 1) stamp else "$stamp-$index"
                val candidate = File(base, name)
                if (candidate.mkdirs()) {
                    ensureNoSymlinkPath(workspace, candidate)
                    return candidate
                }
            }
            throw IOException("同期用の回復フォルダを作成できません。")
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val loadLock = Any()
    private val queueLock = Any()
    private val observerLock = Any()

    @Volatile private var loaded = false
    @Volatile private var currentState: State = State.Disconnected
    @Volatile private var activeLink: Link? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var runtimeActive = false
    @Volatile private var lastFullRemoteVerification = 0L

    private var executor: ScheduledExecutorService? = null
    private var periodicTask: ScheduledFuture<*>? = null
    private var contentObserver: ContentObserver? = null
    private var fileObserver: RecursiveWorkspaceObserver? = null

    private var requestPending = false
    private var workerScheduled = false
    private var pendingForceLocal = false
    private var pendingForceRemote = false
    private var pendingAnnouncement = false
    private val pendingLocalPaths = linkedSetOf<String>()

    fun state(context: Context): State {
        ensureLoaded(context.applicationContext)
        return currentState
    }

    fun addListener(listener: (State) -> Unit) {
        listeners += listener
        main.post { listener(currentState) }
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners -= listener
    }

    /** Starts automatic synchronization while the user-started IDE foreground service is alive. */
    fun start(context: Context) {
        val app = context.applicationContext
        ensureLoaded(app)
        appContext = app
        runtimeActive = true
        synchronized(queueLock) {
            if (periodicTask?.isCancelled != false) {
                periodicTask = executor().scheduleWithFixedDelay(
                    {
                        val now = System.currentTimeMillis()
                        val forceRemote = now - lastFullRemoteVerification >= FULL_VERIFY_INTERVAL_MS
                        enqueue(app, forceLocal = false, forceRemote = forceRemote, delayMs = 0L)
                    },
                    1L,
                    PERIODIC_SCAN_SECONDS,
                    TimeUnit.SECONDS,
                )
            }
        }
        activeLink?.let { refreshObservers(app, it) }
        enqueue(app, forceLocal = false, forceRemote = true, delayMs = 0L)
    }

    /** Stops observers and the periodic timer, then requests one final loss-averse flush. */
    fun stop() {
        runtimeActive = false
        periodicTask?.cancel(false)
        periodicTask = null
        clearObservers()
        appContext?.let { app ->
            if (activeLink != null) {
                enqueue(app, forceLocal = true, forceRemote = true, delayMs = 0L)
            }
        }
    }

    /**
     * Persists read/write access and creates or reuses a dedicated mirror. Existing mirrors are
     * never deleted when a link is replaced.
     */
    fun connect(context: Context, treeUri: Uri) {
        val app = context.applicationContext
        ensureLoaded(app)
        appContext = app
        publish(State.Syncing(activeLink, "端末フォルダの読み書き権限を確認しています…"))
        executor().execute {
            val previous = activeLink
            var switchedLink = false
            runCatching {
                app.contentResolver.takePersistableUriPermission(treeUri, TREE_PERMISSION_FLAGS)
                check(hasReadWriteAccess(app, treeUri)) {
                    "このフォルダの永続的な読み書き権限を取得できません。"
                }
                val root = requireRemoteRoot(app, treeUri)
                val label = root.name?.takeIf { it.isNotBlank() } ?: "端末フォルダ"
                val relative = if (previous?.treeUri == treeUri) {
                    previous.localRelativePath
                } else {
                    chooseLocalRelativePath(app, label)
                }
                val link = Link(treeUri, label, relative)
                localRoot(app, link).mkdirs()

                activeLink = link
                saveLink(app, link)
                switchedLink = true
                clearObservers()
                val summary = performSync(
                    context = app,
                    link = link,
                    request = SyncRequest(
                        forceLocal = true,
                        forceRemote = true,
                        localPaths = emptySet(),
                        announce = true,
                    ),
                )
                summary
            }.also {
                if (switchedLink && previous != null && previous.treeUri != treeUri) {
                    releaseAccess(app, previous.treeUri)
                }
            }.onFailure { error ->
                publish(State.Failed(activeLink ?: previous, readableError(error)))
            }
        }
    }

    /** Releases the persisted SAF permission but deliberately leaves the private mirror intact. */
    fun disconnect(context: Context) {
        val app = context.applicationContext
        ensureLoaded(app)
        executor().execute {
            val previous = activeLink
            clearObservers()
            activeLink = null
            app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit(commit = true) { clear() }
            runCatching { baselineFile(app).delete() }
            previous?.let { releaseAccess(app, it.treeUri) }
            publish(State.Disconnected)
        }
    }

    fun requestSync(context: Context, verifyBothSides: Boolean = true) {
        val app = context.applicationContext
        ensureLoaded(app)
        appContext = app
        if (activeLink == null) return
        enqueue(
            context = app,
            forceLocal = verifyBothSides,
            forceRemote = verifyBothSides,
            announce = true,
            delayMs = 0L,
        )
    }

    fun hasReadWriteAccess(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission && permission.isWritePermission
        }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            appContext = context
            val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val uri = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)
            val label = prefs.getString(KEY_LABEL, null)
            val relative = prefs.getString(KEY_LOCAL_RELATIVE, null)
            val restored = if (uri != null && !label.isNullOrBlank() && isSafeLocalRelative(relative)) {
                Link(uri, label, relative!!)
            } else {
                null
            }
            activeLink = restored
            currentState = if (restored == null) {
                State.Disconnected
            } else if (!hasReadWriteAccess(context, restored.treeUri)) {
                State.Failed(restored, "端末フォルダの権限が失われました。フォルダを選び直してください。")
            } else {
                State.Ready(
                    link = restored,
                    lastSyncedAt = prefs.getLong(KEY_LAST_SYNC, 0L),
                )
            }
            loaded = true
        }
    }

    private fun saveLink(context: Context, link: Link) {
        val saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(KEY_TREE_URI, link.treeUri.toString())
            .putString(KEY_LABEL, link.label)
            .putString(KEY_LOCAL_RELATIVE, link.localRelativePath)
            .commit()
        check(saved) { "端末フォルダの連携設定を保存できません。" }
    }

    private fun enqueue(
        context: Context,
        forceLocal: Boolean,
        forceRemote: Boolean,
        localPath: String? = null,
        announce: Boolean = false,
        delayMs: Long = LOCAL_DEBOUNCE_MS,
    ) {
        if (activeLink == null) return
        synchronized(queueLock) {
            requestPending = true
            pendingForceLocal = pendingForceLocal || forceLocal
            pendingForceRemote = pendingForceRemote || forceRemote
            pendingAnnouncement = pendingAnnouncement || announce
            localPath?.let { pendingLocalPaths += it }
            if (workerScheduled) return
            workerScheduled = true
            executor().schedule({ drainQueue(context) }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun drainQueue(context: Context) {
        while (true) {
            val request = synchronized(queueLock) {
                if (!requestPending) {
                    workerScheduled = false
                    return
                }
                SyncRequest(
                    forceLocal = pendingForceLocal,
                    forceRemote = pendingForceRemote,
                    localPaths = pendingLocalPaths.toSet(),
                    announce = pendingAnnouncement,
                ).also {
                    requestPending = false
                    pendingForceLocal = false
                    pendingForceRemote = false
                    pendingAnnouncement = false
                    pendingLocalPaths.clear()
                }
            }
            val link = activeLink ?: continue
            runCatching { performSync(context, link, request) }
                .onFailure { error -> publish(State.Failed(link, readableError(error))) }
        }
    }

    private fun performSync(context: Context, link: Link, request: SyncRequest): Summary {
        check(activeLink == link) { "端末フォルダの連携先が変更されました。" }
        check(hasReadWriteAccess(context, link.treeUri)) {
            "端末フォルダの読み書き権限がありません。フォルダを選び直してください。"
        }
        val remoteRoot = requireRemoteRoot(context, link.treeUri)
        val localRoot = localRoot(context, link).apply { mkdirs() }
        if (request.announce) {
            publish(State.Syncing(link, "${link.label} の変更を確認しています…"))
        }

        val baseline = loadBaseline(context, link)
        val localSnapshot = scanLocal(localRoot)
        val remoteSnapshot = scanRemote(remoteRoot)
        val localResolved = resolveEntries(
            context,
            localSnapshot.entries,
            baseline,
            Side.Local,
            forceAll = request.forceLocal,
            forcePaths = request.localPaths,
        )
        val remoteResolved = resolveEntries(
            context,
            remoteSnapshot.entries,
            baseline,
            Side.Remote,
            forceAll = request.forceRemote,
            forcePaths = emptySet(),
        )
        val canonicalBaseline = baseline.mapValues { it.value.entry }
        val actions = WorkspaceSyncPolicy.plan(canonicalBaseline, localResolved, remoteResolved)
        val counters = Counters().apply {
            excluded = maxOf(localSnapshot.excluded, remoteSnapshot.excluded)
        }
        if (actions.isEmpty()) {
            check(localResolved == remoteResolved) {
                "同期対象の内容が一致しません。内容を保ったまま再同期します。"
            }
            val unchanged = canonicalBaseline == localResolved && baselineMetadataMatches(
                baseline,
                localSnapshot.entries,
                remoteSnapshot.entries,
            )
            if (!unchanged) {
                saveBaseline(
                    context,
                    link,
                    buildBaseline(localResolved, localSnapshot.entries, remoteSnapshot.entries),
                )
            }
            val summary = counters.summary()
            if (request.announce || !unchanged || currentState is State.Failed) {
                val now = System.currentTimeMillis()
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
                    putLong(KEY_LAST_SYNC, now)
                }
                if (request.forceRemote) lastFullRemoteVerification = now
                publish(State.Ready(link, now, summary))
                if (runtimeActive) refreshObservers(context, link)
            } else if (request.forceRemote) {
                lastFullRemoteVerification = System.currentTimeMillis()
            }
            return summary
        }
        val recovery = Recovery(context, link)
        val affected = linkedSetOf<String>()

        actions.forEach { action ->
            check(activeLink == link) { "同期中に端末フォルダの連携先が変更されました。" }
            publishProgress(link, action.path, counters)
            executeAction(
                context = context,
                link = link,
                remoteRoot = remoteRoot,
                action = action,
                expectedLocal = localResolved,
                expectedRemote = remoteResolved,
                counters = counters,
                recovery = recovery,
            )
            affected += action.path
        }

        var records: Map<String, BaselineRecord>? = null
        for (attempt in 1..FINAL_VERIFY_ATTEMPTS) {
            val finalLocalRaw = scanLocal(localRoot)
            val finalRemoteRaw = scanRemote(remoteRoot)
            val finalLocal = resolveEntries(
                context,
                finalLocalRaw.entries,
                baseline,
                Side.Local,
                forceAll = false,
                forcePaths = affected,
            )
            val finalRemote = resolveEntries(
                context,
                finalRemoteRaw.entries,
                baseline,
                Side.Remote,
                forceAll = false,
                forcePaths = affected,
            )
            if (finalLocal == finalRemote) {
                records = buildBaseline(finalLocal, finalLocalRaw.entries, finalRemoteRaw.entries)
                break
            }
            if (attempt < FINAL_VERIFY_ATTEMPTS) Thread.sleep(FINAL_VERIFY_DELAY_MS)
        }
        check(records != null) {
            "同期中に別の変更を検出しました。内容を保ったまま再同期します。"
        }

        saveBaseline(context, link, records)
        val now = System.currentTimeMillis()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            putLong(KEY_LAST_SYNC, now)
        }
        if (request.forceRemote) lastFullRemoteVerification = now
        counters.recoveryPath = recovery.workspacePath()
        val summary = counters.summary()
        publish(State.Ready(link, now, summary))
        if (runtimeActive) refreshObservers(context, link)
        return summary
    }

    private fun executeAction(
        context: Context,
        link: Link,
        remoteRoot: DocumentFile,
        action: WorkspaceSyncPolicy.Action,
        expectedLocal: Map<String, WorkspaceSyncPolicy.Entry>,
        expectedRemote: Map<String, WorkspaceSyncPolicy.Entry>,
        counters: Counters,
        recovery: Recovery,
    ) {
        val localRoot = localRoot(context, link)
        when (action) {
            is WorkspaceSyncPolicy.Action.Upload -> {
                val source = localEntry(localRoot, action.path)
                verifyRemote(context, findDocument(remoteRoot, action.path), expectedRemote[action.path])
                uploadPath(context, remoteRoot, source, action.path, counters, recovery)
            }

            is WorkspaceSyncPolicy.Action.Download -> {
                val source = findDocument(remoteRoot, action.path)
                    ?: throw IOException("端末側の項目が見つかりません: ${action.path}")
                verifyLocal(localEntry(localRoot, action.path), expectedLocal[action.path])
                downloadPath(context, source, localRoot, action.path, counters, recovery)
            }

            is WorkspaceSyncPolicy.Action.DeleteRemote -> {
                val target = findDocument(remoteRoot, action.path)
                    ?: throw IOException("削除する端末側の項目が見つかりません: ${action.path}")
                assertNoProtectedRemoteMetadata(target, action.path)
                backupRemote(context, target, recovery.destination("device", action.path))
                deleteDocumentRecursively(target)
                counters.deletedRemote += 1
            }

            is WorkspaceSyncPolicy.Action.DeleteLocal -> {
                val target = localEntry(localRoot, action.path)
                assertNoProtectedLocalMetadata(target, action.path)
                backupLocal(target, recovery.destination("local", action.path))
                check(target.deleteRecursively()) { "ローカル項目を削除できません: ${action.path}" }
                counters.deletedLocal += 1
            }

            is WorkspaceSyncPolicy.Action.PreserveConflict -> {
                val localSource = localEntry(localRoot, action.path)
                val remoteSource = findDocument(remoteRoot, action.path)
                    ?: throw IOException("競合した端末側の項目が見つかりません: ${action.path}")
                verifyLocal(localSource, expectedLocal[action.path])
                verifyRemote(context, remoteSource, expectedRemote[action.path])
                if (localSource.isFile && remoteSource.isDirectory) {
                    assertNoProtectedRemoteMetadata(remoteSource, action.path)
                }
                val conflictPath = uniqueConflictPath(remoteRoot, localRoot, action.path)
                downloadPath(context, remoteSource, localRoot, conflictPath, counters, recovery)
                uploadPath(
                    context,
                    remoteRoot,
                    localEntry(localRoot, conflictPath),
                    conflictPath,
                    counters,
                    recovery,
                )
                uploadPath(context, remoteRoot, localSource, action.path, counters, recovery)
                counters.conflicts += 1
            }
        }
    }

    private fun scanLocal(root: File): Snapshot {
        val entries = linkedMapOf<String, RawEntry>()
        var excluded = 0
        val canonicalRoot = root.canonicalFile

        fun visit(directory: File, prefix: String, depth: Int) {
            check(depth <= MAX_DEPTH) { "同期フォルダの階層が深すぎます。" }
            directory.listFiles().orEmpty().sortedBy { it.name.lowercase(Locale.ROOT) }.forEach { child ->
                if (isExcludedName(child.name)) {
                    excluded += 1
                    return@forEach
                }
                validateSegment(child.name)
                if (Files.isSymbolicLink(child.toPath())) {
                    throw IOException("シンボリックリンクは端末フォルダへ同期できません: ${child.name}")
                }
                checkContained(canonicalRoot, child)
                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                check(entries.size < MAX_ENTRIES) { "同期対象が多すぎます（上限 $MAX_ENTRIES 項目）。" }
                when {
                    child.isDirectory -> {
                        entries[path] = RawEntry(
                            kind = WorkspaceSyncPolicy.Kind.Directory,
                            size = 0L,
                            modified = child.lastModified(),
                            localFile = child,
                        )
                        visit(child, path, depth + 1)
                    }
                    child.isFile -> entries[path] = RawEntry(
                        kind = WorkspaceSyncPolicy.Kind.File,
                        size = child.length(),
                        modified = child.lastModified(),
                        localFile = child,
                    )
                }
            }
        }

        visit(canonicalRoot, "", 0)
        return Snapshot(entries, excluded)
    }

    private fun scanRemote(root: DocumentFile): Snapshot {
        val entries = linkedMapOf<String, RawEntry>()
        var excluded = 0

        fun visit(directory: DocumentFile, prefix: String, depth: Int) {
            check(depth <= MAX_DEPTH) { "端末フォルダの階層が深すぎます。" }
            val names = hashSetOf<String>()
            directory.listFiles()
                .sortedBy { it.name.orEmpty().lowercase(Locale.ROOT) }
                .forEach { child ->
                    val name = child.name ?: throw IOException("名前のない端末ファイルは同期できません。")
                    if (isExcludedName(name)) {
                        excluded += 1
                        return@forEach
                    }
                    validateSegment(name)
                    check(names.add(name)) { "端末フォルダに同名項目があります: $name" }
                    val path = if (prefix.isEmpty()) name else "$prefix/$name"
                    check(entries.size < MAX_ENTRIES) { "同期対象が多すぎます（上限 $MAX_ENTRIES 項目）。" }
                    when {
                        child.isDirectory -> {
                            entries[path] = RawEntry(
                                kind = WorkspaceSyncPolicy.Kind.Directory,
                                size = 0L,
                                modified = child.lastModified(),
                                document = child,
                            )
                            visit(child, path, depth + 1)
                        }
                        child.isFile -> entries[path] = RawEntry(
                            kind = WorkspaceSyncPolicy.Kind.File,
                            size = child.length(),
                            modified = child.lastModified(),
                            document = child,
                        )
                    }
                }
        }

        visit(root, "", 0)
        return Snapshot(entries, excluded)
    }

    private enum class Side { Local, Remote }

    private fun resolveEntries(
        context: Context,
        raw: Map<String, RawEntry>,
        baseline: Map<String, BaselineRecord>,
        side: Side,
        forceAll: Boolean,
        forcePaths: Set<String>,
    ): Map<String, WorkspaceSyncPolicy.Entry> = raw.mapValues { (path, value) ->
        if (value.kind == WorkspaceSyncPolicy.Kind.Directory) {
            WorkspaceSyncPolicy.Entry(WorkspaceSyncPolicy.Kind.Directory)
        } else {
            val previous = baseline[path]
            val previousMetadata = when (side) {
                Side.Local -> previous?.local
                Side.Remote -> previous?.remote
            }
            val metadata = value.metadata()
            val forced = forceAll || forcePaths.any { forcedPath ->
                forcedPath.isEmpty() || path == forcedPath || path.startsWith("$forcedPath/")
            }
            val digest = if (!forced && previous?.entry?.kind == WorkspaceSyncPolicy.Kind.File &&
                previousMetadata == metadata
            ) {
                previous.entry.digest!!
            } else {
                digest(openInput(context, value))
            }
            WorkspaceSyncPolicy.Entry(WorkspaceSyncPolicy.Kind.File, digest)
        }
    }

    private fun buildBaseline(
        canonical: Map<String, WorkspaceSyncPolicy.Entry>,
        local: Map<String, RawEntry>,
        remote: Map<String, RawEntry>,
    ): Map<String, BaselineRecord> = canonical.mapValues { (path, entry) ->
        val localEntry = local[path] ?: error("ローカル同期状態が不足しています: $path")
        val remoteEntry = remote[path] ?: error("端末同期状態が不足しています: $path")
        BaselineRecord(entry, localEntry.metadata(), remoteEntry.metadata())
    }

    private fun baselineMetadataMatches(
        baseline: Map<String, BaselineRecord>,
        local: Map<String, RawEntry>,
        remote: Map<String, RawEntry>,
    ): Boolean {
        if (baseline.keys != local.keys || baseline.keys != remote.keys) return false
        return baseline.all { (path, record) ->
            local[path]?.metadata() == record.local && remote[path]?.metadata() == record.remote
        }
    }

    private fun loadBaseline(context: Context, link: Link): Map<String, BaselineRecord> = runCatching {
        val file = baselineFile(context)
        if (!file.isFile) return@runCatching emptyMap()
        val root = JSONObject(file.readText(Charsets.UTF_8))
        if (root.optInt("version") != BASELINE_VERSION ||
            root.optString("treeUri") != link.treeUri.toString() ||
            root.optString("localRelativePath") != link.localRelativePath
        ) {
            return@runCatching emptyMap()
        }
        val records = linkedMapOf<String, BaselineRecord>()
        val array = root.getJSONArray("entries")
        check(array.length() <= MAX_ENTRIES) { "保存済み同期状態が大きすぎます。" }
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val path = item.getString("path")
            validateRelativePath(path)
            val kind = when (item.getString("kind")) {
                "file" -> WorkspaceSyncPolicy.Kind.File
                "directory" -> WorkspaceSyncPolicy.Kind.Directory
                else -> error("Unknown baseline kind")
            }
            val digest = item.optString("digest").takeIf { it.isNotBlank() }
            val entry = WorkspaceSyncPolicy.Entry(kind, digest)
            records[path] = BaselineRecord(
                entry = entry,
                local = SideMetadata(
                    kind,
                    item.getLong("localSize"),
                    item.getLong("localModified"),
                ),
                remote = SideMetadata(
                    kind,
                    item.getLong("remoteSize"),
                    item.getLong("remoteModified"),
                ),
            )
        }
        records
    }.getOrElse { emptyMap() }

    private fun saveBaseline(
        context: Context,
        link: Link,
        records: Map<String, BaselineRecord>,
    ) {
        val array = JSONArray()
        records.toSortedMap().forEach { (path, record) ->
            array.put(
                JSONObject()
                    .put("path", path)
                    .put("kind", if (record.entry.kind == WorkspaceSyncPolicy.Kind.File) "file" else "directory")
                    .put("digest", record.entry.digest ?: "")
                    .put("localSize", record.local.size)
                    .put("localModified", record.local.modified)
                    .put("remoteSize", record.remote.size)
                    .put("remoteModified", record.remote.modified)
            )
        }
        val root = JSONObject()
            .put("version", BASELINE_VERSION)
            .put("treeUri", link.treeUri.toString())
            .put("localRelativePath", link.localRelativePath)
            .put("entries", array)
        val destination = baselineFile(context)
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.writeText(root.toString(), Charsets.UTF_8)
        moveReplacing(temporary, destination)
    }

    private fun uploadPath(
        context: Context,
        remoteRoot: DocumentFile,
        source: File,
        path: String,
        counters: Counters,
        recovery: Recovery,
    ) {
        check(!Files.isSymbolicLink(source.toPath())) { "シンボリックリンクは同期できません: $path" }
        val parent = ensureRemoteParent(remoteRoot, path.substringBeforeLast('/', ""))
        uploadEntry(context, source, parent, path.substringAfterLast('/'), path, counters, recovery)
    }

    private fun uploadEntry(
        context: Context,
        source: File,
        remoteParent: DocumentFile,
        name: String,
        path: String,
        counters: Counters,
        recovery: Recovery,
    ) {
        val existing = remoteParent.findFile(name)
        if (source.isDirectory) {
            val directory = when {
                existing == null -> remoteParent.createDirectory(name)
                existing.isDirectory -> existing
                else -> {
                    backupRemote(context, existing, recovery.destination("device", path))
                    deleteDocumentRecursively(existing)
                    remoteParent.createDirectory(name)
                }
            } ?: throw IOException("端末側にフォルダを作成できません: $path")
            source.listFiles().orEmpty().sortedBy { it.name.lowercase(Locale.ROOT) }.forEach { child ->
                if (!isExcludedName(child.name)) {
                    uploadEntry(context, child, directory, child.name, "$path/${child.name}", counters, recovery)
                }
            }
            return
        }

        val target = when {
            existing == null -> createRemoteFile(remoteParent, name)
            existing.isFile -> existing
            else -> {
                assertNoProtectedRemoteMetadata(existing, path)
                backupRemote(context, existing, recovery.destination("device", path))
                deleteDocumentRecursively(existing)
                createRemoteFile(remoteParent, name)
            }
        }
        openRemoteOutput(context, target).use { output ->
            source.inputStream().buffered().use { input ->
                counters.bytes += input.copyTo(output, COPY_BUFFER_SIZE)
            }
        }
        counters.exported += 1
    }

    private fun downloadPath(
        context: Context,
        source: DocumentFile,
        localRoot: File,
        path: String,
        counters: Counters,
        recovery: Recovery,
    ) {
        val target = localEntry(localRoot, path, mustExist = false)
        if (target.exists() && target.isDirectory != source.isDirectory) {
            if (target.isDirectory) assertNoProtectedLocalMetadata(target, path)
            backupLocal(target, recovery.destination("local", path))
            check(target.deleteRecursively()) { "ローカル項目を置換できません: $path" }
        }
        downloadEntry(context, source, target, path, counters)
    }

    private fun downloadEntry(
        context: Context,
        source: DocumentFile,
        target: File,
        path: String,
        counters: Counters,
    ) {
        if (source.isDirectory) {
            check(target.mkdirs() || target.isDirectory) { "ローカルフォルダを作成できません: $path" }
            source.listFiles().sortedBy { it.name.orEmpty().lowercase(Locale.ROOT) }.forEach { child ->
                val name = child.name ?: throw IOException("名前のない端末ファイルは同期できません。")
                if (!isExcludedName(name)) {
                    validateSegment(name)
                    downloadEntry(context, child, File(target, name), "$path/$name", counters)
                }
            }
            return
        }
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".omochi-sync-tmp-${UUID.randomUUID()}")
        try {
            context.contentResolver.openInputStream(source.uri)?.use { input ->
                FileOutputStream(temporary).buffered().use { output ->
                    counters.bytes += input.copyTo(output, COPY_BUFFER_SIZE)
                }
            } ?: throw IOException("端末ファイルを開けません: $path")
            moveReplacing(temporary, target)
            source.lastModified().takeIf { it > 0L }?.let(target::setLastModified)
            counters.imported += 1
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun backupRemote(context: Context, source: DocumentFile, destination: File) {
        if (source.isDirectory) {
            destination.mkdirs()
            source.listFiles().forEach { child ->
                val name = child.name ?: return@forEach
                validateSegment(name)
                backupRemote(context, child, File(destination, name))
            }
        } else if (source.isFile) {
            destination.parentFile?.mkdirs()
            context.contentResolver.openInputStream(source.uri)?.use { input ->
                destination.outputStream().buffered().use { output -> input.copyTo(output, COPY_BUFFER_SIZE) }
            } ?: throw IOException("削除前の端末ファイルを保全できません: ${source.name}")
        }
    }

    private fun backupLocal(source: File, destination: File) {
        check(!Files.isSymbolicLink(source.toPath())) { "シンボリックリンクは保全できません: ${source.name}" }
        if (source.isDirectory) {
            destination.mkdirs()
            source.listFiles().orEmpty().forEach { child ->
                if (!isExcludedName(child.name)) backupLocal(child, File(destination, child.name))
            }
        } else {
            destination.parentFile?.mkdirs()
            source.inputStream().buffered().use { input ->
                destination.outputStream().buffered().use { output -> input.copyTo(output, COPY_BUFFER_SIZE) }
            }
        }
    }

    private fun verifyLocal(file: File, expected: WorkspaceSyncPolicy.Entry?) {
        when {
            expected == null -> check(!file.exists()) { "同期中にローカル項目が追加されました。再試行します。" }
            expected.kind == WorkspaceSyncPolicy.Kind.Directory ->
                check(file.isDirectory) { "同期中にローカル項目の種類が変わりました。再試行します。" }
            else -> {
                check(file.isFile) { "同期中にローカルファイルが変更されました。再試行します。" }
                check(digest(file.inputStream()) == expected.digest) {
                    "同期中にローカルファイルが変更されました。再試行します。"
                }
            }
        }
    }

    private fun verifyRemote(
        context: Context,
        document: DocumentFile?,
        expected: WorkspaceSyncPolicy.Entry?,
    ) {
        when {
            expected == null -> check(document == null) { "同期中に端末側へ項目が追加されました。再試行します。" }
            expected.kind == WorkspaceSyncPolicy.Kind.Directory ->
                check(document?.isDirectory == true) { "同期中に端末側の項目が変わりました。再試行します。" }
            else -> {
                check(document?.isFile == true) { "同期中に端末ファイルが変わりました。再試行します。" }
                val input = context.contentResolver.openInputStream(document.uri)
                    ?: throw IOException("端末ファイルを再確認できません。")
                check(digest(input) == expected.digest) {
                    "同期中に端末ファイルが変更されました。再試行します。"
                }
            }
        }
    }

    private fun uniqueConflictPath(remoteRoot: DocumentFile, localRoot: File, original: String): String {
        val parent = original.substringBeforeLast('/', "")
        val name = original.substringAfterLast('/')
        val isFile = localEntry(localRoot, original).isFile
        val dot = name.lastIndexOf('.').takeIf { isFile && it > 0 } ?: -1
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        for (index in 1..1_000) {
            val suffix = if (index == 1) "" else "-$index"
            val marker = ".device-conflict-$stamp$suffix"
            val extensionBytes = extension.toByteArray(Charsets.UTF_8).size
            val reservedExtension = extension.takeIf {
                extensionBytes <= 48 && marker.toByteArray(Charsets.UTF_8).size + extensionBytes < MAX_SEGMENT_BYTES
            }.orEmpty()
            val stemBudget = MAX_SEGMENT_BYTES -
                marker.toByteArray(Charsets.UTF_8).size -
                reservedExtension.toByteArray(Charsets.UTF_8).size
            val conflictName = truncateUtf8(stem, stemBudget).ifBlank { "conflict" } +
                marker + reservedExtension
            val path = if (parent.isEmpty()) conflictName else "$parent/$conflictName"
            if (!localEntry(localRoot, path, mustExist = false).exists() &&
                findDocument(remoteRoot, path) == null
            ) return path
        }
        throw IOException("競合ファイル名を作成できません: $original")
    }

    private fun requireRemoteRoot(context: Context, uri: Uri): DocumentFile {
        val root = DocumentFile.fromTreeUri(context, uri)
            ?: throw IOException("選択した端末フォルダを開けません。")
        check(root.isDirectory && root.canRead() && root.canWrite()) {
            "選択したフォルダには読み書きできません。"
        }
        return root
    }

    private fun chooseLocalRelativePath(context: Context, label: String): String {
        val workspace = OmochiRuntime.workspaceDir(context).apply { mkdirs() }
        val parent = File(workspace, "phone").apply { mkdirs() }
        ensureNoSymlinkPath(workspace, parent)
        val base = WorkspaceTransfer.safeName(label)
        for (index in 1..10_000) {
            val name = if (index == 1) base else "$base (linked $index)"
            val candidate = File(parent, name)
            checkContained(workspace, candidate)
            if (!candidate.exists()) return "phone/$name"
        }
        throw IOException("ローカル同期フォルダを作成できません。")
    }

    private fun localRoot(context: Context, link: Link): File {
        check(isSafeLocalRelative(link.localRelativePath)) { "保存済み同期パスが不正です。" }
        val workspace = OmochiRuntime.workspaceDir(context).apply { mkdirs() }
        return File(workspace, link.localRelativePath).also {
            checkContained(workspace, it)
            ensureNoSymlinkPath(workspace, it)
        }
    }

    private fun localEntry(root: File, path: String, mustExist: Boolean = true): File {
        validateRelativePath(path)
        return File(root, path).also { file ->
            checkContained(root, file)
            if (mustExist) check(file.exists()) { "ローカル項目が見つかりません: $path" }
        }
    }

    private fun findDocument(root: DocumentFile, path: String): DocumentFile? {
        validateRelativePath(path)
        var current = root
        path.split('/').forEach { segment ->
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    private fun ensureRemoteParent(root: DocumentFile, path: String): DocumentFile {
        if (path.isEmpty()) return root
        validateRelativePath(path)
        var current = root
        path.split('/').forEach { segment ->
            val existing = current.findFile(segment)
            current = when {
                existing == null -> current.createDirectory(segment)
                existing.isDirectory -> existing
                else -> null
            } ?: throw IOException("端末側の親フォルダを作成できません: $path")
        }
        return current
    }

    private fun createRemoteFile(parent: DocumentFile, name: String): DocumentFile {
        val created = parent.createFile(WorkspaceTransfer.mimeType(name), name)
            ?: throw IOException("端末側にファイルを作成できません: $name")
        if (created.name != name) {
            created.delete()
            throw IOException("端末のファイルプロバイダーが名前を変更しました: $name")
        }
        return created
    }

    private fun openRemoteOutput(context: Context, target: DocumentFile) =
        runCatching { context.contentResolver.openOutputStream(target.uri, "rwt") }.getOrNull()
            ?: context.contentResolver.openOutputStream(target.uri, "w")
            ?: throw IOException("端末側のファイルを開けません: ${target.name}")

    private fun deleteDocumentRecursively(document: DocumentFile) {
        if (document.isDirectory) document.listFiles().forEach(::deleteDocumentRecursively)
        check(document.delete()) { "端末側の項目を削除できません: ${document.name}" }
    }

    private fun assertNoProtectedRemoteMetadata(document: DocumentFile, path: String) {
        if (containsProtectedRemoteMetadata(document, 0)) {
            throw IOException(
                "$path 内の .git は自動削除・種類置換しません。Gitデータを保護するため端末側で確認してください。"
            )
        }
    }

    private fun containsProtectedRemoteMetadata(document: DocumentFile, depth: Int): Boolean {
        if (!document.isDirectory || depth > MAX_DEPTH) return false
        return document.listFiles().any { child ->
            child.name == ".git" || containsProtectedRemoteMetadata(child, depth + 1)
        }
    }

    private fun assertNoProtectedLocalMetadata(file: File, path: String) {
        if (containsProtectedLocalMetadata(file, 0)) {
            throw IOException(
                "$path 内の .git は端末側の削除に追従して自動削除しません。Gitデータを保護するため確認してください。"
            )
        }
    }

    private fun containsProtectedLocalMetadata(file: File, depth: Int): Boolean {
        if (!file.isDirectory || depth > MAX_DEPTH || Files.isSymbolicLink(file.toPath())) return false
        return file.listFiles().orEmpty().any { child ->
            child.name == ".git" || containsProtectedLocalMetadata(child, depth + 1)
        }
    }

    private fun releaseAccess(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(uri, TREE_PERMISSION_FLAGS)
        }
    }

    private fun RawEntry.metadata() = SideMetadata(kind, size, modified)

    private fun openInput(context: Context, entry: RawEntry): InputStream = when {
        entry.localFile != null -> entry.localFile.inputStream().buffered()
        entry.document != null -> context.contentResolver.openInputStream(entry.document.uri)?.buffered()
            ?: throw IOException("端末ファイルを読み込めません: ${entry.document.name}")
        else -> error("Directory entries do not have content")
    }

    private fun digest(input: InputStream): String = input.use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun moveReplacing(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        runCatching {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun baselineFile(context: Context) = File(context.filesDir, "workspace-sync/state.json")

    private fun validateRelativePath(path: String) {
        check(path.isNotBlank() && !path.startsWith('/') && !path.endsWith('/')) {
            "不正な同期パスです。"
        }
        path.split('/').forEach(::validateSegment)
    }

    private fun validateSegment(name: String) {
        check(
            name.isNotBlank() && name != "." && name != ".." &&
                '/' !in name && '\\' !in name && '\u0000' !in name &&
                name.toByteArray(Charsets.UTF_8).size <= MAX_SEGMENT_BYTES
        ) { "端末と安全に同期できないファイル名です: $name" }
    }

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        val result = StringBuilder()
        var offset = 0
        var bytes = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val piece = String(Character.toChars(codePoint))
            val pieceBytes = piece.toByteArray(Charsets.UTF_8).size
            if (bytes + pieceBytes > maxBytes) break
            result.append(piece)
            bytes += pieceBytes
            offset += Character.charCount(codePoint)
        }
        return result.toString()
    }

    private fun isSafeLocalRelative(value: String?): Boolean = runCatching {
        require(!value.isNullOrBlank())
        validateRelativePath(value)
        value.startsWith("phone/")
    }.getOrDefault(false)

    private fun isExcludedName(name: String): Boolean =
        name == ".git" || name.startsWith(".omochi-sync-tmp-")

    private fun checkContained(root: File, candidate: File) {
        val canonicalRoot = root.canonicalFile
        val canonicalCandidate = candidate.canonicalFile
        check(
            canonicalCandidate.path == canonicalRoot.path ||
                canonicalCandidate.path.startsWith(canonicalRoot.path + File.separator)
        ) { "同期先がワークスペース外を参照しています。" }
    }

    private fun ensureNoSymlinkPath(root: File, candidate: File) {
        checkContained(root, candidate)
        if (root.exists() && Files.isSymbolicLink(root.toPath())) {
            throw IOException("ワークスペース自体をシンボリックリンクにはできません。")
        }
        val relative = candidate.absoluteFile.toPath().normalize()
            .let { path -> root.absoluteFile.toPath().normalize().relativize(path) }
        var current = root.absoluteFile
        relative.forEach { segment ->
            current = File(current, segment.toString())
            if (current.exists() && Files.isSymbolicLink(current.toPath())) {
                throw IOException("同期先パスにシンボリックリンクは使用できません: ${current.name}")
            }
        }
    }

    private fun publishProgress(link: Link, path: String, counters: Counters) {
        val now = System.currentTimeMillis()
        if (now - counters.lastProgressAt < PROGRESS_INTERVAL_MS) return
        counters.lastProgressAt = now
        publish(
            State.Syncing(
                link = link,
                message = "同期中: $path",
                filesCopied = counters.imported + counters.exported,
            )
        )
    }

    private fun publish(state: State) {
        currentState = state
        main.post { listeners.forEach { listener -> listener(state) } }
    }

    private fun readableError(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: "端末フォルダの同期に失敗しました。"

    private fun executor(): ScheduledExecutorService = synchronized(queueLock) {
        val current = executor
        if (current != null && !current.isShutdown) return@synchronized current
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "OmochiWorkspaceSync").apply { isDaemon = true }
        }.also { executor = it }
    }

    private fun refreshObservers(context: Context, link: Link) {
        if (!runtimeActive || activeLink != link) return
        synchronized(observerLock) {
            clearObserversLocked(context)
            val localRoot = localRoot(context, link).apply { mkdirs() }
            fileObserver = RecursiveWorkspaceObserver(localRoot) { path ->
                enqueue(
                    context,
                    forceLocal = path == null,
                    forceRemote = false,
                    localPath = path,
                )
            }.also { it.start() }
            contentObserver = object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) {
                    enqueue(context, forceLocal = false, forceRemote = true)
                }

                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    onChange(selfChange)
                }
            }.also { observer ->
                runCatching {
                    context.contentResolver.registerContentObserver(link.treeUri, true, observer)
                }
            }
        }
    }

    private fun clearObservers() {
        synchronized(observerLock) {
            appContext?.let(::clearObserversLocked) ?: run {
                fileObserver?.stop()
                fileObserver = null
                contentObserver = null
            }
        }
    }

    private fun clearObserversLocked(context: Context) {
        fileObserver?.stop()
        fileObserver = null
        contentObserver?.let { observer ->
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
        contentObserver = null
    }

    // The File overload was added in API 29; the path overload keeps API 26-28 support.
    @Suppress("DEPRECATION")
    private class RecursiveWorkspaceObserver(
        private val root: File,
        private val onChanged: (String?) -> Unit,
    ) {
        private val observers = mutableListOf<FileObserver>()

        fun start() {
            stop()
            root.walkTopDown()
                .onEnter { directory ->
                    !isExcludedName(directory.name) && !Files.isSymbolicLink(directory.toPath())
                }
                .filter { it.isDirectory }
                .take(MAX_FILE_OBSERVERS)
                .forEach { directory ->
                    val directoryPath = directory.relativeTo(root).invariantSeparatorsPath
                        .takeUnless { it == "." }
                        .orEmpty()
                    val observer = object : FileObserver(directory.absolutePath, FILE_OBSERVER_MASK) {
                        override fun onEvent(event: Int, path: String?) {
                            if (path != null && isExcludedName(path)) return
                            val relative = when {
                                path == null -> directoryPath.takeIf { it.isNotEmpty() }
                                directoryPath.isEmpty() -> path
                                else -> "$directoryPath/$path"
                            }
                            onChanged(relative)
                        }
                    }
                    observer.startWatching()
                    observers += observer
                }
        }

        fun stop() {
            observers.forEach(FileObserver::stopWatching)
            observers.clear()
        }
    }

    private const val PREFERENCES = "omochi-workspace-sync-v1"
    private const val KEY_TREE_URI = "tree-uri"
    private const val KEY_LABEL = "label"
    private const val KEY_LOCAL_RELATIVE = "local-relative"
    private const val KEY_LAST_SYNC = "last-sync"
    private const val BASELINE_VERSION = 1
    private const val PERIODIC_SCAN_SECONDS = 3L
    private const val FULL_VERIFY_INTERVAL_MS = 30_000L
    private const val LOCAL_DEBOUNCE_MS = 450L
    private const val PROGRESS_INTERVAL_MS = 200L
    private const val FINAL_VERIFY_ATTEMPTS = 3
    private const val FINAL_VERIFY_DELAY_MS = 250L
    private const val COPY_BUFFER_SIZE = 128 * 1024
    private const val MAX_ENTRIES = 50_000
    private const val MAX_DEPTH = 64
    private const val MAX_SEGMENT_BYTES = 240
    private const val MAX_FILE_OBSERVERS = 2_048
    private const val FILE_OBSERVER_MASK =
        FileObserver.CLOSE_WRITE or FileObserver.CREATE or FileObserver.DELETE or
            FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.DELETE_SELF or
            FileObserver.MOVE_SELF
}
