package io.github.hatake716.omochi

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceSyncPolicyTest {
    private val directory = WorkspaceSyncPolicy.Entry(WorkspaceSyncPolicy.Kind.Directory)

    private fun file(value: String) = WorkspaceSyncPolicy.Entry(
        WorkspaceSyncPolicy.Kind.File,
        digest = value,
    )

    @Test
    fun initialRemoteTreeIsDownloadedOnce() {
        val actions = WorkspaceSyncPolicy.plan(
            baseline = emptyMap(),
            local = emptyMap(),
            remote = mapOf("project" to directory, "project/main.kt" to file("remote")),
        )

        assertEquals(listOf(WorkspaceSyncPolicy.Action.Download("project")), actions)
    }

    @Test
    fun oneSidedFileEditsFlowToTheOtherSide() {
        val baseline = mapOf("main.kt" to file("old"))

        assertEquals(
            listOf(WorkspaceSyncPolicy.Action.Upload("main.kt")),
            WorkspaceSyncPolicy.plan(
                baseline,
                local = mapOf("main.kt" to file("local")),
                remote = baseline,
            ),
        )
        assertEquals(
            listOf(WorkspaceSyncPolicy.Action.Download("main.kt")),
            WorkspaceSyncPolicy.plan(
                baseline,
                local = baseline,
                remote = mapOf("main.kt" to file("remote")),
            ),
        )
    }

    @Test
    fun concurrentFileEditsPreserveAConflictCopy() {
        val actions = WorkspaceSyncPolicy.plan(
            baseline = mapOf("main.kt" to file("old")),
            local = mapOf("main.kt" to file("local")),
            remote = mapOf("main.kt" to file("remote")),
        )

        assertEquals(listOf(WorkspaceSyncPolicy.Action.PreserveConflict("main.kt")), actions)
    }

    @Test
    fun unchangedDeletionPropagatesButConcurrentEditWins() {
        val baseline = mapOf("notes.md" to file("old"))

        assertEquals(
            listOf(WorkspaceSyncPolicy.Action.DeleteRemote("notes.md")),
            WorkspaceSyncPolicy.plan(baseline, local = emptyMap(), remote = baseline),
        )
        assertEquals(
            listOf(WorkspaceSyncPolicy.Action.Download("notes.md")),
            WorkspaceSyncPolicy.plan(
                baseline,
                local = emptyMap(),
                remote = mapOf("notes.md" to file("edited")),
            ),
        )
    }

    @Test
    fun changedDirectorySurvivesConcurrentDeletion() {
        val baseline = mapOf(
            "src" to directory,
            "src/main.kt" to file("old"),
        )
        val remote = mapOf(
            "src" to directory,
            "src/main.kt" to file("new"),
        )

        assertEquals(
            listOf(WorkspaceSyncPolicy.Action.Download("src")),
            WorkspaceSyncPolicy.plan(baseline, local = emptyMap(), remote = remote),
        )
    }

    @Test
    fun independentEditsInsideOneDirectoryDoNotConflictAtDirectoryLevel() {
        val baseline = mapOf(
            "src" to directory,
            "src/a.kt" to file("a0"),
            "src/b.kt" to file("b0"),
        )
        val local = baseline + ("src/a.kt" to file("a1"))
        val remote = baseline + ("src/b.kt" to file("b1"))

        assertEquals(
            listOf(
                WorkspaceSyncPolicy.Action.Upload("src/a.kt"),
                WorkspaceSyncPolicy.Action.Download("src/b.kt"),
            ),
            WorkspaceSyncPolicy.plan(baseline, local, remote),
        )
    }

    @Test
    fun fileDirectoryTypeConflictBlocksUnsafeDescendantActions() {
        val actions = WorkspaceSyncPolicy.plan(
            baseline = mapOf("item" to file("old")),
            local = mapOf("item" to directory, "item/child" to file("local")),
            remote = mapOf("item" to file("remote")),
        )

        assertEquals(listOf(WorkspaceSyncPolicy.Action.PreserveConflict("item")), actions)
    }

    @Test
    fun typeReplacementConflictsWithAnEditInsideTheOtherDirectory() {
        val baseline = mapOf(
            "item" to directory,
            "item/child" to file("old"),
        )
        val local = mapOf("item" to file("local-replacement"))
        val remote = mapOf(
            "item" to directory,
            "item/child" to file("remote-edit"),
        )

        assertEquals(
            listOf(WorkspaceSyncPolicy.Action.PreserveConflict("item")),
            WorkspaceSyncPolicy.plan(baseline, local, remote),
        )
    }
}
