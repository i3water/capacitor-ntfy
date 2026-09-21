package io.github.coloz.capacitor.ntfy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal class NtfyStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureConfig = NtfySecureConfig(context)

    fun saveConfig(config: NtfyConfig) {
        val oldSignature = secureConfig.load()?.signature
        secureConfig.save(config)
        if (oldSignature != config.signature) {
            preferences.edit().remove(KEY_LAST_ID).remove(KEY_MESSAGES)
                .putString(KEY_SIGNATURE, config.signature).apply()
        }
    }

    fun loadConfig(): NtfyConfig? = secureConfig.load()

    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    fun setNotificationPermissionAsked() {
        preferences.edit().putBoolean(KEY_PERMISSION_ASKED, true).apply()
    }

    fun wasNotificationPermissionAsked(): Boolean = preferences.getBoolean(KEY_PERMISSION_ASKED, false)

    @Synchronized
    fun saveMessage(message: JSONObject, config: NtfyConfig) {
        val current = messagesArray()
        val updated = JSONArray().put(message)
        val newId = message.optString("id")
        val newTopic = message.optString("topic")
        for (index in 0 until current.length()) {
            val item = current.optJSONObject(index) ?: continue
            if (item.optString("id") == newId && item.optString("topic") == newTopic) continue
            if (updated.length() >= config.historyLimit) break
            updated.put(item)
        }
        preferences.edit().putString(KEY_MESSAGES, updated.toString()).apply()
    }

    @Synchronized
    fun getMessages(limit: Int): JSONArray {
        val current = messagesArray()
        val result = JSONArray()
        for (index in 0 until minOf(limit, current.length())) result.put(current.get(index))
        return result
    }

    fun clearMessages() {
        preferences.edit().remove(KEY_MESSAGES).apply()
    }

    fun saveLastMessageId(config: NtfyConfig, id: String) {
        preferences.edit().putString(KEY_SIGNATURE, config.signature).putString(KEY_LAST_ID, id).apply()
    }

    fun getLastMessageId(config: NtfyConfig): String? {
        if (preferences.getString(KEY_SIGNATURE, null) != config.signature) return null
        return preferences.getString(KEY_LAST_ID, null)
    }

    fun saveStatus(status: JSONObject) {
        preferences.edit().putString(KEY_STATUS, status.toString()).apply()
    }

    fun getStatus(config: NtfyConfig?): JSONObject {
        val stored = preferences.getString(KEY_STATUS, null)?.let {
            try {
                JSONObject(it)
            } catch (_: Exception) {
                null
            }
        } ?: JSONObject().apply {
            put("state", "stopped")
            put("running", false)
            put("connected", false)
        }
        if (config != null) {
            stored.put("baseUrl", config.baseUrl)
            stored.put("topics", JSONArray(config.topics))
            getLastMessageId(config)?.let { stored.put("lastMessageId", it) }
        }
        return stored
    }

    private fun messagesArray(): JSONArray {
        val value = preferences.getString(KEY_MESSAGES, null) ?: return JSONArray()
        return try {
            JSONArray(value)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    companion object {
        private const val PREFS_NAME = "capacitor_ntfy"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PERMISSION_ASKED = "notification_permission_asked"
        private const val KEY_MESSAGES = "messages"
        private const val KEY_STATUS = "status"
        private const val KEY_SIGNATURE = "config_signature"
        private const val KEY_LAST_ID = "last_message_id"
    }
}
