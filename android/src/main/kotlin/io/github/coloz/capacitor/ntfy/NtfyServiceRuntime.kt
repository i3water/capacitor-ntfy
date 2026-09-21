package io.github.coloz.capacitor.ntfy

internal class NtfyServiceRuntimeTracker(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private enum class Phase {
        STOPPED,
        STARTING,
        RUNNING,
    }

    @Volatile private var phase = Phase.STOPPED
    @Volatile private var startingAtMillis = 0L

    @Synchronized
    fun markStarting() {
        // startForegroundService can deliver onStartCommand to the same live
        // Service without another onCreate. Do not downgrade that observation.
        if (phase == Phase.RUNNING) return
        phase = Phase.STARTING
        startingAtMillis = nowMillis()
    }

    @Synchronized
    fun markRunning() {
        phase = Phase.RUNNING
    }

    @Synchronized
    fun markStopped() {
        phase = Phase.STOPPED
        startingAtMillis = 0L
    }

    fun isExpectedActive(): Boolean = when (phase) {
        Phase.RUNNING -> true
        Phase.STARTING -> nowMillis() - startingAtMillis <= START_TIMEOUT_MILLIS
        Phase.STOPPED -> false
    }

    companion object {
        internal const val START_TIMEOUT_MILLIS = 10_000L
    }
}

internal val ntfyServiceRuntime = NtfyServiceRuntimeTracker()
