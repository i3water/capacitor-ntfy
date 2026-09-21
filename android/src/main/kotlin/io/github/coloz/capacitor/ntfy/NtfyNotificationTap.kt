package io.github.coloz.capacitor.ntfy

import java.security.MessageDigest

// An opaque reference to local history, never an executable URL or payload.
// Keep only the latest click while the WebView is starting.
internal class NtfyNotificationTap {
    private var pending: String? = null

    @Synchronized fun capture(uri: String?): Boolean {
        if (uri == null || !URI_PATTERN.matches(uri)) return false
        pending = uri
        return true
    }

    @Synchronized fun take(): String? = pending.also { pending = null }
    @Synchronized fun clear() { pending = null }

    companion object {
        private val URI_PATTERN = Regex("^capacitor-ntfy://notification/[0-9a-f]{64}$")

        fun uri(signature: String, topic: String, id: String): String {
            val fields = listOf(signature, topic, id).joinToString("") { "${it.length}:$it" }
            val digest = MessageDigest.getInstance("SHA-256").digest(fields.toByteArray(Charsets.UTF_8))
            return "capacitor-ntfy://notification/" + digest.joinToString("") { "%02x".format(it) }
        }
    }
}
