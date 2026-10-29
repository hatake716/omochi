package io.github.hatake716.omochi

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
 * Local FileObserver events are coalesced for 450 ms, provider ContentObserver events request a
 * remote verification, and an adaptive periodic scan covers providers that do not emit
 * notifications: every 3 s after activity, stretching to 30 s while idle and backing off to
 * 60 s while syncs fail (see [WorkspaceSyncPolicy.nextScanDelayMs]).
 *
 * The SAF side is read with one DocumentsContract child query per directory. A listing that
 * fails, returns no cursor, or is still loading aborts the sync instead of looking like a set
 * of deleted files.
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
        val remoteUri: Uri? = null,
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

    /** The parsed state.json of [link], valid while the file keeps the same [stamp]. */
    private class BaselineCache(
        val link: Link,
        val stamp: Pair<Long, Long>,
        val records: Map<String, BaselineRecord>,
    )

    /** One SAF document, read from a single DocumentsContract row. */
    private data class RemoteNode(
        val uri: Uri,
        val documentId: String,
        val name: String,
        val kind: WorkspaceSyncPolicy.Kind?,
        val size: Long = 0L,
        val modified: Long = 0L,
    ) {
        val isDirectory: Boolean get() = kind == WorkspaceSyncPolicy.Kind.Directory
        val isFile: Boolean get() = kind == WorkspaceSyncPolicy.Kind.File
    }

    /** The linked SAF tree: [treeUri] carries the grant, [root] is its top document. */
    private class RemoteTree(val treeUri: Uri, val root: RemoteNode)

    private data class SyncRequest(
        val forceLocal: Boolean,
        val forceRemote: Boolean,
        val localPaths: Set<String>,
        val announce: Boolean,
    )

    private data class SyncResult(
        val summary: Summary,
        /** True when the sync copied, deleted, or re-recorded anything. */
        val changed: Boolean,
    )

    /** Why a sync was requested, ordered by priority. */
    private enum class Trigger {
        /** The adaptive timer. Follows the failure backoff and does not count as activity. */
        Periodic,

        /** A FileObserver or ContentObserver change. Retries at most every 3 s while failing. */
        Change,

        /** start/stop/requestSync. Runs without waiting for a failure backoff. */
        Explicit,
    }

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
    @Volatile private var baselineCache: BaselineCache? = null

    /** Until this monotonic time, provider notifications are treated as echoes of our writes. */
    @Volatile private var remoteWritesUntil = Long.MIN_VALUE

    /** Mirror paths this process just replaced or deleted, with the monotonic time the echo ends. */
    private val selfWrites = ConcurrentHashMap<String, Long>()

    private var executor: ScheduledExecutorService? = null

    // Guarded by observerLock.
    private var contentObserver: ContentObserver? = null
    private var fileObserver: RecursiveWorkspaceObserver? = null
    private var observedLink: Link? = null

    // Guarded by queueLock.
    private var periodicTask: ScheduledFuture<*>? = null
    private var drainTask: ScheduledFuture<*>? = null
    private var drainAt = 0L
    private var draining = false
    private var requestPending = false
    private var pendingForceLocal = false
    private var pendingForceRemote = false
    private var pendingAnnouncement = false
    private var pendingTrigger = Trigger.Periodic
    private var pendingRunAt = Long.MAX_VALUE
    private val pendingLocalPaths = linkedSetOf<String>()
    private var quietScans = 0
    private var consecutiveFailures = 0
    private var lastFailureAt = 0L

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
        val link = activeLink ?: return
        // Walking the mirror and registering up to MAX_FILE_OBSERVERS inotify watches can take a
        // while on a large tree, so it runs on the sync thread instead of Service.onCreate's main
        // thread. It is queued before the first scan so edits made during that scan are seen.
        executor().execute { watchLocalTree(app, link) }
        enqueue(app, forceLocal = false, forceRemote = true, delayMs = 0L, trigger = Trigger.Explicit)
    }

    /** Stops observers and the periodic timer, then requests one final loss-averse flush. */
    fun stop() {
        // Cleared before taking either lock: the sync thread re-checks it under the same locks
        // before it re-arms the timer or registers observers, so neither can outlive stop().
        runtimeActive = false
        synchronized(queueLock) {
            periodicTask?.cancel(false)
            periodicTask = null
        }
        clearObservers()
        appContext?.let { app ->
            if (activeLink != null) {
                enqueue(app, forceLocal = true, forceRemote = true, delayMs = 0L, trigger = Trigger.Explicit)
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
            val result = runCatching {
                app.contentResolver.takePersistableUriPermission(treeUri, TREE_PERMISSION_FLAGS)
                check(hasReadWriteAccess(app, treeUri)) {
                    "このフォルダの永続的な読み書き権限を取得できません。"
                }
                val remote = requireRemoteRoot(app, treeUri)
                val label = remote.root.name.takeIf { it.isNotBlank() } ?: "端末フォルダ"
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
                performSync(
                    context = app,
                    link = link,
                    request = SyncRequest(
                        forceLocal = true,
                        forceRemote = true,
                        localPaths = emptySet(),
                        announce = true,
                    ),
                )
            }.also {
                if (switchedLink && previous != null && previous.treeUri != treeUri) {
                    releaseAccess(app, previous.treeUri)
                }
            }.onFailure { error ->
                publish(State.Failed(activeLink ?: previous, readableError(error)))
            }
            synchronized(queueLock) { finishAttemptLocked(app, result) }
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
            synchronized(queueLock) {
                periodicTask?.cancel(false)
                periodicTask = null
            }
            baselineCache = null
            selfWrites.clear()
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
            trigger = Trigger.Explicit,
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
        trigger: Trigger = Trigger.Change,
    ) {
        if (activeLink == null) return
        synchronized(queueLock) {
            requestPending = true
            pendingForceLocal = pendingForceLocal || forceLocal
            pendingForceRemote = pendingForceRemote || forceRemote
            pendingAnnouncement = pendingAnnouncement || announce
            if (trigger > pendingTrigger) pendingTrigger = trigger
            // Anything but the timer itself is activity: return to the short scan interval.
            if (trigger != Trigger.Periodic) quietScans = 0
            localPath?.let { pendingLocalPaths += it }
            pendingRunAt = minOf(pendingRunAt, nowMs() + delayMs)
            scheduleDrainLocked(context)
        }
    }

    /**
     * Schedules the single drain task for the pending request, or pulls an already scheduled one
     * forward. While syncs fail, the start is held back by the backoff that fits the trigger.
     */
    private fun scheduleDrainLocked(context: Context) {
        if (!requestPending || draining) return
        val notBefore = when {
            consecutiveFailures == 0 -> Long.MIN_VALUE
            pendingTrigger == Trigger.Explicit -> Long.MIN_VALUE
            pendingTrigger == Trigger.Change ->
                lastFailureAt + WorkspaceSyncPolicy.changeRetryDelayMs(consecutiveFailures)
            else -> lastFailureAt + WorkspaceSyncPolicy.retryDelayMs(consecutiveFailures)
        }
        val runAt = maxOf(pendingRunAt, notBefore)
        drainTask?.let { scheduled ->
            if (drainAt <= runAt) return
            // A task that already started will take the pending request when it gets the lock.
            if (!scheduled.cancel(false)) return
        }
        drainAt = runAt
        drainTask = executor().schedule(
            { drainQueue(context) },
            (runAt - nowMs()).coerceAtLeast(0L),
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * Runs one coalesced request. Requests that arrive meanwhile are scheduled as a new task
     * instead of looping here, so connect/disconnect queued on the same thread are not starved.
     */
    private fun drainQueue(context: Context) {
        val request = synchronized(queueLock) {
            drainTask = null
            if (!requestPending) return
            draining = true
            SyncRequest(
                forceLocal = pendingForceLocal,
                // Checked here rather than only by the timer: steady edits keep re-arming the
                // timer before it fires, and must not postpone the periodic full verification.
                forceRemote = pendingForceRemote || (
                    runtimeActive && WorkspaceSyncPolicy.fullVerificationDue(
                        nowMs = System.currentTimeMillis(),
                        lastVerifiedAtMs = lastFullRemoteVerification,
                    )
                ),
                localPaths = pendingLocalPaths.toSet(),
                announce = pendingAnnouncement,
            ).also {
                requestPending = false
                pendingForceLocal = false
                pendingForceRemote = false
                pendingAnnouncement = false
                pendingTrigger = Trigger.Periodic
                pendingRunAt = Long.MAX_VALUE
                pendingLocalPaths.clear()
            }
        }
        var result: Result<SyncResult>? = null
        try {
            val link = activeLink
            if (link != null) {
                result = runCatching { performSync(context, link, request) }
                    .onFailure { error -> publishFailure(link, readableError(error)) }
            }
        } finally {
            // One critical section, so a request arriving in between cannot be scheduled with
            // the failure count from before this attempt.
            synchronized(queueLock) {
                draining = false
                finishAttemptLocked(context, result)
            }
        }
    }

    /** Updates the cadence after a sync attempt, then re-arms the drain and the timer. */
    private fun finishAttemptLocked(context: Context, result: Result<SyncResult>?) {
        val synced = result?.getOrNull()
        when {
            result == null -> Unit
            synced != null -> {
                consecutiveFailures = 0
                quietScans = if (synced.changed) 0 else (quietScans + 1).coerceAtMost(MAX_TRACKED_ATTEMPTS)
            }
            else -> {
                consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(MAX_TRACKED_ATTEMPTS)
                quietScans = 0
                lastFailureAt = nowMs()
            }
        }
        scheduleDrainLocked(context)
        schedulePeriodicLocked(context)
    }

    private fun schedulePeriodicLocked(context: Context) {
        if (!runtimeActive || activeLink == null) return
        periodicTask?.cancel(false)
        periodicTask = executor().schedule(
            { periodicScan(context) },
            WorkspaceSyncPolicy.nextScanDelayMs(quietScans, consecutiveFailures),
            TimeUnit.MILLISECONDS,
        )
    }

    /** The adaptive timer. Whether the scan also re-hashes the SAF side is decided when it runs. */
    private fun periodicScan(context: Context) {
        if (!runtimeActive) return
        enqueue(context, forceLocal = false, forceRemote = false, delayMs = 0L, trigger = Trigger.Periodic)
    }

    private fun performSync(context: Context, link: Link, request: SyncRequest): SyncResult {
        check(activeLink == link) { "端末フォルダの連携先が変更されました。" }
        check(hasReadWriteAccess(context, link.treeUri)) {
            "端末フォルダの読み書き権限がありません。フォルダを選び直してください。"
        }
        val remote = requireRemoteRoot(context, link.treeUri)
        val localRoot = localRoot(context, link).apply { mkdirs() }
        if (request.announce) {
            publish(State.Syncing(link, "${link.label} の変更を確認しています…"))
        }

        pruneSelfWrites()
        val baseline = loadBaseline(context, link)
        val localSnapshot = scanLocal(localRoot)
        val remoteSnapshot = scanRemote(context, remote)
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
            } else if (request.forceRemote) {
                lastFullRemoteVerification = System.currentTimeMillis()
            }
            updateObservers(context, link, localSnapshot)
            return SyncResult(summary, changed = !unchanged)
        }
        val recovery = Recovery(context, link)
        val affected = linkedSetOf<String>()

        actions.forEach { action ->
            check(activeLink == link) { "同期中に端末フォルダの連携先が変更されました。" }
            publishProgress(link, action.path, counters)
            executeAction(
                context = context,
                link = link,
                remote = remote,
                action = action,
                expectedLocal = localResolved,
                expectedRemote = remoteResolved,
                counters = counters,
                recovery = recovery,
            )
            affected += action.path
        }

        var records: Map<String, BaselineRecord>? = null
        var verifiedLocal: Snapshot? = null
        for (attempt in 1..FINAL_VERIFY_ATTEMPTS) {
            val finalLocalRaw = scanLocal(localRoot)
            val finalRemoteRaw = scanRemote(context, remote)
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
                verifiedLocal = finalLocalRaw
                break
            }
            if (attempt < FINAL_VERIFY_ATTEMPTS) Thread.sleep(FINAL_VERIFY_DELAY_MS)
        }
        check(records != null && verifiedLocal != null) {
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
        updateObservers(context, link, verifiedLocal)
        return SyncResult(summary, changed = true)
    }

    private fun executeAction(
        context: Context,
        link: Link,
        remote: RemoteTree,
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
                verifyRemote(context, findRemote(context, remote, action.path), expectedRemote[action.path])
                uploadPath(context, remote, source, action.path, counters, recovery)
            }

            is WorkspaceSyncPolicy.Action.Download -> {
                val source = findRemote(context, remote, action.path)
                    ?: throw IOException("端末側の項目が見つかりません: ${action.path}")
                verifyLocal(localEntry(localRoot, action.path), expectedLocal[action.path])
                downloadPath(context, remote, source, localRoot, action.path, counters, recovery)
            }

            is WorkspaceSyncPolicy.Action.DeleteRemote -> {
                val target = findRemote(context, remote, action.path)
                    ?: throw IOException("削除する端末側の項目が見つかりません: ${action.path}")
                assertNoProtectedRemoteMetadata(context, remote, target, action.path)
                backupRemote(context, remote, target, recovery.destination("device", action.path), action.path)
                deleteRemoteRecursively(context, remote, target, action.path)
                counters.deletedRemote += 1
            }

            is WorkspaceSyncPolicy.Action.DeleteLocal -> {
                val target = localEntry(localRoot, action.path)
                assertNoProtectedLocalMetadata(target, action.path)
                backupLocal(target, recovery.destination("local", action.path))
                markSelfWrite(action.path)
                check(target.deleteRecursively()) { "ローカル項目を削除できません: ${action.path}" }
                markSelfWrite(action.path)
                counters.deletedLocal += 1
            }

            is WorkspaceSyncPolicy.Action.PreserveConflict -> {
                val localSource = localEntry(localRoot, action.path)
                val remoteSource = findRemote(context, remote, action.path)
                    ?: throw IOException("競合した端末側の項目が見つかりません: ${action.path}")
                verifyLocal(localSource, expectedLocal[action.path])
                verifyRemote(context, remoteSource, expectedRemote[action.path])
                if (localSource.isFile && remoteSource.isDirectory) {
                    assertNoProtectedRemoteMetadata(context, remote, remoteSource, action.path)
                }
                val conflictPath = uniqueConflictPath(context, remote, localRoot, action.path)
                downloadPath(context, remote, remoteSource, localRoot, conflictPath, counters, recovery)
                uploadPath(
                    context,
                    remote,
                    localEntry(localRoot, conflictPath),
                    conflictPath,
                    counters,
                    recovery,
                )
                uploadPath(context, remote, localSource, action.path, counters, recovery)
                counters.conflicts += 1
            }
        }
    }

    private fun scanLocal(root: File): Snapshot {
        val entries = linkedMapOf<String, RawEntry>()
        var excluded = 0
        val canonicalRoot = root.canonicalFile
        val canonicalRootPath = canonicalRoot.path

        fun visit(directory: File, prefix: String, depth: Int) {
            check(depth <= MAX_DEPTH) { "同期フォルダの階層が深すぎます。" }
            val children = directory.listFiles()
            if (children == null) {
                // A subdirectory removed after its parent was listed is simply gone. Any other
                // listing failure must not read as "every child was deleted".
                if (prefix.isNotEmpty() && Files.notExists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    entries.remove(prefix)
                    return
                }
                throw IOException(
                    if (prefix.isEmpty()) "ローカルの同期フォルダを読み取れません。" else "ローカルフォルダを読み取れません: $prefix"
                )
            }
            children.map { it.name.lowercase(Locale.ROOT) to it }.sortedBy { it.first }.forEach { (_, child) ->
                if (isExcludedName(child.name)) {
                    excluded += 1
                    return@forEach
                }
                validateSegment(child.name)
                // One lstat per entry instead of separate isSymbolicLink/isDirectory/isFile/
                // length/lastModified calls. An entry deleted since the listing is skipped.
                val attributes = try {
                    Files.readAttributes(child.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (vanished: NoSuchFileException) {
                    return@forEach
                }
                if (attributes.isSymbolicLink) {
                    throw IOException("シンボリックリンクは端末フォルダへ同期できません: ${child.name}")
                }
                checkContainedIn(canonicalRootPath, child)
                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                check(entries.size < MAX_ENTRIES) { "同期対象が多すぎます（上限 $MAX_ENTRIES 項目）。" }
                when {
                    attributes.isDirectory -> {
                        entries[path] = RawEntry(
                            kind = WorkspaceSyncPolicy.Kind.Directory,
                            size = 0L,
                            modified = attributes.lastModifiedTime().toMillis(),
                            localFile = child,
                        )
                        visit(child, path, depth + 1)
                    }
                    attributes.isRegularFile -> entries[path] = RawEntry(
                        kind = WorkspaceSyncPolicy.Kind.File,
                        size = attributes.size(),
                        modified = attributes.lastModifiedTime().toMillis(),
                        localFile = child,
                    )
                }
            }
        }

        visit(canonicalRoot, "", 0)
        return Snapshot(entries, excluded)
    }

    private fun scanRemote(context: Context, remote: RemoteTree): Snapshot {
        val entries = linkedMapOf<String, RawEntry>()
        var excluded = 0

        fun visit(directory: RemoteNode, prefix: String, depth: Int) {
            check(depth <= MAX_DEPTH) { "端末フォルダの階層が深すぎます。" }
            val names = hashSetOf<String>()
            listRemoteChildren(context, remote.treeUri, directory, prefix).forEach { child ->
                val name = child.name
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
                            modified = child.modified,
                            remoteUri = child.uri,
                        )
                        visit(child, path, depth + 1)
                    }
                    child.isFile -> entries[path] = RawEntry(
                        kind = WorkspaceSyncPolicy.Kind.File,
                        size = child.size,
                        modified = child.modified,
                        remoteUri = child.uri,
                    )
                }
            }
        }

        visit(remote.root, "", 0)
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
            val forced = forceAll || WorkspaceSyncPolicy.isWithin(path, forcePaths)
            val digest = if (!forced && previous?.entry?.kind == WorkspaceSyncPolicy.Kind.File &&
                previousMetadata == metadata
            ) {
                previous.entry.digest!!
            } else {
                digest(openInput(context, value, path))
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

    /**
     * Returns the saved baseline. Only this thread writes state.json, so the parsed records are
     * reused while the file keeps its modification time and length instead of being re-parsed
     * on every scan.
     */
    private fun loadBaseline(context: Context, link: Link): Map<String, BaselineRecord> {
        val file = baselineFile(context)
        val stamp = file.lastModified() to file.length()
        baselineCache?.let { cached ->
            if (cached.link == link && cached.stamp == stamp) return cached.records
        }
        val records = readBaseline(file, link)
        baselineCache = BaselineCache(link, stamp, records)
        return records
    }

    private fun readBaseline(file: File, link: Link): Map<String, BaselineRecord> = runCatching {
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
        // Never let a failed write leave records cached for a file that now holds other data.
        baselineCache = null
        temporary.writeText(root.toString(), Charsets.UTF_8)
        moveReplacing(temporary, destination)
        baselineCache = BaselineCache(link, destination.lastModified() to destination.length(), records)
    }

    private fun uploadPath(
        context: Context,
        remote: RemoteTree,
        source: File,
        path: String,
        counters: Counters,
        recovery: Recovery,
    ) {
        check(!Files.isSymbolicLink(source.toPath())) { "シンボリックリンクは同期できません: $path" }
        val parentPath = path.substringBeforeLast('/', "")
        val parent = ensureRemoteParent(context, remote, parentPath)
        val name = path.substringAfterLast('/')
        val existing = remoteChild(context, remote, parent, parentPath, name)
        uploadEntry(context, remote, source, parent, name, path, existing, counters, recovery, depth = 0)
    }

    private fun uploadEntry(
        context: Context,
        remote: RemoteTree,
        source: File,
        remoteParent: RemoteNode,
        name: String,
        path: String,
        existing: RemoteNode?,
        counters: Counters,
        recovery: Recovery,
        depth: Int,
    ) {
        check(depth <= MAX_DEPTH) { "同期フォルダの階層が深すぎます。" }
        if (source.isDirectory) {
            val directory = when {
                existing == null -> createRemoteDirectory(context, remoteParent, name)
                existing.isDirectory -> existing
                else -> {
                    backupRemote(context, remote, existing, recovery.destination("device", path), path)
                    deleteRemoteRecursively(context, remote, existing, path)
                    createRemoteDirectory(context, remoteParent, name)
                }
            } ?: throw IOException("端末側にフォルダを作成できません: $path")
            // One listing for all children instead of one lookup per child. A directory created
            // just now is known to be empty.
            val remoteChildren = if (directory === existing) {
                listRemoteChildren(context, remote.treeUri, directory, path).associateBy { it.name }
            } else {
                emptyMap()
            }
            val children = source.listFiles() ?: throw IOException("ローカルフォルダを読み取れません: $path")
            children.sortedBy { it.name.lowercase(Locale.ROOT) }.forEach { child ->
                if (!isExcludedName(child.name)) {
                    // The scan rejected links, but one may have appeared since; never follow it
                    // out of the mirror into shared storage.
                    check(!Files.isSymbolicLink(child.toPath())) {
                        "シンボリックリンクは同期できません: $path/${child.name}"
                    }
                    uploadEntry(
                        context,
                        remote,
                        child,
                        directory,
                        child.name,
                        "$path/${child.name}",
                        remoteChildren[child.name],
                        counters,
                        recovery,
                        depth + 1,
                    )
                }
            }
            return
        }

        val target = when {
            existing == null -> createRemoteFile(context, remoteParent, name)
            existing.isFile -> existing
            else -> {
                assertNoProtectedRemoteMetadata(context, remote, existing, path)
                backupRemote(context, remote, existing, recovery.destination("device", path), path)
                deleteRemoteRecursively(context, remote, existing, path)
                createRemoteFile(context, remoteParent, name)
            }
        }
        markRemoteWrite()
        openRemoteOutput(context, target).use { output ->
            source.inputStream().buffered().use { input ->
                counters.bytes += input.copyTo(output, COPY_BUFFER_SIZE)
            }
        }
        markRemoteWrite()
        counters.exported += 1
    }

    private fun downloadPath(
        context: Context,
        remote: RemoteTree,
        source: RemoteNode,
        localRoot: File,
        path: String,
        counters: Counters,
        recovery: Recovery,
    ) {
        val target = localEntry(localRoot, path, mustExist = false)
        if (target.exists() && target.isDirectory != source.isDirectory) {
            if (target.isDirectory) assertNoProtectedLocalMetadata(target, path)
            backupLocal(target, recovery.destination("local", path))
            markSelfWrite(path)
            check(target.deleteRecursively()) { "ローカル項目を置換できません: $path" }
        }
        downloadEntry(context, remote, source, target, path, counters)
    }

    private fun downloadEntry(
        context: Context,
        remote: RemoteTree,
        source: RemoteNode,
        target: File,
        path: String,
        counters: Counters,
    ) {
        if (source.isDirectory) {
            markSelfWrite(path)
            check(target.mkdirs() || target.isDirectory) { "ローカルフォルダを作成できません: $path" }
            listRemoteChildren(context, remote.treeUri, source, path).forEach { child ->
                val name = child.name
                if (!isExcludedName(name)) {
                    validateSegment(name)
                    downloadEntry(context, remote, child, File(target, name), "$path/$name", counters)
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
            // The rename below is the only event the mirror's FileObserver sees for this file.
            // It is this sync's own write, so it must not queue another sync.
            markSelfWrite(path)
            moveReplacing(temporary, target)
            markSelfWrite(path)
            source.modified.takeIf { it > 0L }?.let(target::setLastModified)
            counters.imported += 1
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun backupRemote(
        context: Context,
        remote: RemoteTree,
        source: RemoteNode,
        destination: File,
        path: String,
    ) {
        if (source.isDirectory) {
            destination.mkdirs()
            listRemoteChildren(context, remote.treeUri, source, path).forEach { child ->
                validateSegment(child.name)
                backupRemote(context, remote, child, File(destination, child.name), "$path/${child.name}")
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
            val children = source.listFiles()
                ?: throw IOException("削除前のローカルフォルダを保全できません: ${source.name}")
            children.forEach { child ->
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
        document: RemoteNode?,
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

    private fun uniqueConflictPath(
        context: Context,
        remote: RemoteTree,
        localRoot: File,
        original: String,
    ): String {
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
                findRemote(context, remote, path) == null
            ) return path
        }
        throw IOException("競合ファイル名を作成できません: $original")
    }

    /**
     * Resolves the linked tree's top document with one query for its name, type and flags. It
     * applies DocumentFile's isDirectory/canRead/canWrite rules; the grant itself is checked by
     * [hasReadWriteAccess] before every caller gets here.
     */
    private fun requireRemoteRoot(context: Context, treeUri: Uri): RemoteTree {
        val documentId = runCatching { rootDocumentId(treeUri) }.getOrNull()
            ?: throw IOException("選択した端末フォルダを開けません。")
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        var name = ""
        var mimeType: String? = null
        var flags = 0
        val found = runCatching {
            context.contentResolver.query(uri, ROOT_PROJECTION, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                name = cursor.stringAt(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)).orEmpty()
                mimeType = cursor.stringAt(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE))
                flags = cursor.longAt(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS))?.toInt() ?: 0
                true
            } ?: false
        }.getOrDefault(false)
        check(found && isWritableDirectory(mimeType, flags)) {
            "選択したフォルダには読み書きできません。"
        }
        return RemoteTree(
            treeUri = treeUri,
            root = RemoteNode(uri, documentId, name, WorkspaceSyncPolicy.Kind.Directory),
        )
    }

    /** Same document as DocumentFile.fromTreeUri, without its PackageManager lookup. */
    private fun rootDocumentId(treeUri: Uri): String {
        val segments = treeUri.pathSegments
        return if (segments.size >= 4 && segments[0] == "tree" && segments[2] == "document") {
            DocumentsContract.getDocumentId(treeUri)
        } else {
            DocumentsContract.getTreeDocumentId(treeUri)
        }
    }

    /** DocumentFile.isDirectory && canWrite for a document that has already been read. */
    private fun isWritableDirectory(mimeType: String?, flags: Int): Boolean {
        if (mimeType != WorkspaceSyncPolicy.DIRECTORY_MIME_TYPE) return false
        val writable = DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
            DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or
            DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        return flags and writable != 0
    }

    /**
     * Lists [parent] with a single DocumentsContract query that returns every attribute the sync
     * needs. It throws when the query fails, returns no cursor, or the provider marks the result
     * as loading or failed, so a caller never mistakes an incomplete listing for deletions.
     */
    private fun listRemoteChildren(
        context: Context,
        treeUri: Uri,
        parent: RemoteNode,
        path: String,
    ): List<RemoteNode> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent.documentId)
        val listing = try {
            context.contentResolver.query(childrenUri, CHILD_PROJECTION, null, null, null)
                ?.use(::readChildListing)
        } catch (error: Exception) {
            throw IOException(remoteListingError(path), error)
        } ?: throw IOException(remoteListingError(path))
        return WorkspaceSyncPolicy.children(listing).map { child ->
            RemoteNode(
                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId),
                documentId = child.documentId,
                name = child.name,
                kind = child.kind,
                size = child.size,
                modified = child.modified,
            )
        }
    }

    private fun readChildListing(cursor: Cursor): WorkspaceSyncPolicy.ChildListing {
        val id = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val name = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeType = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val size = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
        val modified = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val rows = ArrayList<WorkspaceSyncPolicy.ChildRow>(cursor.count.coerceAtLeast(0))
        while (cursor.moveToNext()) {
            rows += WorkspaceSyncPolicy.ChildRow(
                documentId = cursor.stringAt(id),
                displayName = cursor.stringAt(name),
                mimeType = cursor.stringAt(mimeType),
                size = cursor.longAt(size),
                lastModified = cursor.longAt(modified),
            )
        }
        val extras = cursor.extras
        return WorkspaceSyncPolicy.ChildListing(
            rows = rows,
            loading = extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) == true,
            error = extras?.getString(DocumentsContract.EXTRA_ERROR) != null,
        )
    }

    private fun Cursor.stringAt(index: Int): String? =
        if (index < 0 || isNull(index)) null else getString(index)

    private fun Cursor.longAt(index: Int): Long? =
        if (index < 0 || isNull(index)) null else getLong(index)

    private fun remoteListingError(path: String): String =
        if (path.isEmpty()) {
            "端末フォルダの一覧を取得できません。後で再同期します。"
        } else {
            "端末フォルダの一覧を取得できません: $path"
        }

    private fun remoteChild(
        context: Context,
        remote: RemoteTree,
        parent: RemoteNode,
        parentPath: String,
        name: String,
    ): RemoteNode? =
        listRemoteChildren(context, remote.treeUri, parent, parentPath).firstOrNull { it.name == name }

    private fun remoteDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, NAME_PROJECTION, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.stringAt(0) else null
        }
    }.getOrNull()

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

    private fun findRemote(context: Context, remote: RemoteTree, path: String): RemoteNode? {
        validateRelativePath(path)
        var current = remote.root
        var currentPath = ""
        path.split('/').forEach { segment ->
            if (!current.isDirectory) return null
            current = remoteChild(context, remote, current, currentPath, segment) ?: return null
            currentPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"
        }
        return current
    }

    private fun ensureRemoteParent(context: Context, remote: RemoteTree, path: String): RemoteNode {
        if (path.isEmpty()) return remote.root
        validateRelativePath(path)
        var current = remote.root
        var currentPath = ""
        var created = false
        path.split('/').forEach { segment ->
            // Below a directory created in this loop there is nothing to look up.
            val existing = if (created) null else remoteChild(context, remote, current, currentPath, segment)
            current = when {
                existing == null -> createRemoteDirectory(context, current, segment)?.also { created = true }
                existing.isDirectory -> existing
                else -> null
            } ?: throw IOException("端末側の親フォルダを作成できません: $path")
            currentPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"
        }
        return current
    }

    private fun createRemoteDirectory(context: Context, parent: RemoteNode, name: String): RemoteNode? {
        markRemoteWrite()
        val uri = runCatching {
            DocumentsContract.createDocument(
                context.contentResolver,
                parent.uri,
                WorkspaceSyncPolicy.DIRECTORY_MIME_TYPE,
                name,
            )
        }.getOrNull() ?: return null
        markRemoteWrite()
        return RemoteNode(uri, DocumentsContract.getDocumentId(uri), name, WorkspaceSyncPolicy.Kind.Directory)
    }

    private fun createRemoteFile(context: Context, parent: RemoteNode, name: String): RemoteNode {
        markRemoteWrite()
        val uri = runCatching {
            DocumentsContract.createDocument(
                context.contentResolver,
                parent.uri,
                WorkspaceTransfer.mimeType(name),
                name,
            )
        }.getOrNull() ?: throw IOException("端末側にファイルを作成できません: $name")
        markRemoteWrite()
        if (remoteDisplayName(context, uri) != name) {
            deleteRemoteDocument(context, uri)
            throw IOException("端末のファイルプロバイダーが名前を変更しました: $name")
        }
        return RemoteNode(uri, DocumentsContract.getDocumentId(uri), name, WorkspaceSyncPolicy.Kind.File)
    }

    private fun openRemoteOutput(context: Context, target: RemoteNode) =
        runCatching { context.contentResolver.openOutputStream(target.uri, "rwt") }.getOrNull()
            ?: context.contentResolver.openOutputStream(target.uri, "w")
            ?: throw IOException("端末側のファイルを開けません: ${target.name}")

    private fun deleteRemoteRecursively(context: Context, remote: RemoteTree, document: RemoteNode, path: String) {
        if (document.isDirectory) {
            listRemoteChildren(context, remote.treeUri, document, path).forEach { child ->
                deleteRemoteRecursively(context, remote, child, "$path/${child.name}")
            }
        }
        check(deleteRemoteDocument(context, document.uri)) { "端末側の項目を削除できません: ${document.name}" }
    }

    private fun deleteRemoteDocument(context: Context, uri: Uri): Boolean {
        markRemoteWrite()
        val deleted = runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            .getOrDefault(false)
        markRemoteWrite()
        return deleted
    }

    private fun assertNoProtectedRemoteMetadata(
        context: Context,
        remote: RemoteTree,
        document: RemoteNode,
        path: String,
    ) {
        if (containsProtectedRemoteMetadata(context, remote, document, path, 0)) {
            throw IOException(
                "$path 内の .git は自動削除・種類置換しません。Gitデータを保護するため端末側で確認してください。"
            )
        }
    }

    private fun containsProtectedRemoteMetadata(
        context: Context,
        remote: RemoteTree,
        document: RemoteNode,
        path: String,
        depth: Int,
    ): Boolean {
        if (!document.isDirectory || depth > MAX_DEPTH) return false
        return listRemoteChildren(context, remote.treeUri, document, path).any { child ->
            child.name == ".git" ||
                containsProtectedRemoteMetadata(context, remote, child, "$path/${child.name}", depth + 1)
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
        val children = file.listFiles() ?: throw IOException("ローカルフォルダを確認できません: ${file.name}")
        return children.any { child ->
            child.name == ".git" || containsProtectedLocalMetadata(child, depth + 1)
        }
    }

    private fun releaseAccess(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(uri, TREE_PERMISSION_FLAGS)
        }
    }

    private fun RawEntry.metadata() = SideMetadata(kind, size, modified)

    private fun openInput(context: Context, entry: RawEntry, path: String): InputStream = when {
        entry.localFile != null -> entry.localFile.inputStream().buffered()
        entry.remoteUri != null -> context.contentResolver.openInputStream(entry.remoteUri)?.buffered()
            ?: throw IOException("端末ファイルを読み込めません: $path")
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
        val bytes = digest.digest()
        val hex = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            hex[index * 2] = HEX_DIGITS[value ushr 4]
            hex[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
        }
        String(hex)
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

    private fun checkContained(root: File, candidate: File) =
        checkContainedIn(root.canonicalFile.path, candidate)

    /** [checkContained] for a root already resolved once, as a scan checks every entry. */
    private fun checkContainedIn(canonicalRootPath: String, candidate: File) {
        val canonicalCandidate = candidate.canonicalFile
        check(
            canonicalCandidate.path == canonicalRootPath ||
                canonicalCandidate.path.startsWith(canonicalRootPath + File.separator)
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

    /**
     * Publishes a failure unless the same failure is already showing. Retries of a persistent
     * error would otherwise re-open the banner the user just closed on every attempt.
     */
    private fun publishFailure(link: Link, message: String) {
        val current = currentState
        if (current is State.Failed && current.link == link && current.message == message) return
        publish(State.Failed(link, message))
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

    /** Monotonic milliseconds, the clock ScheduledExecutorService delays are measured on. */
    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    /** Marks [path] (and everything below it) in the mirror as written by this sync. */
    private fun markSelfWrite(path: String) {
        selfWrites[path] = nowMs() + SELF_EVENT_GRACE_MS
    }

    private fun isRecentSelfWrite(path: String): Boolean {
        if (selfWrites.isEmpty()) return false
        val now = nowMs()
        return WorkspaceSyncPolicy.isWithin(path) { candidate ->
            val until = selfWrites[candidate]
            until != null && until >= now
        }
    }

    private fun pruneSelfWrites() {
        if (selfWrites.isEmpty()) return
        val now = nowMs()
        selfWrites.values.removeIf { until -> until < now }
    }

    private fun markRemoteWrite() {
        remoteWritesUntil = nowMs() + REMOTE_ECHO_GRACE_MS
    }

    /** Local change seen by the mirror's FileObserver. [path] is null for the mirror root itself. */
    private fun onLocalChange(context: Context, path: String?) {
        // The sync's own downloads and deletions already ended in a verified baseline.
        if (path != null && isRecentSelfWrite(path)) return
        enqueue(
            context,
            forceLocal = path == null,
            forceRemote = false,
            localPath = path,
        )
    }

    /** Registers observers for the whole mirror before a snapshot exists (service start). */
    private fun watchLocalTree(context: Context, link: Link) {
        synchronized(observerLock) {
            if (!runtimeActive || activeLink != link) return
            runCatching { observersFor(context, link).watchTree() }
        }
    }

    /**
     * Converges the FileObserver set to the directories of a verified local snapshot. Unchanged
     * directories keep their inotify watch, so a sync without directory changes costs no
     * syscalls here.
     */
    private fun updateObservers(context: Context, link: Link, snapshot: Snapshot) {
        synchronized(observerLock) {
            if (!runtimeActive || activeLink != link) return
            val directories = snapshot.entries.mapNotNull { (path, entry) ->
                path.takeIf { entry.kind == WorkspaceSyncPolicy.Kind.Directory }
            }
            val limited = if (directories.size < MAX_FILE_OBSERVERS) {
                directories
            } else {
                // Shallow directories first: they cover the most paths per watch.
                directories.map { it.count { char -> char == '/' } to it }
                    .sortedWith(compareBy<Pair<Int, String>>({ it.first }, { it.second }))
                    .take(MAX_FILE_OBSERVERS - 1)
                    .map { it.second }
            }
            // Watches only speed up detection; the periodic scan still covers a failure here, so
            // it must not turn an already verified sync into a failed one.
            runCatching { observersFor(context, link).update(limited) }
        }
    }

    /** Returns the observers for [link], replacing those of any previous link. */
    private fun observersFor(context: Context, link: Link): RecursiveWorkspaceObserver {
        fileObserver?.takeIf { observedLink == link }?.let { return it }
        clearObserversLocked(context)
        val observer = RecursiveWorkspaceObserver(localRoot(context, link)) { path ->
            onLocalChange(context, path)
        }
        fileObserver = observer
        observedLink = link
        contentObserver = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                // Providers echo Omochi's own uploads and deletions, which the sync has already
                // verified. Re-hashing the whole SAF tree for each echo is the costliest thing
                // this loop can do, so only notifications outside that window force it.
                if (nowMs() <= remoteWritesUntil) return
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
        return observer
    }

    private fun clearObservers() {
        synchronized(observerLock) {
            appContext?.let(::clearObserversLocked) ?: run {
                fileObserver?.stop()
                fileObserver = null
                contentObserver = null
                observedLink = null
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
        observedLink = null
    }

    // The File overload was added in API 29; the path overload keeps API 26-28 support.
    @Suppress("DEPRECATION")
    private class RecursiveWorkspaceObserver(
        private val root: File,
        private val onChanged: (String?) -> Unit,
    ) {
        private inner class DirectoryObserver(
            val relativePath: String,
            absolutePath: String,
        ) : FileObserver(absolutePath, FILE_OBSERVER_MASK) {
            /** Set once the watched directory itself was deleted or moved away. */
            @Volatile var detached = false

            override fun onEvent(event: Int, path: String?) {
                if (event and (FileObserver.DELETE_SELF or FileObserver.MOVE_SELF) != 0) detached = true
                if (path != null && isExcludedName(path)) return
                val relative = when {
                    path == null -> relativePath.takeIf { it.isNotEmpty() }
                    relativePath.isEmpty() -> path
                    else -> "$relativePath/$path"
                }
                onChanged(relative)
            }
        }

        /** Relative directory ("" is the root) to its watch, in registration order. */
        private val observers = linkedMapOf<String, DirectoryObserver>()

        /** Watches the tree as found on disk, for use before any snapshot exists. */
        fun watchTree() {
            val directories = root.walkTopDown()
                .onEnter { directory ->
                    !isExcludedName(directory.name) && !Files.isSymbolicLink(directory.toPath())
                }
                .filter { it.isDirectory }
                .take(MAX_FILE_OBSERVERS)
                .map { directory ->
                    directory.relativeTo(root).invariantSeparatorsPath.takeUnless { it == "." }.orEmpty()
                }
                .filter { it.isNotEmpty() }
                .toList()
            update(directories)
        }

        /**
         * Watches the root plus [directories] and nothing else. Watches that still match are
         * kept. Stale and detached ones are stopped before new ones start: a moved directory
         * keeps its inode, and a new watch on it would share the same inotify descriptor that
         * stopping the old watch then removes.
         */
        fun update(directories: List<String>) {
            val desired = LinkedHashSet<String>(directories.size + 1)
            desired += ""
            for (directory in directories) {
                if (desired.size >= MAX_FILE_OBSERVERS) break
                desired += directory
            }
            val iterator = observers.values.iterator()
            while (iterator.hasNext()) {
                val observer = iterator.next()
                if (observer.detached || observer.relativePath !in desired) {
                    observer.stopWatching()
                    iterator.remove()
                }
            }
            desired.forEach { relative ->
                if (relative in observers) return@forEach
                val directory = if (relative.isEmpty()) root else File(root, relative)
                if (!directory.isDirectory) return@forEach
                observers[relative] = DirectoryObserver(relative, directory.absolutePath).also {
                    it.startWatching()
                }
            }
        }

        fun stop() {
            observers.values.forEach(FileObserver::stopWatching)
            observers.clear()
        }
    }

    private val CHILD_PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )
    private val ROOT_PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_FLAGS,
    )
    private val NAME_PROJECTION = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)

    private const val PREFERENCES = "omochi-workspace-sync-v1"
    private const val KEY_TREE_URI = "tree-uri"
    private const val KEY_LABEL = "label"
    private const val KEY_LOCAL_RELATIVE = "local-relative"
    private const val KEY_LAST_SYNC = "last-sync"
    private const val BASELINE_VERSION = 1
    private const val LOCAL_DEBOUNCE_MS = 450L
    private const val SELF_EVENT_GRACE_MS = 2_000L
    private const val REMOTE_ECHO_GRACE_MS = 5_000L
    private const val MAX_TRACKED_ATTEMPTS = 1_000
    private const val PROGRESS_INTERVAL_MS = 200L
    private const val FINAL_VERIFY_ATTEMPTS = 3
    private const val FINAL_VERIFY_DELAY_MS = 250L
    private const val COPY_BUFFER_SIZE = 128 * 1024
    private const val MAX_ENTRIES = 50_000
    private const val MAX_DEPTH = 64
    private const val MAX_SEGMENT_BYTES = 240
    private const val MAX_FILE_OBSERVERS = 2_048
    private const val HEX_DIGITS = "0123456789abcdef"
    private const val FILE_OBSERVER_MASK =
        FileObserver.CLOSE_WRITE or FileObserver.CREATE or FileObserver.DELETE or
            FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.DELETE_SELF or
            FileObserver.MOVE_SELF
}
