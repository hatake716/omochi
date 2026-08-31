package io.github.hatake716.omochi

/**
 * Pure conflict policy for the SAF workspace mirror.
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
        val allPaths = (baseline.keys + local.keys + remote.keys)
            .distinct()
            .sortedWith(compareBy<String>({ depth(it) }, { it }))
        val blockedTrees = mutableListOf<String>()
        val actions = mutableListOf<Action>()

        allPaths.forEach { path ->
            if (blockedTrees.any { isDescendant(path, it) }) return@forEach

            val previous = baseline[path]
            val localNow = local[path]
            val remoteNow = remote[path]
            val action = decide(
                path = path,
                previous = previous,
                localNow = localNow,
                remoteNow = remoteNow,
                baseline = baseline,
                local = local,
                remote = remote,
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
        baseline: Map<String, Entry>,
        local: Map<String, Entry>,
        remote: Map<String, Entry>,
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
            val remoteChanged = subtreeChanged(remote, baseline, path)
            return if (remoteChanged) Action.Download(path) else Action.DeleteRemote(path)
        }
        if (remoteNow == null) {
            val localChanged = subtreeChanged(local, baseline, path)
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

    private fun subtreeChanged(
        current: Map<String, Entry>,
        baseline: Map<String, Entry>,
        root: String,
    ): Boolean {
        val currentTree = current.filterKeys { it == root || isDescendant(it, root) }
        val baselineTree = baseline.filterKeys { it == root || isDescendant(it, root) }
        return currentTree != baselineTree
    }

    private fun entryOrSubtreeChanged(
        current: Map<String, Entry>,
        baseline: Map<String, Entry>,
        path: String,
        currentEntry: Entry,
        previousEntry: Entry,
    ): Boolean {
        if (currentEntry != previousEntry) return true
        return if (currentEntry.kind == Kind.Directory || previousEntry.kind == Kind.Directory) {
            subtreeChanged(current, baseline, path)
        } else {
            false
        }
    }

    private fun isDescendant(path: String, parent: String): Boolean =
        path.length > parent.length && path.startsWith("$parent/")

    private fun depth(path: String): Int = path.count { it == '/' }
}
