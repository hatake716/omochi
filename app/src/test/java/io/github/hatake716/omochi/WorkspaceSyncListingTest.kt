package io.github.hatake716.omochi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class WorkspaceSyncListingTest {
    private fun row(
        name: String?,
        mimeType: String? = "text/plain",
        documentId: String? = "id:$name",
        size: Long? = 1L,
        modified: Long? = 2L,
    ) = WorkspaceSyncPolicy.ChildRow(documentId, name, mimeType, size, modified)

    private fun listing(vararg rows: WorkspaceSyncPolicy.ChildRow, loading: Boolean = false, error: Boolean = false) =
        WorkspaceSyncPolicy.ChildListing(rows.toList(), loading, error)

    @Test
    fun rowsBecomeChildrenSortedCaseInsensitively() {
        val children = WorkspaceSyncPolicy.children(
            listing(
                row("b.kt", size = 10L, modified = 20L),
                row("Src", mimeType = WorkspaceSyncPolicy.DIRECTORY_MIME_TYPE, size = null),
                row("a.kt"),
            )
        )

        assertEquals(listOf("a.kt", "b.kt", "Src"), children.map { it.name })
        assertEquals(
            WorkspaceSyncPolicy.Child("id:b.kt", "b.kt", WorkspaceSyncPolicy.Kind.File, 10L, 20L),
            children[1],
        )
        assertEquals(WorkspaceSyncPolicy.Kind.Directory, children[2].kind)
        assertEquals(0L, children[2].size)
    }

    @Test
    fun missingSizeAndTimeReadAsZeroLikeDocumentFile() {
        val child = WorkspaceSyncPolicy.children(listing(row("x", size = null, modified = null))).single()

        assertEquals(0L, child.size)
        assertEquals(0L, child.modified)
    }

    @Test
    fun documentsWithoutAMimeTypeAreNeitherFileNorDirectory() {
        assertNull(WorkspaceSyncPolicy.kindOf(null))
        assertNull(WorkspaceSyncPolicy.kindOf(""))
        assertEquals(WorkspaceSyncPolicy.Kind.File, WorkspaceSyncPolicy.kindOf("application/octet-stream"))
        assertEquals(
            WorkspaceSyncPolicy.Kind.Directory,
            WorkspaceSyncPolicy.kindOf(WorkspaceSyncPolicy.DIRECTORY_MIME_TYPE),
        )
    }

    @Test
    fun anEmptyListingIsEmptyNotAnError() {
        assertEquals(emptyList<WorkspaceSyncPolicy.Child>(), WorkspaceSyncPolicy.children(listing()))
    }

    @Test
    fun incompleteListingsFailInsteadOfLookingLikeDeletions() {
        // A provider still loading (or reporting an error) may return only some children, or
        // none. Either must abort the sync rather than plan the missing ones as deleted.
        assertThrows(IOException::class.java) {
            WorkspaceSyncPolicy.children(listing(row("a.kt"), loading = true))
        }
        assertThrows(IOException::class.java) {
            WorkspaceSyncPolicy.children(listing(loading = true))
        }
        assertThrows(IOException::class.java) {
            WorkspaceSyncPolicy.children(listing(row("a.kt"), error = true))
        }
    }

    @Test
    fun rowsWithoutANameOrIdentifierFail() {
        val unnamed = assertThrows(IOException::class.java) {
            WorkspaceSyncPolicy.children(listing(row("a.kt"), row(null)))
        }
        assertEquals("名前のない端末ファイルは同期できません。", unnamed.message)
        assertThrows(IOException::class.java) {
            WorkspaceSyncPolicy.children(listing(row("a.kt", documentId = null)))
        }
        assertThrows(IOException::class.java) {
            WorkspaceSyncPolicy.children(listing(row("a.kt", documentId = "")))
        }
    }
}
