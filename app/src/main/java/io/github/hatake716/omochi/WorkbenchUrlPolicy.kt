package io.github.hatake716.omochi

import java.net.URI

/** Pure URL boundary used before allowing a WebView navigation to stay in-process. */
internal object WorkbenchUrlPolicy {
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
}
