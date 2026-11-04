package de.tetnavigator.ui.drive

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.tetnavigator.core.common.Geo
import de.tetnavigator.core.common.GeoPoint
import de.tetnavigator.core.common.LatLon
import de.tetnavigator.core.database.HazardPinEntity
import de.tetnavigator.core.database.HazardType
import de.tetnavigator.core.database.TetDatabase
import de.tetnavigator.core.offlinedata.OfflineStorage
import de.tetnavigator.core.offlinedata.Poi
import de.tetnavigator.core.offlinedata.PoiCategory
import de.tetnavigator.core.offlinedata.PoiDatabase
import de.tetnavigator.core.preferences.AppSettings
import de.tetnavigator.core.preferences.SettingsRepository
import de.tetnavigator.feature.bedienung.BatteryMonitor
import de.tetnavigator.feature.bedienung.BatteryState
import de.tetnavigator.feature.logbuch.LogbuchRepository
import de.tetnavigator.feature.logbuch.RecordingState
import de.tetnavigator.feature.logbuch.RideRecorder
import de.tetnavigator.feature.logistik.DaylightInfo
import de.tetnavigator.feature.logistik.FuelRange
import de.tetnavigator.feature.logistik.FuelWarning
import de.tetnavigator.feature.logistik.LogistikRepository
import de.tetnavigator.feature.logistik.StationOnTrack
import de.tetnavigator.feature.navigation.ActiveTrack
import de.tetnavigator.feature.navigation.AuxRoute
import de.tetnavigator.feature.navigation.Fix
import de.tetnavigator.feature.navigation.NavState
import de.tetnavigator.feature.navigation.NavigationSession
import de.tetnavigator.ui.AppEvents
import de.tetnavigator.ui.components.ProfileData
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ViewMode { FOLLOW, NORTH_UP, OVERVIEW }

@HiltViewModel
class DriveViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val session: NavigationSession,
    private val recorder: RideRecorder,
    private val settingsRepo: SettingsRepository,
    private val logistik: LogistikRepository,
    private val logbuch: LogbuchRepository,
    private val poiDb: PoiDatabase,
    val storage: OfflineStorage,
    private val db: TetDatabase,
    battery: BatteryMonitor,
    val events: AppEvents,
) : ViewModel(), SensorEventListener {

    val nav: StateFlow<NavState?> = session.state
    val track: StateFlow<ActiveTrack?> = session.track
    val fix: StateFlow<Fix?> = session.lastFix
    val aux: StateFlow<AuxRoute?> = session.auxRoute
    val recording: StateFlow<RecordingState?> = recorder.state
    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val battery: StateFlow<BatteryState> = battery.state
    val hazards: StateFlow<List<HazardPinEntity>> = logbuch.hazards().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _daylight = MutableStateFlow<DaylightInfo?>(null)
    val daylight: StateFlow<DaylightInfo?> = _daylight
    private val _fuel = MutableStateFlow<FuelWarning>(FuelWarning.Ok)
    val fuel: StateFlow<FuelWarning> = _fuel
    private val _pois = MutableStateFlow<List<Poi>>(emptyList())
    val pois: StateFlow<List<Poi>> = _pois
    private val _profile = MutableStateFlow(ProfileData(DoubleArray(0), DoubleArray(0)))
    val profile: StateFlow<ProfileData> = _profile
    private val _allTracks = MutableStateFlow<List<List<LatLon>>>(emptyList())
    val allTracks: StateFlow<List<List<LatLon>>> = _allTracks
    val viewMode = MutableStateFlow(ViewMode.FOLLOW)
    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast: SharedFlow<String> = _toast
    val busy = MutableStateFlow(false)

    /** Kompass-Kurs für Heading-up im Stand. */
    private val _compass = MutableStateFlow<Float?>(null)
    val compass: StateFlow<Float?> = _compass
    private val sensors = context.getSystemService(SensorManager::class.java)

    private var stations: List<StationOnTrack> = emptyList()
    private var poiCenter: GeoPoint? = null

    init {
        sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        // Profil + Tankstellen je Sektion
        viewModelScope.launch {
            track.collect { t ->
                if (t == null) { _profile.value = ProfileData(DoubleArray(0), DoubleArray(0)); stations = emptyList(); return@collect }
                _profile.value = ProfileData.of(t.vertices.map { it.distM }, t.vertices.map { it.ele })
                stations = runCatching {
                    val pts = t.vertices.map { GeoPoint(it.lat, it.lon) }
                    logistik.stationsAlong(pts, t.vertices.map { it.distM }.toDoubleArray())
                }.getOrDefault(emptyList())
            }
        }
        // Tank-Warnung bei jeder Positionsänderung
        viewModelScope.launch {
            nav.collect { st ->
                val s = settings.value
                val range = FuelRange.rangeM(s.fuelRemainingL, s.consumptionLPer100)
                val full = FuelRange.rangeM(s.tankLiters, s.consumptionLPer100)
                _fuel.value = if (st == null) {
                    if (range < full * 0.25) FuelWarning.Low(range) else FuelWarning.Ok
                } else FuelRange.evaluate(st.match?.alongM ?: 0.0, st.track.lengthM, range, full, stations)
            }
        }
        // Tageslicht jede Minute
        viewModelScope.launch {
            while (true) {
                val f = fix.value
                if (f != null) _daylight.value = runCatching { logistik.daylight(f.lat, f.lon) }.getOrNull()
                delay(if (f == null) 5_000 else 60_000)
            }
        }
        // POIs im Umkreis (bei > 2 km Bewegung neu)
        viewModelScope.launch {
            fix.collect { f ->
                f ?: return@collect
                val c = poiCenter
                if (c == null || Geo.distance(c.lat, c.lon, f.lat, f.lon) > 2_000) {
                    poiCenter = GeoPoint(f.lat, f.lon)
                    _pois.value = runCatching { poiDb.near(f.lat, f.lon, 15_000.0, PoiCategory.entries.toSet()).take(300) }.getOrDefault(emptyList())
                }
            }
        }
        // Alle importierten Sektionen dünn im Hintergrund (Übersicht ohne aktive Sektion)
        viewModelScope.launch {
            _allTracks.value = runCatching {
                db.trackDao().allSections().take(400).map { s ->
                    val pts = db.trackDao().points(s.id)
                    val step = (pts.size / 300).coerceAtLeast(1)
                    pts.filterIndexed { i, _ -> i % step == 0 || i == pts.lastIndex }.map { LatLon(it.lat, it.lon) }
                }
            }.getOrDefault(emptyList())
        }
    }

    fun toggleRecording() = viewModelScope.launch {
        if (recorder.recording) {
            recorder.stop()
            _toast.tryEmit("Aufzeichnung gespeichert")
        } else {
            recorder.start(track.value?.sectionId)
            _toast.tryEmit("Aufzeichnung läuft")
        }
    }

    fun addHazard(type: String, note: String, photo: String?) = viewModelScope.launch {
        val f = fix.value
        if (f == null) { _toast.emit("Noch keine GPS-Position"); return@launch }
        logbuch.addHazard(f.lat, f.lon, type, note, photo)
        _toast.tryEmit("Gefahren-Pin gesetzt")
    }

    fun addNote(text: String, photo: String?) = viewModelScope.launch {
        val f = fix.value
        if (f == null) { _toast.emit("Noch keine GPS-Position"); return@launch }
        logbuch.addJournal(recorder.state.value?.rideId, f.lat, f.lon, text, photo)
        _toast.tryEmit("Notiz gespeichert")
    }

    /** Remote „Wegpunkt“: schneller Tagebuch-Eintrag ohne Bedienung. */
    fun quickWaypoint() = addNote("Wegpunkt", null)

    fun reverse() = viewModelScope.launch { session.reverse(); _toast.tryEmit("Richtung umgekehrt") }
    fun stopNavigation() = viewModelScope.launch { session.stop() }

    fun backToTrail(toStart: Boolean) = viewModelScope.launch {
        busy.value = true
        try {
            val r = session.routeBackToTrail(toStart)
            _toast.tryEmit("Route: ${de.tetnavigator.core.common.Format.distance(r.distanceM)}")
            viewMode.value = ViewMode.OVERVIEW
        } catch (e: Exception) {
            _toast.tryEmit(e.message ?: "Keine Route")
        } finally { busy.value = false }
    }

    /** Abschnitt ab aktueller Position als gesperrt markieren und umfahren. */
    fun blockAhead(lengthM: Double) = viewModelScope.launch {
        val st = nav.value
        if (st == null) { _toast.emit("Keine aktive Sektion"); return@launch }
        val from = (st.match?.alongM ?: 0.0) + 50
        busy.value = true
        try {
            val r = session.blockAndDetour(from, (from + lengthM).coerceAtMost(st.track.lengthM), "Gesperrt")
            _toast.tryEmit("Umleitung: ${de.tetnavigator.core.common.Format.distance(r.distanceM)}")
        } catch (e: Exception) {
            _toast.tryEmit(e.message ?: "Keine Umleitung gefunden")
        } finally { busy.value = false }
    }

    fun clearAux() = session.clearAuxRoute()

    fun refuel() = viewModelScope.launch {
        settingsRepo.update { it.copy(fuelRemainingL = it.tankLiters) }
        _toast.tryEmit("Tank voll")
    }

    fun setLayer(block: (AppSettings) -> AppSettings) = viewModelScope.launch { settingsRepo.update(block) }

    fun cycleView() {
        viewMode.value = ViewMode.entries[(viewMode.value.ordinal + 1) % ViewMode.entries.size]
    }

    fun hazardTypes() = HazardType.all

    override fun onSensorChanged(e: SensorEvent) {
        val rot = FloatArray(9); val o = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        SensorManager.getOrientation(rot, o)
        _compass.value = Geo.normalize360(Math.toDegrees(o[0].toDouble())).toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onCleared() {
        sensors?.unregisterListener(this)
    }
}
