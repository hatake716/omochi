package io.github.hatake716.omochi

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Persists and validates the folder that code-server opens as the active workspace.
 *
 * A guest path is never converted into an arbitrary host path. Every accepted value
 * must stay below [GUEST_ROOT], which is the only writable tree bound into PRoot.
 *
 * Everything except the pure helpers touches the file system. Call it off the main
 * thread; listing a large folder such as node_modules can take noticeable time.
 */
object WorkspaceSession {
    const val GUEST_ROOT = "/workspace"

    data class Folder(
        val guestPath: String,
        val displayName: String,
        val hasChildren: Boolean,
    )

    private const val PREFERENCES = "omochi-workspace-session"
    private const val SELECTED_FOLDER = "selected-guest-folder-v1"

    fun selectedGuestFolder(context: Context): String {
        val saved = preferences(context).getString(SELECTED_FOLDER, null)
        return existingFolderIn(workspaceRoot(context), saved) ?: GUEST_ROOT
    }

    /**
     * Persists an explicit user choice. This is followed by a code-server restart that
     * reads the value back, so it is committed before returning.
     */
    fun selectFolder(context: Context, guestPath: String): Result<Folder> = runCatching {
        val folder = resolveFolderIn(workspaceRoot(context), guestPath)
        val preferences = preferences(context)
        if (folderToPersist(preferences.getString(SELECTED_FOLDER, null), folder.guestPath) != null) {
            preferences.edit(commit = true) { putString(SELECTED_FOLDER, folder.guestPath) }
        }
        folder
    }

    /**
     * Persist a folder selected by Code - OSS itself after a trusted navigation and
     * return the folder that is now selected, or null when the URL names none.
     *
     * Navigations inside the workbench repeat the same folder most of the time, so an
     * unchanged value costs neither file system access nor a preferences write. The
     * next server start reads the value in-process, where apply() is already visible.
     */
    fun captureFolderFromUrl(context: Context, url: String): String? {
        val candidate = folderFromWorkbenchUrl(url) ?: return null
        val preferences = preferences(context)
        val changed = folderToPersist(preferences.getString(SELECTED_FOLDER, null), candidate)
            ?: return candidate
        val existing = existingFolderIn(workspaceRoot(context), changed) ?: return null
        preferences.edit { putString(SELECTED_FOLDER, existing) }
        return existing
    }

    fun resolveFolder(context: Context, guestPath: String?): Result<Folder> = runCatching {
        resolveFolderIn(workspaceRoot(context), guestPath)
    }

    fun listFolders(context: Context, parentGuestPath: String): Result<List<Folder>> = runCatching {
        listFoldersIn(workspaceRoot(context), parentGuestPath)
    }

    fun createFolder(
        context: Context,
        parentGuestPath: String,
        requestedName: String,
    ): Result<Folder> = runCatching {
        createFolderIn(workspaceRoot(context), parentGuestPath, requestedName)
    }

    fun parentGuestFolder(guestPath: String): String? {
        val normalized = normalizeGuestFolder(guestPath) ?: return null
        if (normalized == GUEST_ROOT) return null
        return normalized.substringBeforeLast('/').ifBlank { GUEST_ROOT }
    }

    internal fun normalizeGuestFolder(candidate: String?): String? {
        if (candidate.isNullOrBlank() || '\u0000' in candidate || '\\' in candidate) return null
        val normalized = runCatching {
            Paths.get(candidate).normalize().toString().replace(File.separatorChar, '/')
        }.getOrNull() ?: return null
        if (normalized != GUEST_ROOT && !normalized.startsWith("$GUEST_ROOT/")) return null
        return normalized.trimEnd('/').ifBlank { GUEST_ROOT }
    }

    /**
     * The value to store when [candidate] is captured while [saved] is stored, or null
     * when nothing has to be written. A missing value means [GUEST_ROOT].
     */
    internal fun folderToPersist(saved: String?, candidate: String?): String? {
        val normalized = normalizeGuestFolder(candidate) ?: return null
        if (normalized == saved) return null
        if (saved == null && normalized == GUEST_ROOT) return null
        return normalized
    }

    internal fun folderFromWorkbenchUrl(url: String): String? = runCatching {
        val uri = URI(url)
        val rawValue = uri.rawQuery.orEmpty()
            .split('&')
            .firstNotNullOfOrNull { item ->
                val separator = item.indexOf('=')
                if (separator <= 0) return@firstNotNullOfOrNull null
                val key = URLDecoder.decode(item.substring(0, separator), StandardCharsets.UTF_8.name())
                if (key != "folder") return@firstNotNullOfOrNull null
                URLDecoder.decode(item.substring(separator + 1), StandardCharsets.UTF_8.name())
            }
        normalizeGuestFolder(rawValue)
    }.getOrNull()

    internal fun buildWorkbenchUrl(port: Int, guestPath: String): String {
        require(port in 1024..65535) { "Invalid loopback port: $port" }
        val normalized = requireNotNull(normalizeGuestFolder(guestPath)) {
            "Invalid workspace folder: $guestPath"
        }
        return URI("http", null, "127.0.0.1", port, "/", "folder=$normalized", null)
            .toASCIIString()
    }

    internal fun isValidFolderName(name: String): Boolean =
        name.isNotBlank() &&
            name != "." &&
            name != ".." &&
            name.none { it == '/' || it == '\\' || it == '\u0000' || it.code < 0x20 }

    /** [root] must be canonical, as returned by [workspaceRoot]. */
    internal fun resolveFolderIn(root: File, guestPath: String?): Folder {
        val normalized = normalizeGuestFolder(guestPath)
            ?: error("作業フォルダーは $GUEST_ROOT 内から選択してください。")
        val host = hostFolder(root, normalized)
        check(host.isDirectory) { "フォルダーが存在しません: $normalized" }
        return Folder(
            guestPath = normalized,
            displayName = if (normalized == GUEST_ROOT) "workspace" else host.name,
            hasChildren = hasChildFolder(host),
        )
    }

    internal fun listFoldersIn(root: File, parentGuestPath: String): List<Folder> {
        val parent = resolveFolderIn(root, parentGuestPath)
        val parentHost = hostFolder(root, parent.guestPath)
        return parentHost.listFiles().orEmpty()
            .asSequence()
            .filter { it.isDirectory }
            .mapNotNull { child ->
                val guestPath = guestPathFor(root, child) ?: return@mapNotNull null
                Folder(
                    guestPath = guestPath,
                    displayName = child.name,
                    hasChildren = hasChildFolder(child),
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
            .toList()
    }

    internal fun createFolderIn(root: File, parentGuestPath: String, requestedName: String): Folder {
        val parent = resolveFolderIn(root, parentGuestPath)
        val name = requestedName.trim()
        require(isValidFolderName(name)) {
            "フォルダー名には /、\\、制御文字、.、.. を使用できません。"
        }
        val host = File(hostFolder(root, parent.guestPath), name)
        check(!host.exists()) { "同名のファイルまたはフォルダーが既にあります。" }
        check(host.mkdir()) { "フォルダーを作成できませんでした。" }
        return resolveFolderIn(root, guestPathFor(root, host))
    }

    /**
     * Whether [directory] contains at least one folder. The picker only needs a yes/no
     * per row, so stop at the first hit instead of materialising every child as
     * listFiles() does; that matters for rows such as node_modules.
     */
    internal fun hasChildFolder(directory: File): Boolean = runCatching {
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            entries.any { Files.isDirectory(it) }
        }
    }.getOrDefault(false)

    /** Validated guest path of an existing folder, without listing its contents. */
    private fun existingFolderIn(root: File, guestPath: String?): String? {
        val normalized = normalizeGuestFolder(guestPath) ?: return null
        val host = runCatching { hostFolder(root, normalized) }.getOrNull() ?: return null
        return normalized.takeIf { host.isDirectory }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Canonical host directory behind [GUEST_ROOT]. Resolve it once per operation. */
    private fun workspaceRoot(context: Context): File =
        OmochiRuntime.workspaceDir(context).apply { mkdirs() }.canonicalFile

    private fun hostFolder(root: File, guestPath: String): File {
        val relative = guestPath.removePrefix(GUEST_ROOT).trimStart('/')
        val target = if (relative.isEmpty()) root else File(root, relative).canonicalFile
        check(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
            "作業領域外のフォルダーは開けません。"
        }
        return target
    }

    private fun guestPathFor(root: File, host: File): String? {
        val candidate = host.absoluteFile
        if (candidate.path != root.path && !candidate.path.startsWith(root.path + File.separator)) {
            return null
        }
        val canonicalCandidate = host.canonicalFile
        if (canonicalCandidate.path != root.path &&
            !canonicalCandidate.path.startsWith(root.path + File.separator)
        ) {
            return null
        }
        val relative = candidate.relativeTo(root).invariantSeparatorsPath
        return if (relative.isBlank() || relative == ".") GUEST_ROOT else "$GUEST_ROOT/$relative"
    }

}
