package io.github.hatake716.omochi

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Paths

/**
 * Persists and validates the folder that code-server opens as the active workspace.
 *
 * A guest path is never converted into an arbitrary host path. Every accepted value
 * must stay below [GUEST_ROOT], which is the only writable tree bound into PRoot.
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
        val saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(SELECTED_FOLDER, null)
        return resolveFolder(context, saved).getOrNull()?.guestPath ?: GUEST_ROOT
    }

    fun selectFolder(context: Context, guestPath: String): Result<Folder> = runCatching {
        val folder = resolveFolder(context, guestPath).getOrThrow()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit(commit = true) {
            putString(SELECTED_FOLDER, folder.guestPath)
        }
        folder
    }

    /** Persist a folder selected by Code - OSS itself after a trusted navigation. */
    fun captureFolderFromUrl(context: Context, url: String): Folder? {
        val guestPath = folderFromWorkbenchUrl(url) ?: return null
        return selectFolder(context, guestPath).getOrNull()
    }

    fun resolveFolder(context: Context, guestPath: String?): Result<Folder> = runCatching {
        val normalized = normalizeGuestFolder(guestPath)
            ?: error("作業フォルダーは $GUEST_ROOT 内から選択してください。")
        val host = hostFolder(context, normalized)
        check(host.isDirectory) { "フォルダーが存在しません: $normalized" }
        Folder(
            guestPath = normalized,
            displayName = if (normalized == GUEST_ROOT) "workspace" else host.name,
            hasChildren = host.listFiles().orEmpty().any { it.isDirectory },
        )
    }

    fun listFolders(context: Context, parentGuestPath: String): Result<List<Folder>> = runCatching {
        val parent = resolveFolder(context, parentGuestPath).getOrThrow()
        val parentHost = hostFolder(context, parent.guestPath)
        parentHost.listFiles().orEmpty()
            .asSequence()
            .filter { it.isDirectory }
            .mapNotNull { child ->
                val guestPath = guestPathFor(context, child) ?: return@mapNotNull null
                Folder(
                    guestPath = guestPath,
                    displayName = child.name,
                    hasChildren = child.listFiles().orEmpty().any { it.isDirectory },
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
            .toList()
    }

    fun createFolder(
        context: Context,
        parentGuestPath: String,
        requestedName: String,
    ): Result<Folder> = runCatching {
        val parent = resolveFolder(context, parentGuestPath).getOrThrow()
        val name = requestedName.trim()
        require(isValidFolderName(name)) {
            "フォルダー名には /、\\、制御文字、.、.. を使用できません。"
        }
        val host = File(hostFolder(context, parent.guestPath), name)
        check(!host.exists()) { "同名のファイルまたはフォルダーが既にあります。" }
        check(host.mkdir()) { "フォルダーを作成できませんでした。" }
        resolveFolder(context, guestPathFor(context, host)).getOrThrow()
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

    private fun hostFolder(context: Context, guestPath: String): File {
        val root = OmochiRuntime.workspaceDir(context).apply { mkdirs() }.canonicalFile
        val relative = guestPath.removePrefix(GUEST_ROOT).trimStart('/')
        val target = if (relative.isEmpty()) root else File(root, relative).canonicalFile
        check(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
            "作業領域外のフォルダーは開けません。"
        }
        return target
    }

    private fun guestPathFor(context: Context, host: File): String? {
        val root = OmochiRuntime.workspaceDir(context).apply { mkdirs() }.canonicalFile
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
