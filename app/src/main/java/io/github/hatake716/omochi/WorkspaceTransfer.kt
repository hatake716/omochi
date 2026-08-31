package io.github.hatake716.omochi

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** SAF import/export for Omochi's private `/workspace`. Existing files are never overwritten. */
object WorkspaceTransfer {
    data class Progress(
        val operation: String,
        val currentPath: String,
        val filesCopied: Int,
        val bytesCopied: Long
    )

    data class Summary(
        val filesCopied: Int,
        val directoriesCreated: Int,
        val bytesCopied: Long,
        val destination: String
    )

    fun importDocuments(
        context: Context,
        uris: List<Uri>,
        onProgress: (Progress) -> Unit,
        onComplete: (Result<Summary>) -> Unit
    ) = runAsync("OmochiFileImport", onComplete) {
        val workspace = OmochiRuntime.workspaceDir(context).apply { mkdirs() }
        val counter = Counter()
        uris.distinct().forEach { uri ->
            val name = displayName(context, uri) ?: "imported-file"
            val target = uniqueTarget(workspace, safeName(name))
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().buffered().use { output ->
                    counter.bytes += input.copyTo(output, 128 * 1024)
                }
            } ?: throw IOException("選択したファイルを開けません: $name")
            counter.files += 1
            onProgress(counter.progress("import", target.name))
        }
        counter.summary(workspace.absolutePath)
    }

    fun importTree(
        context: Context,
        uri: Uri,
        onProgress: (Progress) -> Unit,
        onComplete: (Result<Summary>) -> Unit
    ) = runAsync("OmochiTreeImport", onComplete) {
        val source = DocumentFile.fromTreeUri(context, uri)
            ?: throw IOException("選択したフォルダを開けません。")
        check(source.isDirectory) { "選択先はフォルダではありません。" }
        val workspace = OmochiRuntime.workspaceDir(context).apply { mkdirs() }
        val rootName = safeName(source.name ?: "imported-folder")
        val destination = uniqueTarget(workspace, rootName)
        check(destination.mkdirs()) { "取込先フォルダを作成できません。" }
        val counter = Counter(directories = 1)
        copyTreeToFile(context, source, destination, counter, onProgress)
        counter.summary(destination.absolutePath)
    }

    fun exportWorkspace(
        context: Context,
        destinationTree: Uri,
        onProgress: (Progress) -> Unit,
        onComplete: (Result<Summary>) -> Unit
    ) = runAsync("OmochiWorkspaceExport", onComplete) {
        val destinationRoot = DocumentFile.fromTreeUri(context, destinationTree)
            ?: throw IOException("書出先フォルダを開けません。")
        check(destinationRoot.isDirectory) { "書出先はフォルダではありません。" }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val exportName = "Omochi-workspace-$stamp"
        val workspace = OmochiRuntime.workspaceDir(context).apply { mkdirs() }
        val workspaceRoot = workspace.canonicalFile
        val exportRoot = createUniqueDirectory(destinationRoot, exportName)
        val counter = Counter(directories = 1)
        workspace.listFiles()?.sortedBy { it.name.lowercase(Locale.ROOT) }?.forEach { child ->
            copyFileToTree(
                context,
                child,
                workspaceRoot,
                exportRoot,
                counter,
                onProgress,
                child.name,
            )
        }
        counter.summary(exportRoot.name.orEmpty().ifBlank { exportName })
    }

    private fun copyTreeToFile(
        context: Context,
        source: DocumentFile,
        destination: File,
        counter: Counter,
        onProgress: (Progress) -> Unit
    ) {
        source.listFiles().sortedBy { it.name.orEmpty().lowercase(Locale.ROOT) }.forEach { child ->
            val name = safeName(child.name ?: "unnamed")
            val target = File(destination, name)
            when {
                child.isDirectory -> {
                    check(target.mkdirs() || target.isDirectory) {
                        "フォルダを作成できません: ${target.absolutePath}"
                    }
                    counter.directories += 1
                    copyTreeToFile(context, child, target, counter, onProgress)
                }
                child.isFile -> {
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
                        target.outputStream().buffered().use { output ->
                            counter.bytes += input.copyTo(output, 128 * 1024)
                        }
                    } ?: throw IOException("読込に失敗しました: ${child.name}")
                    counter.files += 1
                    onProgress(counter.progress("import", target.relativeTo(destination).path))
                }
            }
        }
    }

    private fun copyFileToTree(
        context: Context,
        source: File,
        workspaceRoot: File,
        destination: DocumentFile,
        counter: Counter,
        onProgress: (Progress) -> Unit,
        relative: String
    ) {
        // PRoot の --link2symlink は Git の loose object 作成時の hard link を
        // rootfs/.l2s 配下への疑似リンクへ変換する。これを追跡して SAF へ
        // 書き出すとワークスペース外参照を許すことになるため、Git 管理情報は
        // スナップショット対象から外し、作業ツリーの実ファイルだけを書き出す。
        if (isExcludedExportEntry(source)) return
        if (!isSafeExportSource(workspaceRoot, source)) {
            throw IOException("シンボリックリンクまたはワークスペース外の項目は書き出せません: $relative")
        }
        if (source.isDirectory) {
            val childDir = destination.createDirectory(source.name)
                ?: throw IOException("書出先フォルダを作成できません: $relative")
            counter.directories += 1
            source.listFiles()?.sortedBy { it.name.lowercase(Locale.ROOT) }?.forEach { child ->
                copyFileToTree(
                    context,
                    child,
                    workspaceRoot,
                    childDir,
                    counter,
                    onProgress,
                    "$relative/${child.name}"
                )
            }
            return
        }

        val mime = mimeType(source.name)
        val target = destination.createFile(mime, source.name)
            ?: throw IOException("書出先ファイルを作成できません: $relative")
        context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
            source.inputStream().buffered().use { input ->
                counter.bytes += input.copyTo(output, 128 * 1024)
            }
        } ?: throw IOException("書出先ファイルを開けません: $relative")
        counter.files += 1
        onProgress(counter.progress("export", relative))
    }

    private fun createUniqueDirectory(parent: DocumentFile, requestedName: String): DocumentFile {
        for (index in 1..1_000) {
            val name = if (index == 1) requestedName else "$requestedName-$index"
            if (parent.findFile(name) == null) {
                parent.createDirectory(name)?.let { return it }
            }
        }
        throw IOException("書出先に一意のフォルダを作成できません: $requestedName")
    }

    internal fun isSafeExportSource(workspaceRoot: File, source: File): Boolean {
        if (Files.isSymbolicLink(source.toPath())) return false
        val root = workspaceRoot.canonicalFile
        val candidate = source.canonicalFile
        return candidate.path == root.path ||
            candidate.path.startsWith(root.path + File.separator)
    }

    internal fun isExcludedExportEntry(source: File): Boolean =
        source.name == ".git" ||
            source.name == "Omochi-Recovery" ||
            source.name.startsWith(".omochi-sync-tmp-")

    private fun displayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0)
            }
        return DocumentFile.fromSingleUri(context, uri)?.name
    }

    private fun uniqueTarget(parent: File, requestedName: String): File {
        val initial = File(parent, requestedName)
        if (!initial.exists()) return initial

        val extension = requestedName.substringAfterLast('.', "")
        val stem = if (extension.isEmpty()) requestedName else requestedName.dropLast(extension.length + 1)
        for (index in 2..10_000) {
            val candidateName = if (extension.isEmpty()) {
                "$stem (import $index)"
            } else {
                "$stem (import $index).$extension"
            }
            val candidate = File(parent, candidateName)
            if (!candidate.exists()) return candidate
        }
        throw IOException("同名項目が多すぎるため取込先を作成できません: $requestedName")
    }

    internal fun safeName(value: String): String {
        val cleaned = value
            .replace('\u0000', '_')
            .replace('/', '_')
            .replace('\\', '_')
            .trim()
        val usable = cleaned.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "unnamed"
        return truncateUtf8(usable, maxBytes = 240).ifBlank { "unnamed" }
    }

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        val result = StringBuilder()
        var index = 0
        var bytes = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val piece = String(Character.toChars(codePoint))
            val pieceBytes = piece.toByteArray(Charsets.UTF_8).size
            if (bytes + pieceBytes > maxBytes) break
            result.append(piece)
            bytes += pieceBytes
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }

    internal fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        // ExternalStorageProvider は text/plain と未知の拡張子を組み合わせると
        // `main.kt.txt` のように `.txt` を補う。コードは MIME より正確な
        // ファイル名を優先し、既知のメディア形式以外は octet-stream にする。
        "txt" -> "text/plain"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    private class Counter(
        var files: Int = 0,
        var directories: Int = 0,
        var bytes: Long = 0
    ) {
        fun progress(operation: String, current: String) = Progress(
            operation = operation,
            currentPath = current,
            filesCopied = files,
            bytesCopied = bytes
        )

        fun summary(destination: String) = Summary(
            filesCopied = files,
            directoriesCreated = directories,
            bytesCopied = bytes,
            destination = destination
        )
    }

    private fun <T> runAsync(
        name: String,
        onComplete: (Result<T>) -> Unit,
        block: () -> T
    ) {
        Thread({ onComplete(runCatching(block)) }, name).start()
    }
}
