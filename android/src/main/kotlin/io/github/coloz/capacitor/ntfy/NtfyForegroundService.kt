package io.github.coloz.capacitor.ntfy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ThreadLocalRandom
import org.json.JSONObject

class NtfyForegroundService : Service() {
    private lateinit var store: NtfyStore
    private lateinit var connectivityManager: ConnectivityManager
    private val networkMonitor = Object()

    @Volatile private var networkAvailable = false
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var generation = 0L
    @Volatile private var networkGeneration = 0L
    @Volatile private var currentConfig: NtfyConfig? = null
    private var networkSnapshot = NtfyNetworkSnapshot(handle = null, available = false)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = networkChanged()
        override fun onLost(network: Network) = networkChanged()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = networkChanged()
    }

    override fun onCreate() {
        super.onCreate()
        ntfyServiceRuntime.markRunning()
        store = NtfyStore(this)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkSnapshot = currentNetworkSnapshot()
        networkAvailable = networkSnapshot.available
        runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            store.setEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }

        val config = store.loadConfig()
        if (config == null || !store.isEnabled()) {
            stopSelf()
            return START_NOT_STICKY
        }

        currentConfig = config
        createNotificationChannels(config)
        startOrUpdateForeground(config, "正在启动")
        startConnection(config)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val previous = synchronized(networkMonitor) {
            generation += 1
            val old = activeConnection
            activeConnection = null
            networkMonitor.notifyAll()
            old
        }
        previous?.disconnect()
        ntfyServiceRuntime.markStopped()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        val status = statusJson("stopped", running = false, connected = false)
        store.saveStatus(status)
        NtfyEventBus.statusChanged(status)
        super.onDestroy()
    }

    private fun startConnection(config: NtfyConfig) {
        val (runId, previous) = synchronized(networkMonitor) {
            generation += 1
            val old = activeConnection
            activeConnection = null
            networkMonitor.notifyAll()
            generation to old
        }
        previous?.disconnect()
        Thread({ connectionLoop(config, runId) }, "capacitor-ntfy-stream").apply {
            isDaemon = true
            start()
        }
    }

    private fun connectionLoop(config: NtfyConfig, runId: Long) {
        val reconnectPolicy = NtfyReconnectPolicy()
        while (isActive(runId)) {
            if (!networkAvailable) {
                reconnectPolicy.reset()
                emitStatus("waitingForNetwork", false, null, null, runId)
                waitForSignal(60_000L)
                continue
            }

            val connectionNetworkGeneration = networkGeneration
            var connectedAtNanos: Long? = null
            try {
                emitStatus(if (reconnectPolicy.isRetrying) "reconnecting" else "connecting", false, null, null, runId)
                subscribe(config, runId) { connectedAtNanos = System.nanoTime() }
                if (!isActive(runId)) return
                throw IOException("ntfy stream ended")
            } catch (error: Exception) {
                if (!isActive(runId)) return
                if (!networkAvailable || connectionNetworkGeneration != networkGeneration) {
                    reconnectPolicy.reset()
                    continue
                }
                val wasStable = connectedAtNanos?.let {
                    System.nanoTime() - it >= STABLE_CONNECTION_NANOS
                } ?: false
                if (wasStable) reconnectPolicy.reset()
                val retrySeconds = reconnectPolicy.nextDelaySeconds()
                emitStatus(
                    state = "reconnecting",
                    connected = false,
                    error = error.message ?: error.javaClass.simpleName,
                    retrySeconds = retrySeconds,
                    runId = runId,
                )
                val jitter = ThreadLocalRandom.current().nextLong(0, 1_000)
                waitForSignal(retrySeconds * 1_000L + jitter)
            }
        }
    }

    private fun subscribe(config: NtfyConfig, runId: Long, onConnected: () -> Unit) {
        val since = store.getLastMessageId(config) ?: config.initialSince
        val encodedSince = URLEncoder.encode(since, Charsets.UTF_8.name())
        val topics = config.topics.joinToString(",")
        val url = URL("${config.baseUrl}/$topics/json?since=$encodedSince")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 90_000
            useCaches = false
            setRequestProperty("Accept", "application/x-ndjson, application/json")
            setRequestProperty("User-Agent", "capacitor-ntfy/0.1 Android")
            setAuthorization(config)
        }
        try {
            synchronized(networkMonitor) {
                if (!isActive(runId)) return
                activeConnection = connection
            }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) throw IOException("ntfy HTTP $responseCode")

            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                while (isActive(runId)) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val raw = JSONObject(line)
                    when (raw.optString("event")) {
                        "open" -> {
                            onConnected()
                            emitStatus("connected", true, null, null, runId, "open")
                        }
                        "keepalive", "message" -> {
                            // Record native I/O, not a JS timer or an old persisted
                            // connected flag. Heartbeats need no notification update.
                            if (isActive(runId)) store.saveStatus(
                                statusJson("connected", running = true, connected = true)
                                    .put("streamEvent", raw.optString("event")),
                            )
                            if (raw.optString("event") == "message") handleMessage(raw, config, runId)
                        }
                    }
                }
            }
        } finally {
            // A retired worker owns this socket only, never its replacement.
            synchronized(networkMonitor) {
                if (activeConnection === connection) activeConnection = null
            }
            connection.disconnect()
        }
    }

    private fun handleMessage(raw: JSONObject, config: NtfyConfig, runId: Long) {
        if (!isActive(runId)) return
        val message = NtfyMessageJson.fromRaw(raw)
        store.saveMessage(message, config)
        message.optString("id").takeIf { it.isNotBlank() }?.let { store.saveLastMessageId(config, it) }
        NtfyEventBus.messageReceived(message)
        if (config.showNotifications) showMessageNotification(message, config)
    }

    private fun HttpURLConnection.setAuthorization(config: NtfyConfig) {
        when {
            !config.token.isNullOrBlank() -> setRequestProperty("Authorization", "Bearer ${config.token}")
            !config.username.isNullOrBlank() -> {
                val credentials = "${config.username}:${config.password.orEmpty()}"
                val encoded = Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                setRequestProperty("Authorization", "Basic $encoded")
            }
        }
    }

    private fun emitStatus(
        state: String,
        connected: Boolean,
        error: String?,
        retrySeconds: Int?,
        runId: Long,
        streamEvent: String? = null,
    ) {
        if (!isActive(runId)) return
        val status = statusJson(state, running = true, connected = connected).apply {
            if (error == null) remove("lastError") else put("lastError", error)
            if (retrySeconds == null) remove("retryInSeconds") else put("retryInSeconds", retrySeconds)
            if (streamEvent != null) put("streamEvent", streamEvent)
        }
        store.saveStatus(status)
        NtfyEventBus.statusChanged(status)
        currentConfig?.let {
            val text = when (state) {
                "connected" -> it.foregroundText
                "waitingForNetwork" -> "等待网络连接"
                "reconnecting" -> "连接中断，正在重试"
                else -> "正在连接 ntfy"
            }
            startOrUpdateForeground(it, text)
        }
    }

    private fun statusJson(state: String, running: Boolean, connected: Boolean): JSONObject {
        val config = currentConfig
        return JSONObject().apply {
            put("state", state)
            put("running", running)
            put("connected", connected)
            put("observedAt", System.currentTimeMillis())
            if (config != null) {
                put("baseUrl", config.baseUrl)
                put("topics", org.json.JSONArray(config.topics))
                store.getLastMessageId(config)?.let { put("lastMessageId", it) }
            }
        }
    }

    private fun createNotificationChannels(config: NtfyConfig) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(config.serviceChannelId, config.serviceChannelName, NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持与 ntfy 服务器的实时连接"
                setShowBadge(false)
            },
        )
        (1..5).forEach { priority ->
            val profile = ntfyNotificationProfile(priority)
            manager.createNotificationChannel(
                NotificationChannel(
                    profile.channelId(config.messageChannelId),
                    "${config.messageChannelName}（${profile.channelNameSuffix}）",
                    profile.importance,
                ).apply {
                    description = "ntfy 优先级 ${profile.priority} 消息"
                    if (profile.vibrationPattern == null) {
                        enableVibration(false)
                    } else {
                        enableVibration(true)
                        vibrationPattern = profile.vibrationPattern
                    }
                },
            )
        }
    }

    private fun startOrUpdateForeground(config: NtfyConfig, text: String) {
        val notification = NotificationCompat.Builder(this, config.serviceChannelId)
            .setSmallIcon(notificationIcon())
            .setContentTitle(config.foregroundTitle)
            .setContentText(text)
            .setContentIntent(appPendingIntent(FOREGROUND_NOTIFICATION_ID))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        val serviceType = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, FOREGROUND_NOTIFICATION_ID, notification, serviceType)
    }

    private fun showMessageNotification(message: JSONObject, config: NtfyConfig) {
        val id = message.optString("id", System.nanoTime().toString()).hashCode() and 0x7fffffff
        val profile = ntfyNotificationProfile(message.optInt("priority", 3))
        val notification = NotificationCompat.Builder(this, profile.channelId(config.messageChannelId))
            .setSmallIcon(notificationIcon())
            .setContentTitle(message.optString("title").ifBlank { message.optString("topic", "ntfy") })
            .setContentText(message.optString("message"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.optString("message")))
            .setContentIntent(appPendingIntent(id, NtfyNotificationTap.uri(
                config.signature, message.optString("topic"), message.optString("id"),
            )))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(profile.compatPriority)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(MESSAGE_NOTIFICATION_BASE + id % 100_000, notification) }
    }

    private fun notificationIcon(): Int = applicationInfo.icon.takeIf { it != 0 }
        ?: android.R.drawable.stat_notify_sync_noanim

    private fun appPendingIntent(requestCode: Int, tapUri: String? = null): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // Distinct data also prevents colliding integer request codes from
            // replacing another message's PendingIntent. Always open our app.
            if (tapUri != null) data = Uri.parse(tapUri)
        } ?: return null
        return PendingIntent.getActivity(
            this,
            requestCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun networkChanged() {
        val nextSnapshot = currentNetworkSnapshot()
        val changed = synchronized(networkMonitor) {
            val value = nextSnapshot != networkSnapshot
            networkSnapshot = nextSnapshot
            networkAvailable = nextSnapshot.available
            if (value) networkGeneration += 1
            networkMonitor.notifyAll()
            value
        }
        if (changed) activeConnection?.disconnect()
    }

    private fun currentNetworkSnapshot(): NtfyNetworkSnapshot {
        val network = connectivityManager.activeNetwork
            ?: return NtfyNetworkSnapshot(handle = null, available = false)
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        val available = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        return NtfyNetworkSnapshot(handle = network.networkHandle, available = available)
    }

    private fun waitForSignal(milliseconds: Long) {
        synchronized(networkMonitor) {
            runCatching { networkMonitor.wait(milliseconds) }
        }
    }

    private fun isActive(runId: Long): Boolean = runId == generation && store.isEnabled()

    companion object {
        const val ACTION_START = "io.github.coloz.capacitor.ntfy.START"
        const val ACTION_STOP = "io.github.coloz.capacitor.ntfy.STOP"
        private const val FOREGROUND_NOTIFICATION_ID = 42_600
        private const val MESSAGE_NOTIFICATION_BASE = 50_000
        private const val STABLE_CONNECTION_NANOS = 30_000_000_000L
    }
}
