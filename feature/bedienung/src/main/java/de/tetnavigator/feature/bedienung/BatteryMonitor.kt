package de.tetnavigator.feature.bedienung

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class BatteryState(
    val percent: Int,
    val charging: Boolean,
    val temperatureC: Float?,
    /** Geschätzte Restlaufzeit (ms) aus der gemessenen Entladerate, null = unbekannt. */
    val remainingMs: Long?,
)

/** Schätzt die Restlaufzeit aus dem Akkuverlauf (gleitend über bis zu 30 min). */
class DrainEstimator(private val windowMs: Long = 30 * 60_000L) {
    private val samples = ArrayDeque<Pair<Long, Int>>()

    fun add(timeMs: Long, percent: Int, charging: Boolean): Long? {
        if (charging) { samples.clear(); return null }
        if (samples.lastOrNull()?.second != percent) samples.addLast(timeMs to percent)
        while (samples.size > 2 && timeMs - samples.first().first > windowMs) samples.removeFirst()
        if (samples.size < 2) return null
        val (t0, p0) = samples.first(); val (t1, p1) = samples.last()
        val drop = p0 - p1
        if (drop <= 0 || t1 <= t0) return null
        val msPerPercent = (t1 - t0).toDouble() / drop
        return (msPerPercent * percent).toLong()
    }
}

/** Akku-Modus: Restlaufzeit anzeigen, GPS-Intervall und Helligkeit anpassen. */
@Singleton
class BatteryMonitor @Inject constructor(@ApplicationContext private val context: Context) {
    private val _state = MutableStateFlow(BatteryState(100, false, null, null))
    val state: StateFlow<BatteryState> = _state
    private val estimator = DrainEstimator()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = update(i)
    }

    fun start() {
        if (registered) return
        registered = true
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::update)
    }

    fun stop() {
        if (!registered) return
        registered = false
        try { context.unregisterReceiver(receiver) } catch (_: Exception) { }
    }

    private fun update(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val pct = if (level < 0) 100 else level * 100 / scale
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.div(10f)
        _state.value = BatteryState(pct, charging, temp, estimator.add(System.currentTimeMillis(), pct, charging))
    }

    companion object {
        /** GPS-Intervall: 1 Hz normal, im Akku-Modus oder < 15 % Akku 3 s. */
        fun gpsIntervalMs(batteryMode: Boolean, percent: Int, charging: Boolean): Long =
            if (!charging && (batteryMode || percent < 15)) 3_000L else 1_000L

        /** Fensterhelligkeit (-1 = System). Akku-Modus dimmt; Hitze (> 42 °C) ebenfalls. */
        fun brightness(batteryMode: Boolean, temperatureC: Float?): Float = when {
            temperatureC != null && temperatureC > 42f -> 0.35f
            batteryMode -> 0.4f
            else -> -1f
        }
    }
}
