package io.github.hatake716.omochi

import java.io.IOException
import java.util.Locale

/**
 * Pure rules for the SAF workspace mirror: conflict planning, polling cadence, and validation
 * of DocumentsContract child listings.
 *
 * Android's Storage Access Framework exposes content URIs rather than paths that PRoot can
 * open. [WorkspaceSyncManager] therefore snapshots the selected SAF tree and its private
 * workspace mirror, resolves file digests, and gives those snapshots to this planner.
 */
internal object WorkspaceSyncPolicy {
    enum class Kind { File, Directory }

    data class Entry(
        val kind: Kind,
        val digest: String? = null,
    ) {
        init {
            require(kind != Kind.File || !digest.isNullOrBlank()) {
                "Files require a content digest"
            }
        }
    }

    sealed interface Action {
        val path: String

        /** Make the SAF entry recursively match the private workspace entry. */
        data class Upload(override val path: String) : Action

        /** Make the private workspace entry recursively match the SAF entry. */
        data class Download(override val path: String) : Action

        /** Propagate an unambiguous private-workspace deletion to SAF. */
        data class DeleteRemote(override val path: String) : Action

        /** Propagate an unambiguous SAF deletion to the private workspace. */
        data class DeleteLocal(override val path: String) : Action

        /** Preserve the remote version under a conflict name, then keep the local version. */
        data class PreserveConflict(override val path: String) : Action
    }

    /**
     * Plans a converging, loss-averse bidirectional sync.
     *
     * Directory entries deliberately do not carry a tree digest. Their descendants are planned
     * independently so edits to different files on the two sides do not create a directory-wide
     * conflict. A subtree comparison is only used when one whole directory disappeared: if the
     * surviving tree changed, the edit wins over the concurrent deletion.
     */
    fun plan(
        baseline: Map<String, Entry>,
        local: Map<String, Entry>,
        remote: Map<String, Entry>,
    ): List<Action> {
        // When both sides agree on every path, no path can produce an action. This is the
        // common idle case, and it skips sorting the whole tree.
        if (local == remote) return emptyList()

        val baselineTree = Tree(baseline)
        val localTree = Tree(local)
        val remoteTree = Tree(remote)
        val allPaths = HashSet<String>(baseline.size + local.size + remote.size).apply {
            addAll(baseline.keys)
            addAll(local.keys)
            addAll(remote.keys)
        }
            .map { path -> depth(path) to path }
            .sortedWith(compareBy<Pair<Int, String>>({ it.first }, { it.second }))
            .map { it.second }
        val blockedTrees = hashSetOf<String>()
        val actions = mutableListOf<Action>()

        allPaths.forEach { path ->
            if (hasAncestorIn(path, blockedTrees)) return@forEach

            val previous = baseline[path]
            val localNow = local[path]
            val remoteNow = remote[path]
            val action = decide(
                path = path,
                previous = previous,
                localNow = localNow,
                remoteNow = remoteNow,
                baseline = baselineTree,
                local = localTree,
                remote = remoteTree,
            ) ?: return@forEach

            actions += action
            if (listOfNotNull(previous, localNow, remoteNow).any { it.kind == Kind.Directory }) {
                blockedTrees += path
            }
        }
        return actions
    }

    private fun decide(
        path: String,
        previous: Entry?,
        localNow: Entry?,
        remoteNow: Entry?,
        baseline: Tree,
        local: Tree,
        remote: Tree,
    ): Action? {
        if (previous == null) {
            return when {
                localNow == null && remoteNow == null -> null
                localNow == null -> Action.Download(path)
                remoteNow == null -> Action.Upload(path)
                localNow == remoteNow -> null
                localNow.kind == Kind.Directory && remoteNow.kind == Kind.Directory -> null
                else -> Action.PreserveConflict(path)
            }
        }

        if (localNow == null && remoteNow == null) return null
        if (localNow == null) {
            val remoteChanged = !remote.subtreeEquals(baseline, path)
            return if (remoteChanged) Action.Download(path) else Action.DeleteRemote(path)
        }
        if (remoteNow == null) {
            val localChanged = !local.subtreeEquals(baseline, path)
            return if (localChanged) Action.Upload(path) else Action.DeleteLocal(path)
        }

        if (localNow == remoteNow) return null
        if (localNow.kind == Kind.Directory && remoteNow.kind == Kind.Directory) return null

        val localChanged = entryOrSubtreeChanged(local, baseline, path, localNow, previous)
        val remoteChanged = entryOrSubtreeChanged(remote, baseline, path, remoteNow, previous)
        return when {
            localChanged && !remoteChanged -> Action.Upload(path)
            remoteChanged && !localChanged -> Action.Download(path)
            else -> Action.PreserveConflict(path)
        }
    }

    private fun entryOrSubtreeChanged(
        current: Tree,
        baseline: Tree,
        path: String,
        currentEntry: Entry,
        previousEntry: Entry,
    ): Boolean {
        if (currentEntry != previousEntry) return true
        return if (currentEntry.kind == Kind.Directory || previousEntry.kind == Kind.Directory) {
            !current.subtreeEquals(baseline, path)
        } else {
            false
        }
    }

    /**
     * One side of a plan with its keys sorted once, so a subtree is a contiguous key range.
     *
     * Comparing a deleted subtree costs O(log n + k) instead of filtering every entry, which kept
     * large deletions quadratic.
     */
    private class Tree(private val entries: Map<String, Entry>) {
        private val sortedKeys: Array<String> by lazy(LazyThreadSafetyMode.NONE) {
            entries.keys.toTypedArray().apply { sort() }
        }

        /** True when [root] and all of its descendants are identical in both trees. */
        fun subtreeEquals(other: Tree, root: String): Boolean {
            if (entries[root] != other.entries[root]) return false
            // Keys that start with "$root/" sort at or after "$root/" and before "$root0",
            // because '0' is the character right after '/'.
            val lower = "$root/"
            val upper = "${root}0"
            val start = lowerBound(lower)
            val count = lowerBound(upper) - start
            val otherStart = other.lowerBound(lower)
            if (other.lowerBound(upper) - otherStart != count) return false
            for (offset in 0 until count) {
                val key = sortedKeys[start + offset]
                if (key != other.sortedKeys[otherStart + offset]) return false
                if (entries[key] != other.entries[key]) return false
            }
            return true
        }

        private fun lowerBound(key: String): Int {
            var low = 0
            var high = sortedKeys.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (sortedKeys[middle] < key) low = middle + 1 else high = middle
            }
            return low
        }
    }

    /**
     * True when [path] is one of [roots] or lies below one. An empty root stands for the whole
     * tree, matching how an unnamed FileObserver event forces a full local verification.
     */
    fun isWithin(path: String, roots: Set<String>): Boolean =
        roots.isNotEmpty() && isWithin(path) { it in roots }

    /** Like [isWithin], but asks [isRoot] for "", [path] itself, and each ancestor of [path]. */
    inline fun isWithin(path: String, isRoot: (String) -> Boolean): Boolean {
        if (isRoot("") || isRoot(path)) return true
        var slash = path.indexOf('/')
        while (slash >= 0) {
            if (isRoot(path.substring(0, slash))) return true
            slash = path.indexOf('/', slash + 1)
        }
        return false
    }

    /** True when a proper ancestor of [path] is in [roots] ("a" for "a/b", not "a" itself). */
    private fun hasAncestorIn(path: String, roots: Set<String>): Boolean {
        if (roots.isEmpty()) return false
        var slash = path.indexOf('/')
        while (slash >= 0) {
            if (path.substring(0, slash) in roots) return true
            slash = path.indexOf('/', slash + 1)
        }
        return false
    }

    private fun depth(path: String): Int = path.count { it == '/' }

    // Polling cadence -----------------------------------------------------------------------

    /** Scan interval right after activity, when follow-up edits are likely. */
    const val ACTIVE_SCAN_INTERVAL_MS = 3_000L

    /** Longest idle scan interval. Observer events and explicit requests still wake it at once. */
    const val MAX_IDLE_SCAN_INTERVAL_MS = 30_000L

    /** Quiet scans that keep [ACTIVE_SCAN_INTERVAL_MS] before the interval starts doubling. */
    const val ACTIVE_QUIET_SCANS = 5

    /** First retry delay after a failed sync; it doubles up to [MAX_RETRY_DELAY_MS]. */
    const val FIRST_RETRY_DELAY_MS = 3_000L
    const val MAX_RETRY_DELAY_MS = 60_000L

    /** Interval between forced re-hashes of every SAF file, for providers without notifications. */
    const val FULL_VERIFY_INTERVAL_MS = 5 * 60_000L

    /**
     * Delay before the next periodic scan after [quietScans] consecutive syncs found nothing:
     * 3 s for the first [ACTIVE_QUIET_SCANS], then 6, 12, 24 and at most 30 s.
     */
    fun idleScanDelayMs(quietScans: Int): Long {
        if (quietScans < ACTIVE_QUIET_SCANS) return ACTIVE_SCAN_INTERVAL_MS
        return doubled(ACTIVE_SCAN_INTERVAL_MS, quietScans - ACTIVE_QUIET_SCANS + 1, MAX_IDLE_SCAN_INTERVAL_MS)
    }

    /** Exponential retry delay after [consecutiveFailures] failures: 3, 6, 12, … at most 60 s. */
    fun retryDelayMs(consecutiveFailures: Int): Long {
        if (consecutiveFailures <= 0) return 0L
        return doubled(FIRST_RETRY_DELAY_MS, consecutiveFailures - 1, MAX_RETRY_DELAY_MS)
    }

    /**
     * Retry delay for a sync requested by an observed change while failing. It is capped at the
     * active interval so a fix by the user (or a transient provider error) is picked up quickly,
     * while a burst of edits against a persistent error cannot turn into a busy loop.
     */
    fun changeRetryDelayMs(consecutiveFailures: Int): Long =
        minOf(retryDelayMs(consecutiveFailures), ACTIVE_SCAN_INTERVAL_MS)

    /** Delay before the next periodic scan: failure backoff wins over the idle cadence. */
    fun nextScanDelayMs(quietScans: Int, consecutiveFailures: Int): Long =
        if (consecutiveFailures > 0) retryDelayMs(consecutiveFailures) else idleScanDelayMs(quietScans)

    /** True when the SAF side should be re-hashed; a clock that went backwards also counts. */
    fun fullVerificationDue(nowMs: Long, lastVerifiedAtMs: Long): Boolean =
        nowMs < lastVerifiedAtMs || nowMs - lastVerifiedAtMs >= FULL_VERIFY_INTERVAL_MS

    private fun doubled(base: Long, doublings: Int, cap: Long): Long =
        (base shl doublings.coerceIn(0, 30)).coerceAtMost(cap)

    // DocumentsContract child listings ------------------------------------------------------

    /** DocumentsContract.Document.MIME_TYPE_DIR. */
    const val DIRECTORY_MIME_TYPE = "vnd.android.document/directory"

    /** One row of a child-documents query. A null field means the provider returned null. */
    data class ChildRow(
        val documentId: String?,
        val displayName: String?,
        val mimeType: String?,
        val size: Long?,
        val lastModified: Long?,
    )

    /** A child-documents cursor: its rows plus the EXTRA_LOADING / EXTRA_ERROR extras. */
    data class ChildListing(
        val rows: List<ChildRow>,
        val loading: Boolean,
        val error: Boolean,
    )

    /** A validated child. [kind] is null for a document without a MIME type. */
    data class Child(
        val documentId: String,
        val name: String,
        val kind: Kind?,
        val size: Long,
        val modified: Long,
    )

    /**
     * Validates one child listing and returns it sorted case-insensitively by name.
     *
     * A listing that is still loading or reports an error is incomplete. It throws instead of
     * returning the rows it has, because missing children would otherwise be planned as
     * deletions. Missing size or modification time read as 0, as androidx DocumentFile does.
     */
    fun children(listing: ChildListing): List<Child> {
        if (listing.loading) {
            throw IOException("端末フォルダの一覧をまだ読み込み中です。読み込み後に再同期します。")
        }
        if (listing.error) {
            throw IOException("端末フォルダの一覧を取得できませんでした。後で再同期します。")
        }
        return listing.rows
            .map { row ->
                val name = row.displayName ?: throw IOException("名前のない端末ファイルは同期できません。")
                val documentId = row.documentId?.takeIf { it.isNotEmpty() }
                    ?: throw IOException("端末フォルダの一覧が不完全です: $name")
                name.lowercase(Locale.ROOT) to Child(
                    documentId = documentId,
                    name = name,
                    kind = kindOf(row.mimeType),
                    size = row.size ?: 0L,
                    modified = row.lastModified ?: 0L,
                )
            }
            .sortedBy { it.first }
            .map { it.second }
    }

    /** Mirrors DocumentFile.isDirectory / isFile: a document without a MIME type is neither. */
    fun kindOf(mimeType: String?): Kind? = when {
        mimeType == DIRECTORY_MIME_TYPE -> Kind.Directory
        mimeType.isNullOrEmpty() -> null
        else -> Kind.File
    }
}
