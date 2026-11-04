package de.tetnavigator.feature.navigation

import de.tetnavigator.core.common.LatLon
import de.tetnavigator.core.database.BlockedRangeEntity
import de.tetnavigator.core.database.ProgressEntity
import de.tetnavigator.core.database.SpatialIndex
import de.tetnavigator.core.database.TetDatabase
import de.tetnavigator.core.preferences.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

sealed interface NavEvent {
    /** Abweichung > Schwellwert länger als 5 s → Ton + Vibration. */
    data object DeviationAlarm : NavEvent
    data object BackOnTrack : NavEvent
    data class TurnAhead(val maneuver: Maneuver) : NavEvent
    data object SectionFinished : NavEvent
}

/** Hilfsroute (Zurück zum Trail / Umfahrung) auf der Karte. */
data class AuxRoute(val kind: Kind, val route: Route) {
    enum class Kind { BACK_TO_TRAIL, DETOUR }
}

/**
 * Laufende Navigationssitzung (App-weit, vom Location-Service gefüttert).
 */
@Singleton
class NavigationSession @Inject constructor(
    private val db: TetDatabase,
    private val spatial: SpatialIndex,
    private val settings: SettingsRepository,
    private val router: OfflineRouter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _track = MutableStateFlow<ActiveTrack?>(null)
    val track: StateFlow<ActiveTrack?> = _track

    private val _state = MutableStateFlow<NavState?>(null)
    val state: StateFlow<NavState?> = _state

    private val _lastFix = MutableStateFlow<Fix?>(null)
    val lastFix: StateFlow<Fix?> = _lastFix

    private val _aux = MutableStateFlow<AuxRoute?>(null)
    val auxRoute: StateFlow<AuxRoute?> = _aux

    private val _events = MutableSharedFlow<NavEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<NavEvent> = _events

    private var engine: NavigationEngine? = null
    private var lastSavedAlong = 0.0
    private var lastAnnouncedTurn: Double = -1.0
    private var wasOff = false

    /** Sektion laden und Navigation starten. */
    suspend fun start(sectionId: Long, reversed: Boolean) = mutex.withLock {
        val dao = db.trackDao()
        val s = dao.section(sectionId) ?: return@withLock
        val pts = dao.points(sectionId).map { TrackVertex(it.lat, it.lon, it.ele, it.distM, it.surface, it.changed) }
        val t = ActiveTrack.build(sectionId, s.name, s.countryCode, reversed, pts)
        val cfg = settings.current()
        engine = NavigationEngine(t, cfg.deviationThresholdM.toDouble())
        _track.value = t
        _state.value = null
        _aux.value = null
        lastAnnouncedTurn = -1.0
        val prog = db.progressDao().get(sectionId)
        lastSavedAlong = if (reversed) 0.0 else prog?.rideDistanceM ?: 0.0
        settings.update { it.copy(activeSectionId = sectionId, activeReversed = reversed) }
    }

    suspend fun resumeIfActive() {
        val cfg = settings.current()
        val id = cfg.activeSectionId ?: return
        if (_track.value == null) start(id, cfg.activeReversed)
    }

    suspend fun stop() = mutex.withLock {
        engine = null
        _track.value = null
        _state.value = null
        _aux.value = null
        settings.update { it.copy(activeSectionId = null) }
    }

    /** Richtung umkehren (Sektion rückwärts fahren). */
    suspend fun reverse() {
        val t = _track.value ?: return
        start(t.sectionId, !t.reversed)
    }

    fun onFix(fix: Fix) {
        _lastFix.value = fix
        val e = engine ?: return
        scope.launch {
            mutex.withLock {
                if (engine !== e) return@withLock
                e.setThreshold(settings.current().deviationThresholdM.toDouble())
                val st = e.update(fix)
                _state.value = st
                if (st.alarmTriggered) { _events.tryEmit(NavEvent.DeviationAlarm); wasOff = true }
                if (wasOff && st.deviation == DeviationMonitor.State.ON_TRACK) {
                    wasOff = false; _events.tryEmit(NavEvent.BackOnTrack)
                    if (_aux.value?.kind == AuxRoute.Kind.BACK_TO_TRAIL) _aux.value = null
                }
                val m = st.maneuver
                if ((m.kind == ManeuverKind.HAIRPIN || m.kind == ManeuverKind.CURVE) && m.distanceM < 150) {
                    val at = (st.match?.alongM ?: 0.0) + m.distanceM
                    if (kotlin.math.abs(at - lastAnnouncedTurn) > 20) { lastAnnouncedTurn = at; _events.tryEmit(NavEvent.TurnAhead(m)) }
                }
                persistProgress(st)
            }
        }
    }

    private suspend fun persistProgress(st: NavState) {
        val t = st.track
        if (t.reversed) {
            if (st.finished) markRidden(t.sectionId, t.lengthM)
            return
        }
        val along = e().maxAlongM
        if (st.finished) {
            markRidden(t.sectionId, t.lengthM)
            _events.tryEmit(NavEvent.SectionFinished)
        } else if (along - lastSavedAlong > 100) {
            lastSavedAlong = along
            val old = db.progressDao().get(t.sectionId)
            db.progressDao().upsert(
                ProgressEntity(t.sectionId, old?.ridden == true, maxOf(old?.rideDistanceM ?: 0.0, along), System.currentTimeMillis()),
            )
        }
    }

    private fun e() = engine!!

    private suspend fun markRidden(sectionId: Long, length: Double) {
        val old = db.progressDao().get(sectionId)
        if (old?.ridden == true) return
        db.progressDao().upsert(ProgressEntity(sectionId, true, length, System.currentTimeMillis()))
    }

    /** Zurück zum Trail: Route zum nächsten Trackpunkt voraus bzw. zum Sektionsstart. */
    suspend fun routeBackToTrail(toSectionStart: Boolean = false): Route {
        val fix = _lastFix.value ?: throw RoutingUnavailableException("Noch keine GPS-Position")
        val t = _track.value
        val target: LatLon = when {
            t != null && toSectionStart -> t.vertices.first().let { LatLon(it.lat, it.lon) }
            t != null -> {
                val m = _state.value?.match
                // Ziel etwas voraus, damit man nicht zurückgeschickt wird.
                val p = t.pointAt(((m?.alongM ?: 0.0) + 300).coerceAtMost(t.lengthM))
                LatLon(p.lat, p.lon)
            }
            else -> spatial.nearest(fix.lat, fix.lon)?.let { LatLon(it.lat, it.lon) }
                ?: throw RoutingUnavailableException("Kein Track in der Nähe")
        }
        val r = router.route(LatLon(fix.lat, fix.lon), target)
        _aux.value = AuxRoute(AuxRoute.Kind.BACK_TO_TRAIL, r)
        return r
    }

    /** Abschnitt als gesperrt markieren und Umleitung berechnen. */
    suspend fun blockAndDetour(startM: Double, endM: Double, note: String): Route {
        val t = _track.value ?: throw RoutingUnavailableException("Keine aktive Sektion")
        val (a, b) = if (t.reversed) (t.lengthM - endM) to (t.lengthM - startM) else startM to endM
        db.blockedRangeDao().insert(BlockedRangeEntity(sectionId = t.sectionId, startM = a, endM = b, note = note, createdAt = System.currentTimeMillis()))
        val r = router.detour(t, startM, endM)
        _aux.value = AuxRoute(AuxRoute.Kind.DETOUR, r)
        return r
    }

    fun clearAuxRoute() { _aux.value = null }

    val routingAvailable: Boolean get() = router.available
}
