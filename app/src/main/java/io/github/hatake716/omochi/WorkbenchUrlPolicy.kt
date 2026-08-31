package io.github.hatake716.omochi

import java.net.URI

/** Pure URL boundary used before allowing a WebView navigation to stay in-process. */
internal object WorkbenchUrlPolicy {
    enum class PopupTarget {
        INITIAL_BLANK,
        REUSE_WORKBENCH,
        EXTERNAL_APP,
        BLOCKED,
    }

    enum class NativeMenuAction {
        OPEN_FOLDER,
        LINK_DEVICE_FOLDER,
        CLOSE_FOLDER,
        EXIT_WORKBENCH,
    }

    fun isTrustedLoopback(runningUrl: String?, candidateUrl: String): Boolean {
        if (runningUrl.isNullOrBlank()) return false
        return runCatching {
            val expected = URI(runningUrl)
            val candidate = URI(candidateUrl)
            expected.scheme == "http" &&
                expected.host == "127.0.0.1" &&
                expected.port in 1024..65535 &&
                candidate.scheme == "http" &&
                candidate.host == expected.host &&
                candidate.port == expected.port &&
                candidate.rawUserInfo == null
        }.getOrDefault(false)
    }

    fun shouldOpenExternally(scheme: String?): Boolean =
        scheme?.lowercase() in setOf("http", "https", "mailto")

    fun classifyPopup(runningUrl: String?, candidateUrl: String): PopupTarget {
        val scheme = runCatching { URI(candidateUrl).scheme?.lowercase() }.getOrNull()
        if (scheme == "about" && candidateUrl.equals("about:blank", ignoreCase = true)) {
            return PopupTarget.INITIAL_BLANK
        }
        if (isTrustedLoopback(runningUrl, candidateUrl)) return PopupTarget.REUSE_WORKBENCH
        if (shouldOpenExternally(scheme)) return PopupTarget.EXTERNAL_APP
        return PopupTarget.BLOCKED
    }

    fun nativeMenuAction(
        runningUrl: String?,
        currentPageUrl: String?,
        candidateUrl: String,
    ): NativeMenuAction? {
        if (currentPageUrl.isNullOrBlank() || !isTrustedLoopback(runningUrl, currentPageUrl)) return null
        return runCatching {
            val uri = URI(candidateUrl)
            if (uri.scheme != "omochi" || uri.host != "menu" || uri.rawUserInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null
            ) {
                return@runCatching null
            }
            when (uri.path) {
                "/open-folder" -> NativeMenuAction.OPEN_FOLDER
                "/link-device-folder" -> NativeMenuAction.LINK_DEVICE_FOLDER
                "/close-folder" -> NativeMenuAction.CLOSE_FOLDER
                "/exit-workbench" -> NativeMenuAction.EXIT_WORKBENCH
                else -> null
            }
        }.getOrNull()
    }
}
