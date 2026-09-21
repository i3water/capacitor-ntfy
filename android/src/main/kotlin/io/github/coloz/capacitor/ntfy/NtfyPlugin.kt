package io.github.coloz.capacitor.ntfy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import androidx.core.content.ContextCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONObject

@CapacitorPlugin(
    name = "Ntfy",
    permissions = [Permission(alias = "notifications", strings = [Manifest.permission.POST_NOTIFICATIONS])],
)
class NtfyPlugin : Plugin(), NtfyEventListener {
    private lateinit var store: NtfyStore
    private val ioExecutor = Executors.newCachedThreadPool()
    private val notificationTap = NtfyNotificationTap()

    override fun load() {
        store = NtfyStore(context)
        reconcilePreviousExit()
        NtfyEventBus.add(this)
        captureNotificationTap(activity?.intent)
    }

    override fun handleOnNewIntent(intent: Intent?) {
        super.handleOnNewIntent(intent)
        captureNotificationTap(intent)
    }

    private fun captureNotificationTap(intent: Intent?) {
        if (!notificationTap.capture(intent?.dataString)) return
        intent?.data = null // Activity recreation must not replay a consumed tap.
        notifyListeners("notificationAction", JSObject())
    }

    @PluginMethod
    fun consumeNotificationAction(call: PluginCall) {
        val reference = notificationTap.take()
        val config = store.loadConfig()
        val result = JSObject()
        if (reference != null && config != null && store.isEnabled()) {
            val history = store.getMessages(500)
            for (index in 0 until history.length()) {
                val message = history.optJSONObject(index) ?: continue
                val topic = message.optString("topic")
                if (message.optString("event") == "message" && config.topics.contains(topic)
                    && reference == NtfyNotificationTap.uri(config.signature, topic, message.optString("id"))) {
                    result.put("message", toJSObject(message))
                    break
                }
            }
        }
        call.resolve(result)
    }

    override fun handleOnDestroy() {
        NtfyEventBus.remove(this)
        ioExecutor.shutdownNow()
        super.handleOnDestroy()
    }

    @PluginMethod
    fun start(call: PluginCall) {
        val config = parseConfig(call) ?: return
        try {
            store.saveConfig(config)
            store.setEnabled(true)
            val starting = JSONObject().apply {
                put("state", "starting")
                put("running", true)
                put("connected", false)
                put("baseUrl", config.baseUrl)
                put("topics", org.json.JSONArray(config.topics))
            }
            store.saveStatus(starting)
            ntfyServiceRuntime.markStarting()
            ContextCompat.startForegroundService(
                context,
                Intent(context, NtfyForegroundService::class.java).setAction(NtfyForegroundService.ACTION_START),
            )
            call.resolve(statusObject())
        } catch (error: Exception) {
            ntfyServiceRuntime.markStopped()
            store.setEnabled(false)
            call.reject("Unable to start the ntfy foreground service", error)
        }
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        notificationTap.clear()
        store.setEnabled(false)
        ntfyServiceRuntime.markStopped()
        context.stopService(Intent(context, NtfyForegroundService::class.java))
        val stopped = JSONObject().apply {
            put("state", "stopped")
            put("running", false)
            put("connected", false)
        }
        store.saveStatus(stopped)
        NtfyEventBus.statusChanged(stopped)
        call.resolve(statusObject())
    }

    @PluginMethod
    fun getStatus(call: PluginCall) {
        call.resolve(statusObject())
    }

    @PluginMethod
    fun getMessages(call: PluginCall) {
        val limit = (call.getInt("limit") ?: 100).coerceIn(1, 500)
        val result = JSObject()
        result.put("messages", JSArray(store.getMessages(limit).toString()))
        call.resolve(result)
    }

    @PluginMethod
    fun clearMessages(call: PluginCall) {
        notificationTap.clear()
        store.clearMessages()
        call.resolve()
    }

    @PluginMethod
    fun publish(call: PluginCall) {
        val baseUrl = normalizeBaseUrl(call.getString("baseUrl")) ?: run {
            call.reject("A valid HTTP(S) baseUrl is required")
            return
        }
        val topic = call.getString("topic")?.trim()?.takeIf { TOPIC_PATTERN.matches(it) } ?: run {
            call.reject("topic must match ${TOPIC_PATTERN.pattern}")
            return
        }
        val message = call.getString("message") ?: run {
            call.reject("message is required")
            return
        }
        val token = call.getString("token")
        val username = call.getString("username")
        val password = call.getString("password")
        val title = call.getString("title")
        val priority = call.getInt("priority")?.coerceIn(1, 5)
        val tags = call.getArray("tags")?.let { array ->
            buildList { for (index in 0 until array.length()) add(array.optString(index)) }
        }
        val click = call.getString("click")

        ioExecutor.execute {
            try {
                val connection = (URL("$baseUrl/$topic").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    doOutput = true
                    setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                    if (!token.isNullOrBlank()) {
                        setRequestProperty("Authorization", "Bearer $token")
                    } else if (!username.isNullOrBlank()) {
                        val credentials = "$username:${password.orEmpty()}"
                        val encoded = Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        setRequestProperty("Authorization", "Basic $encoded")
                    }
                    title?.let { setRequestProperty("Title", it) }
                    priority?.let { setRequestProperty("Priority", it.toString()) }
                    tags?.takeIf { it.isNotEmpty() }?.let { setRequestProperty("Tags", it.joinToString(",")) }
                    click?.let { setRequestProperty("Click", it) }
                }
                connection.outputStream.use { it.write(message.toByteArray(Charsets.UTF_8)) }
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    val detail = connection.errorStream?.bufferedReader()?.use { it.readText().take(300) }
                    throw IOException("ntfy HTTP $responseCode${detail?.let { ": $it" } ?: ""}")
                }
                val raw = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                call.resolve(toJSObject(NtfyMessageJson.fromRaw(raw)))
                connection.disconnect()
            } catch (error: Exception) {
                call.reject("Unable to publish the ntfy message", error)
            }
        }
    }

    @PluginMethod
    fun requestNotificationPermission(call: PluginCall) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            call.resolve(permissionObject())
            return
        }
        store.setNotificationPermissionAsked()
        requestPermissionForAlias("notifications", call, "notificationPermissionCallback")
    }

    @PermissionCallback
    private fun notificationPermissionCallback(call: PluginCall) {
        call.resolve(permissionObject())
    }

    @PluginMethod
    fun getNotificationPermission(call: PluginCall) {
        call.resolve(permissionObject())
    }

    @PluginMethod
    fun isIgnoringBatteryOptimizations(call: PluginCall) {
        val powerManager = context.getSystemService(PowerManager::class.java)
        call.resolve(JSObject().apply { put("value", powerManager.isIgnoringBatteryOptimizations(context.packageName)) })
    }

    @PluginMethod
    fun openBatteryOptimizationSettings(call: PluginCall) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            call.resolve()
        } catch (error: Exception) {
            call.reject("Unable to open battery optimization settings", error)
        }
    }

    @PluginMethod
    fun openAppSettings(call: PluginCall) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            call.resolve()
        } catch (error: Exception) {
            call.reject("Unable to open application settings", error)
        }
    }

    override fun onStatusChanged(status: JSONObject) {
        activity?.runOnUiThread { notifyListeners("statusChanged", enrichStatus(status)) }
    }

    override fun onMessageReceived(message: JSONObject) {
        activity?.runOnUiThread { notifyListeners("messageReceived", toJSObject(message)) }
    }

    private fun parseConfig(call: PluginCall): NtfyConfig? {
        val baseUrl = normalizeBaseUrl(call.getString("baseUrl")) ?: run {
            call.reject("A valid HTTP(S) baseUrl is required")
            return null
        }
        val topicsArray = call.getArray("topics") ?: run {
            call.reject("topics is required")
            return null
        }
        val topics = buildList {
            for (index in 0 until topicsArray.length()) {
                topicsArray.optString(index).trim().takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }.distinct()
        if (topics.isEmpty() || topics.any { !TOPIC_PATTERN.matches(it) }) {
            call.reject("Each topic must match ${TOPIC_PATTERN.pattern}")
            return null
        }
        val serviceChannelId = call.getString("serviceChannelId") ?: "capacitor_ntfy_service"
        val messageChannelId = call.getString("messageChannelId") ?: "capacitor_ntfy_messages"
        if (!CHANNEL_PATTERN.matches(serviceChannelId) || !CHANNEL_PATTERN.matches(messageChannelId)) {
            call.reject("Notification channel IDs may contain letters, numbers, dot, underscore and dash only")
            return null
        }
        return NtfyConfig(
            baseUrl = baseUrl,
            topics = topics,
            token = call.getString("token")?.takeIf { it.isNotBlank() },
            username = call.getString("username")?.takeIf { it.isNotBlank() },
            password = call.getString("password"),
            initialSince = call.getString("initialSince")?.takeIf { it.isNotBlank() } ?: "10m",
            autoStartOnBoot = call.getBoolean("autoStartOnBoot") ?: true,
            showNotifications = call.getBoolean("showNotifications") ?: true,
            historyLimit = (call.getInt("historyLimit") ?: 100).coerceIn(1, 500),
            foregroundTitle = call.getString("foregroundTitle") ?: "ntfy 实时连接",
            foregroundText = call.getString("foregroundText") ?: "正在等待新消息",
            serviceChannelId = serviceChannelId,
            serviceChannelName = call.getString("serviceChannelName") ?: "ntfy 后台连接",
            messageChannelId = messageChannelId,
            messageChannelName = call.getString("messageChannelName") ?: "ntfy 消息",
        )
    }

    private fun normalizeBaseUrl(value: String?): String? {
        val normalized = value?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val uri = URI(normalized)
            normalized.takeIf { (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun statusObject(): JSObject {
        val config = store.loadConfig()
        var status = store.getStatus(config)
        if (status.optBoolean("running") && !ntfyServiceRuntime.isExpectedActive()) {
            status = stoppedStatus(config)
            store.saveStatus(status)
        }
        return enrichStatus(status)
    }

    private fun reconcilePreviousExit() {
        if (!store.isEnabled() || ntfyServiceRuntime.isExpectedActive()) return
        val config = store.loadConfig()
        if (config == null || NtfyProcessExit.wasStoppedByUser(context)) {
            store.setEnabled(false)
            store.saveStatus(stoppedStatus(config))
            return
        }

        val starting = JSONObject().apply {
            put("state", "starting")
            put("running", true)
            put("connected", false)
            put("baseUrl", config.baseUrl)
            put("topics", org.json.JSONArray(config.topics))
        }
        store.saveStatus(starting)
        ntfyServiceRuntime.markStarting()
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NtfyForegroundService::class.java).setAction(NtfyForegroundService.ACTION_START),
            )
        }.onFailure {
            ntfyServiceRuntime.markStopped()
            store.saveStatus(stoppedStatus(config))
        }
    }

    private fun stoppedStatus(config: NtfyConfig?): JSONObject = JSONObject().apply {
        put("state", "stopped")
        put("running", false)
        put("connected", false)
        if (config != null) {
            put("baseUrl", config.baseUrl)
            put("topics", org.json.JSONArray(config.topics))
            store.getLastMessageId(config)?.let { put("lastMessageId", it) }
        }
    }

    private fun enrichStatus(status: JSONObject): JSObject {
        val powerManager = context.getSystemService(PowerManager::class.java)
        status.put("batteryOptimizationsIgnored", powerManager.isIgnoringBatteryOptimizations(context.packageName))
        status.put("notificationPermission", permissionState())
        return toJSObject(status)
    }

    private fun permissionObject(): JSObject = JSObject().apply { put("state", permissionState()) }

    private fun permissionState(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "granted"
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return "granted"
        }
        return if (store.wasNotificationPermissionAsked()) "denied" else "prompt"
    }

    private fun toJSObject(json: JSONObject): JSObject = JSObject().apply {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            put(key, json.get(key))
        }
    }

    companion object {
        private val TOPIC_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
        private val CHANNEL_PATTERN = Regex("^[A-Za-z0-9._-]{1,100}$")
    }
}
