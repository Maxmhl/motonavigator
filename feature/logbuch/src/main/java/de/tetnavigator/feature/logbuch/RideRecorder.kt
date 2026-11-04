package de.tetnavigator.feature.logbuch

import de.tetnavigator.core.common.Countries
import de.tetnavigator.core.common.Geo
import de.tetnavigator.core.common.LatLon
import de.tetnavigator.core.database.RideCountryEntity
import de.tetnavigator.core.database.RideEntity
import de.tetnavigator.core.database.RidePointEntity
import de.tetnavigator.core.database.TetDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class RecordingState(
    val rideId: Long,
    val startedAt: Long,
    val distanceM: Double,
    val offroadM: Double,
    val ascentM: Double,
    val movingMs: Long,
    val points: Int,
)

/** Reine Statistik einer Aufzeichnung (testbar ohne Datenbank). */
class RideStats(
    private val minStepM: Double = 3.0,
    private val maxAccuracyM: Float = 35f,
    private val eleThresholdM: Double = 4.0,
) {
    var distanceM = 0.0; private set
    var offroadM = 0.0; private set
    var ascentM = 0.0; private set
    var movingMs = 0L; private set
    var offroadMovingMs = 0L; private set
    val perCountry = HashMap<String, DoubleArray>() // [distance, offroad]

    private var last: LatLon? = null
    private var lastTime = 0L
    private var eleRef: Double? = null

    /** @return true, wenn der Punkt übernommen wurde. */
    fun add(lat: Double, lon: Double, ele: Double?, timeMs: Long, speedMs: Float, accuracyM: Float, offroad: Boolean, country: String?): Boolean {
        if (accuracyM > maxAccuracyM) return false
        val p = LatLon(lat, lon)
        val prev = last
        if (prev == null) {
            last = p; lastTime = timeMs; eleRef = ele
            return true
        }
        val d = Geo.distance(prev, p)
        if (d < minStepM) return false
        val dt = (timeMs - lastTime).coerceAtLeast(0)
        // Unplausible Sprünge (> 250 km/h) verwerfen
        if (dt > 0 && d / (dt / 1000.0) > 70) return false
        distanceM += d
        if (offroad) offroadM += d
        if (speedMs > 1.5f || (dt > 0 && d / (dt / 1000.0) > 1.5)) {
            val mv = dt.coerceAtMost(30_000)
            movingMs += mv
            if (offroad) offroadMovingMs += mv
        }
        if (ele != null) {
            val r = eleRef
            if (r == null) eleRef = ele else {
                val de = ele - r
                if (de >= eleThresholdM) { ascentM += de; eleRef = ele } else if (de <= -eleThresholdM) eleRef = ele
            }
        }
        if (country != null) {
            val arr = perCountry.getOrPut(country) { DoubleArray(2) }
            arr[0] += d; if (offroad) arr[1] += d
        }
        last = p; lastTime = timeMs
        return true
    }
}

/**
 * Aufzeichnung: eigener Track, km pro Land, Offroad-Anteil, Höhenmeter.
 * Punkte werden gepuffert und blockweise in Room geschrieben.
 */
@Singleton
class RideRecorder @Inject constructor(private val db: TetDatabase) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<RecordingState?>(null)
    val state: StateFlow<RecordingState?> = _state

    private var stats = RideStats()
    private var rideId = 0L
    private var idx = 0
    private val buffer = ArrayList<RidePointEntity>()

    val recording: Boolean get() = _state.value != null

    suspend fun start(sectionId: Long?) = mutex.withLock {
        if (_state.value != null) return@withLock
        val now = System.currentTimeMillis()
        rideId = db.rideDao().insert(RideEntity(startedAt = now, endedAt = null, distanceM = 0.0, offroadM = 0.0, ascentM = 0.0, sectionId = sectionId))
        stats = RideStats(); idx = 0; buffer.clear()
        _state.value = RecordingState(rideId, now, 0.0, 0.0, 0.0, 0, 0)
    }

    /** Nach App-Neustart eine offene Fahrt fortsetzen. */
    suspend fun resumeOpen() = mutex.withLock {
        val open = db.rideDao().openRide() ?: return@withLock
        rideId = open.id
        stats = RideStats()
        // Statistik aus gespeicherten Punkten wiederherstellen
        for (p in db.rideDao().points(open.id)) stats.add(p.lat, p.lon, p.ele, p.time, p.speedMs, 0f, p.offroad, p.countryCode)
        idx = (db.rideDao().maxIdx(open.id) ?: -1) + 1
        _state.value = RecordingState(open.id, open.startedAt, stats.distanceM, stats.offroadM, stats.ascentM, stats.movingMs, idx)
    }

    /** @return gefahrene Zusatzdistanz (für die Tank-Reichweite). */
    suspend fun add(lat: Double, lon: Double, ele: Double?, timeMs: Long, speedMs: Float, accuracyM: Float, offroad: Boolean): Double = mutex.withLock {
        val st = _state.value ?: return@withLock 0.0
        val before = stats.distanceM
        val country = Countries.detectFromPoints(sequenceOf(LatLon(lat, lon)))?.code
        if (!stats.add(lat, lon, ele, timeMs, speedMs, accuracyM, offroad, country)) return@withLock 0.0
        buffer += RidePointEntity(rideId, idx++, lat, lon, ele, timeMs, speedMs, country, offroad)
        if (buffer.size >= 30) flush()
        _state.value = st.copy(distanceM = stats.distanceM, offroadM = stats.offroadM, ascentM = stats.ascentM, movingMs = stats.movingMs, points = idx)
        stats.distanceM - before
    }

    private suspend fun flush() {
        if (buffer.isEmpty()) return
        db.rideDao().insertPoints(ArrayList(buffer))
        buffer.clear()
        for ((cc, v) in stats.perCountry) db.rideDao().upsertCountry(RideCountryEntity(rideId, cc, v[0], v[1]))
        db.rideDao().finish(rideId, null, stats.distanceM, stats.offroadM, stats.ascentM, stats.movingMs, stats.offroadMovingMs, "")
    }

    suspend fun stop(title: String? = null): Long? = mutex.withLock {
        val st = _state.value ?: return@withLock null
        flush()
        val name = title ?: defaultTitle(st.startedAt)
        db.rideDao().finish(rideId, System.currentTimeMillis(), stats.distanceM, stats.offroadM, stats.ascentM, stats.movingMs, stats.offroadMovingMs, name)
        if (stats.distanceM < 50) db.rideDao().delete(rideId) // Fehlstart verwerfen
        _state.value = null
        rideId
    }

    private fun defaultTitle(t: Long) = "Fahrt " + SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date(t))
}
