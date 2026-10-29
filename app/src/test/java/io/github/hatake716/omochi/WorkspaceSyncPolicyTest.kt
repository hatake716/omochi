package io.github.hatake716.omochi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

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

    @Test
    fun identicalSidesNeedNoActionWhateverTheBaseline() {
        val both = mapOf("src" to directory, "src/main.kt" to file("same"))

        assertEquals(
            emptyList<WorkspaceSyncPolicy.Action>(),
            WorkspaceSyncPolicy.plan(
                baseline = mapOf("gone.kt" to file("old"), "src/main.kt" to file("older")),
                local = both,
                remote = both,
            ),
        )
    }

    @Test
    fun deletedSubtreeIgnoresSiblingsThatSortNextToIt() {
        // "src-old", "src.bak" and "src0" sort right around "src/…" but are not inside "src".
        val baseline = mapOf(
            "src" to directory,
            "src/a.kt" to file("a"),
            "src-old" to file("old"),
            "src.bak" to file("bak"),
            "src0" to file("zero"),
        )
        val local = baseline - "src" - "src/a.kt"
        val remote = baseline + ("src-old" to file("old-edited")) + ("src0" to file("zero-edited"))

        assertEquals(
            listOf(
                WorkspaceSyncPolicy.Action.DeleteRemote("src"),
                WorkspaceSyncPolicy.Action.Download("src-old"),
                WorkspaceSyncPolicy.Action.Download("src0"),
            ),
            WorkspaceSyncPolicy.plan(baseline, local, remote),
        )
    }

    @Test
    fun anyChangeInsideADeletedSubtreeKeepsIt() {
        val baseline = mapOf(
            "src" to directory,
            "src/a.kt" to file("a"),
            "src/deep" to directory,
            "src/deep/b.kt" to file("b"),
        )
        val local = mapOf<String, WorkspaceSyncPolicy.Entry>()

        listOf(
            baseline + ("src/deep/b.kt" to file("edited")),
            baseline + ("src/deep/new.kt" to file("new")),
            baseline - "src/deep/b.kt",
            baseline - "src/deep" - "src/deep/b.kt",
        ).forEach { remote ->
            assertEquals(
                listOf(WorkspaceSyncPolicy.Action.Download("src")),
                WorkspaceSyncPolicy.plan(baseline, local, remote),
            )
        }
        assertEquals(
            listOf(WorkspaceSyncPolicy.Action.DeleteLocal("src")),
            WorkspaceSyncPolicy.plan(baseline, local = baseline, remote = local),
        )
    }

    @Test(timeout = 10_000)
    fun massDeletionIsPlannedPerEntryWithoutScanningTheWholeTree() {
        val baseline = linkedMapOf<String, WorkspaceSyncPolicy.Entry>()
        repeat(20_000) { index -> baseline["file-%05d.txt".format(index)] = file("digest-$index") }
        repeat(200) { dir ->
            baseline["dir-%03d".format(dir)] = directory
            repeat(50) { child -> baseline["dir-%03d/item-%02d".format(dir, child)] = file("d$dir-$child") }
        }
        val deletedFiles = baseline.keys.filter { it.startsWith("file-") }
            .filterIndexed { index, _ -> index % 2 == 0 }
            .toSet()
        val deletedDirectories = (0 until 200 step 2).map { "dir-%03d".format(it) }
        val local = baseline.filterKeys { path ->
            path !in deletedFiles && deletedDirectories.none { path == it || path.startsWith("$it/") }
        }

        val actions = WorkspaceSyncPolicy.plan(baseline, local, remote = baseline)

        val expected = (deletedDirectories + deletedFiles).sorted()
            .map { WorkspaceSyncPolicy.Action.DeleteRemote(it) }
        assertEquals(expected, actions)
    }

    @Test
    fun matchesTheOriginalFullScanPlannerOnRandomTrees() {
        val random = Random(716)
        // Names that sort immediately before, inside and after "a/…".
        val segments = listOf("a", "b", "a-b", "a.b", "a0")
        val digests = listOf("x", "y", "z")

        fun randomTree(): Map<String, WorkspaceSyncPolicy.Entry> {
            val tree = linkedMapOf<String, WorkspaceSyncPolicy.Entry>()
            repeat(random.nextInt(0, 14)) {
                val depth = random.nextInt(1, 4)
                val path = (1..depth).joinToString("/") { segments.random(random) }
                tree[path] = if (random.nextInt(3) == 0) directory else file(digests.random(random))
            }
            return tree
        }

        repeat(5_000) { round ->
            val baseline = randomTree()
            // Derive the sides from the baseline most of the time so deletions and edits of
            // existing subtrees, not only unrelated trees, are exercised.
            fun side(): Map<String, WorkspaceSyncPolicy.Entry> = if (random.nextBoolean()) {
                randomTree()
            } else {
                baseline.filter { random.nextInt(4) != 0 }
                    .mapValues { (_, entry) ->
                        if (entry.kind == WorkspaceSyncPolicy.Kind.File && random.nextInt(4) == 0) {
                            file(digests.random(random))
                        } else {
                            entry
                        }
                    } + randomTree().filter { random.nextInt(3) == 0 }
            }
            val local = side()
            val remote = side()

            assertEquals(
                "round $round: baseline=$baseline local=$local remote=$remote",
                ReferencePlanner.plan(baseline, local, remote),
                WorkspaceSyncPolicy.plan(baseline, local, remote),
            )
        }
    }

    @Test
    fun isWithinMatchesPrefixSemantics() {
        val roots = setOf("src", "docs/api")

        assertTrue(WorkspaceSyncPolicy.isWithin("src", roots))
        assertTrue(WorkspaceSyncPolicy.isWithin("src/main.kt", roots))
        assertTrue(WorkspaceSyncPolicy.isWithin("docs/api/index.md", roots))
        assertFalse(WorkspaceSyncPolicy.isWithin("src-old/main.kt", roots))
        assertFalse(WorkspaceSyncPolicy.isWithin("srcs", roots))
        assertFalse(WorkspaceSyncPolicy.isWithin("docs", roots))
        assertFalse(WorkspaceSyncPolicy.isWithin("docs/apis/x", roots))
        assertFalse(WorkspaceSyncPolicy.isWithin("src", emptySet()))
        assertTrue(WorkspaceSyncPolicy.isWithin("anything/at/all", setOf("")))

        val random = Random(42)
        val names = listOf("a", "b", "a-b", "a.b", "a0")
        fun randomPath() = (1..random.nextInt(1, 4)).joinToString("/") { names.random(random) }
        repeat(5_000) {
            val set = (0 until random.nextInt(0, 4)).map { randomPath() }.toSet()
            val path = randomPath()
            val expected = set.any { root -> root.isEmpty() || path == root || path.startsWith("$root/") }
            assertEquals("$path in $set", expected, WorkspaceSyncPolicy.isWithin(path, set))
        }
    }

    /** The planner before the sorted-range subtree comparison, kept as the behavioural oracle. */
    private object ReferencePlanner {
        fun plan(
            baseline: Map<String, WorkspaceSyncPolicy.Entry>,
            local: Map<String, WorkspaceSyncPolicy.Entry>,
            remote: Map<String, WorkspaceSyncPolicy.Entry>,
        ): List<WorkspaceSyncPolicy.Action> {
            val allPaths = (baseline.keys + local.keys + remote.keys)
                .distinct()
                .sortedWith(compareBy<String>({ depth(it) }, { it }))
            val blockedTrees = mutableListOf<String>()
            val actions = mutableListOf<WorkspaceSyncPolicy.Action>()
            allPaths.forEach { path ->
                if (blockedTrees.any { isDescendant(path, it) }) return@forEach
                val previous = baseline[path]
                val localNow = local[path]
                val remoteNow = remote[path]
                val action = decide(path, previous, localNow, remoteNow, baseline, local, remote)
                    ?: return@forEach
                actions += action
                if (listOfNotNull(previous, localNow, remoteNow).any { it.kind == WorkspaceSyncPolicy.Kind.Directory }) {
                    blockedTrees += path
                }
            }
            return actions
        }

        private fun decide(
            path: String,
            previous: WorkspaceSyncPolicy.Entry?,
            localNow: WorkspaceSyncPolicy.Entry?,
            remoteNow: WorkspaceSyncPolicy.Entry?,
            baseline: Map<String, WorkspaceSyncPolicy.Entry>,
            local: Map<String, WorkspaceSyncPolicy.Entry>,
            remote: Map<String, WorkspaceSyncPolicy.Entry>,
        ): WorkspaceSyncPolicy.Action? {
            val directoryKind = WorkspaceSyncPolicy.Kind.Directory
            if (previous == null) {
                return when {
                    localNow == null && remoteNow == null -> null
                    localNow == null -> WorkspaceSyncPolicy.Action.Download(path)
                    remoteNow == null -> WorkspaceSyncPolicy.Action.Upload(path)
                    localNow == remoteNow -> null
                    localNow.kind == directoryKind && remoteNow.kind == directoryKind -> null
                    else -> WorkspaceSyncPolicy.Action.PreserveConflict(path)
                }
            }
            if (localNow == null && remoteNow == null) return null
            if (localNow == null) {
                return if (subtreeChanged(remote, baseline, path)) {
                    WorkspaceSyncPolicy.Action.Download(path)
                } else {
                    WorkspaceSyncPolicy.Action.DeleteRemote(path)
                }
            }
            if (remoteNow == null) {
                return if (subtreeChanged(local, baseline, path)) {
                    WorkspaceSyncPolicy.Action.Upload(path)
                } else {
                    WorkspaceSyncPolicy.Action.DeleteLocal(path)
                }
            }
            if (localNow == remoteNow) return null
            if (localNow.kind == directoryKind && remoteNow.kind == directoryKind) return null
            val localChanged = localNow != previous ||
                ((localNow.kind == directoryKind || previous.kind == directoryKind) && subtreeChanged(local, baseline, path))
            val remoteChanged = remoteNow != previous ||
                ((remoteNow.kind == directoryKind || previous.kind == directoryKind) && subtreeChanged(remote, baseline, path))
            return when {
                localChanged && !remoteChanged -> WorkspaceSyncPolicy.Action.Upload(path)
                remoteChanged && !localChanged -> WorkspaceSyncPolicy.Action.Download(path)
                else -> WorkspaceSyncPolicy.Action.PreserveConflict(path)
            }
        }

        private fun subtreeChanged(
            current: Map<String, WorkspaceSyncPolicy.Entry>,
            baseline: Map<String, WorkspaceSyncPolicy.Entry>,
            root: String,
        ): Boolean =
            current.filterKeys { it == root || isDescendant(it, root) } !=
                baseline.filterKeys { it == root || isDescendant(it, root) }

        private fun isDescendant(path: String, parent: String): Boolean =
            path.length > parent.length && path.startsWith("$parent/")

        private fun depth(path: String): Int = path.count { it == '/' }
    }
}
