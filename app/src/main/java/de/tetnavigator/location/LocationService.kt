package de.tetnavigator.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.tetnavigator.MainActivity
import de.tetnavigator.R
import de.tetnavigator.core.common.Format
import de.tetnavigator.core.offlinedata.SurfaceClass
import de.tetnavigator.core.preferences.SettingsRepository
import de.tetnavigator.feature.bedienung.BatteryMonitor
import de.tetnavigator.feature.logbuch.RideRecorder
import de.tetnavigator.feature.logistik.FuelRange
import de.tetnavigator.feature.navigation.DeviationMonitor
import de.tetnavigator.feature.navigation.Fix
import de.tetnavigator.feature.navigation.ManeuverKind
import de.tetnavigator.feature.navigation.NavEvent
import de.tetnavigator.feature.navigation.NavigationSession
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * GNSS-Foreground-Service (Typ location, GPS_PROVIDER, 1 Hz). Läuft auch bei
 * gesperrtem Bildschirm und füttert Navigation, Aufzeichnung und Tank-Reichweite.
 */
@AndroidEntryPoint
class LocationService : LifecycleService(), LocationListener {
    @Inject lateinit var session: NavigationSession
    @Inject lateinit var recorder: RideRecorder
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var battery: BatteryMonitor
    @Inject lateinit var alarm: AlarmPlayer

    private val lm by lazy { getSystemService(LocationManager::class.java) }
    private var intervalMs = 0L
    private var pendingFuelM = 0.0

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat(getString(R.string.service_text_idle))
        battery.start()
        lifecycleScope.launch { session.resumeIfActive() }
        lifecycleScope.launch { if (!recorder.recording) recorder.resumeOpen() }
        // GPS-Intervall an Akku-Modus anpassen
        lifecycleScope.launch {
            combine(settings.settings, battery.state) { s, b -> BatteryMonitor.gpsIntervalMs(s.batteryMode, b.percent, b.charging) }
                .distinctUntilChanged().collect { requestUpdates(it) }
        }
        // Alarme auch bei gesperrtem Bildschirm
        lifecycleScope.launch {
            session.events.collect { e ->
                val s = settings.current()
                when (e) {
                    NavEvent.DeviationAlarm -> alarm.deviation(s.alarmSound, s.alarmVibrate)
                    NavEvent.BackOnTrack -> alarm.backOnTrack(s.alarmSound, s.alarmVibrate)
                    is NavEvent.TurnAhead -> if (s.turnPreview && e.maneuver.kind == ManeuverKind.HAIRPIN) alarm.turn(s.alarmVibrate)
                    NavEvent.SectionFinished -> alarm.backOnTrack(s.alarmSound, s.alarmVibrate)
                }
            }
        }
        // Benachrichtigung aktuell halten
        lifecycleScope.launch {
            combine(session.state, recorder.state) { nav, rec ->
                buildString {
                    if (nav != null) {
                        append("${nav.track.name} · ${Format.distance(nav.remainingM)} übrig")
                        if (nav.deviation == DeviationMonitor.State.OFF_TRACK) append(" · ABWEICHUNG ${nav.deviationM.toInt()} m")
                    }
                    if (rec != null) { if (isNotEmpty()) append(" · "); append("REC ${Format.km1(rec.distanceM)} km") }
                }.ifEmpty { getString(R.string.service_text_idle) }
            }.map { it }.distinctUntilChanged().collect { updateNotification(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP_IF_IDLE) {
            lifecycleScope.launch {
                if (session.track.value == null && !recorder.recording) stopSelf()
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates(ms: Long) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        if (ms == intervalMs) return
        intervalMs = ms
        lm.removeUpdates(this)
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, ms, 0f, this, Looper.getMainLooper())
        lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { if (System.currentTimeMillis() - it.time < 120_000) onLocationChanged(it) }
    }

    override fun onLocationChanged(l: Location) {
        val fix = Fix(
            lat = l.latitude, lon = l.longitude, timeMs = l.time,
            speedMs = if (l.hasSpeed()) l.speed else 0f,
            bearing = if (l.hasBearing() && l.speed > 1.5f) l.bearing else null,
            accuracyM = if (l.hasAccuracy()) l.accuracy else 50f,
            altitude = if (l.hasAltitude()) l.altitude else null,
        )
        session.onFix(fix)
        lifecycleScope.launch {
            val nav = session.state.value
            val onTrack = nav != null && nav.deviation != DeviationMonitor.State.OFF_TRACK
            val surface = nav?.match?.let { SurfaceClass.ofCode(nav.track.vertices[it.segment].surface) }
            // Offroad: Schotter/Erde laut OSM, oder auf dem TET-Track bei unbekanntem Belag
            val offroad = surface?.offroad == true || (onTrack && surface == SurfaceClass.UNKNOWN)
            val added = recorder.add(fix.lat, fix.lon, fix.altitude, fix.timeMs, fix.speedMs, fix.accuracyM, offroad)
            consumeFuel(if (added > 0) added else 0.0, fix)
        }
    }

    private var lastFuelFix: Fix? = null

    /** Tank-Reichweite mitführen (auch ohne Aufzeichnung). */
    private suspend fun consumeFuel(recordedM: Double, fix: Fix) {
        val d = if (recorder.recording) recordedM else {
            val last = lastFuelFix
            if (last == null || fix.accuracyM > 35) 0.0 else de.tetnavigator.core.common.Geo.distance(last.lat, last.lon, fix.lat, fix.lon).let { if (it < 3) 0.0 else it }
        }
        if (fix.accuracyM <= 35) lastFuelFix = fix
        pendingFuelM += d
        if (pendingFuelM >= 500) {
            val m = pendingFuelM; pendingFuelM = 0.0
            settings.update { it.copy(fuelRemainingL = (it.fuelRemainingL - FuelRange.consumedLiters(m, it.consumptionLPer100)).coerceAtLeast(0.0)) }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    override fun onDestroy() {
        lm.removeUpdates(this)
        super.onDestroy()
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle(getString(R.string.service_title))
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun startForegroundCompat(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW))
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    private fun updateNotification(text: String) {
        try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text)) } catch (_: SecurityException) { }
    }

    companion object {
        private const val CHANNEL = "navigation"
        private const val NOTIFICATION_ID = 1201
        private const val ACTION_STOP_IF_IDLE = "stopIfIdle"

        fun start(ctx: Context) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
            ContextCompat.startForegroundService(ctx, Intent(ctx, LocationService::class.java))
        }

        fun stopIfIdle(ctx: Context) {
            try { ctx.startService(Intent(ctx, LocationService::class.java).setAction(ACTION_STOP_IF_IDLE)) } catch (_: Exception) { }
        }
    }
}
