package de.tetnavigator.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.tetnavigator.core.common.GeoPoint
import de.tetnavigator.core.database.CountrySummary
import de.tetnavigator.core.database.SectionEntity
import de.tetnavigator.core.database.SectionWithProgress
import de.tetnavigator.core.database.TetDatabase
import de.tetnavigator.core.preferences.AppSettings
import de.tetnavigator.core.preferences.SettingsRepository
import de.tetnavigator.feature.importer.ImportRepository
import de.tetnavigator.feature.importer.SurfaceAnalyzer
import de.tetnavigator.feature.logistik.LogistikRepository
import de.tetnavigator.feature.logistik.SectionChecks
import de.tetnavigator.feature.navigation.NavigationSession
import de.tetnavigator.ui.components.ProfileData
import de.tetnavigator.ui.components.SurfaceShare
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryUi(val countries: List<CountrySummary> = emptyList(), val settings: AppSettings = AppSettings()) {
    val sectionCount get() = countries.sumOf { it.sectionCount }
}

@HiltViewModel
class LibraryViewModel @Inject constructor(db: TetDatabase, settings: SettingsRepository) : ViewModel() {
    val ui: StateFlow<LibraryUi> = combine(db.trackDao().observeCountrySummaries(), settings.settings) { c, s -> LibraryUi(c, s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUi())
}

@HiltViewModel
class CountryViewModel @Inject constructor(
    private val db: TetDatabase,
    private val importer: ImportRepository,
    settings: SettingsRepository,
) : ViewModel() {
    private val cc = MutableStateFlow("")
    val settings = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    private val _sections = MutableStateFlow<List<SectionWithProgress>>(emptyList())
    val sections: StateFlow<List<SectionWithProgress>> = _sections
    val files = db.trackDao().observeFiles().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun load(code: String) {
        if (cc.value == code) return
        cc.value = code
        viewModelScope.launch { db.trackDao().observeSectionsOfCountry(code).collect { _sections.value = it } }
    }

    fun deleteFile(id: Long) = viewModelScope.launch { importer.deleteFile(id) }
}

data class SectionUi(
    val section: SectionEntity? = null,
    val profile: ProfileData = ProfileData(DoubleArray(0), DoubleArray(0)),
    val surface: SurfaceShare? = null,
    val checks: SectionChecks? = null,
    val riddenM: Double = 0.0,
    val ridden: Boolean = false,
    val fileDate: Long = 0,
    val reversedPreview: Boolean = false,
    val analyzing: Boolean = false,
)

@HiltViewModel
class SectionViewModel @Inject constructor(
    private val db: TetDatabase,
    private val logistik: LogistikRepository,
    private val surface: SurfaceAnalyzer,
    private val session: NavigationSession,
) : ViewModel() {
    private val _ui = MutableStateFlow(SectionUi())
    val ui: StateFlow<SectionUi> = _ui

    fun load(id: Long) {
        if (_ui.value.section?.id == id) return
        viewModelScope.launch {
            val dao = db.trackDao()
            val s = dao.section(id) ?: return@launch
            val pts = dao.points(id)
            val prog = db.progressDao().get(id)
            val file = dao.file(s.trackFileId)
            _ui.value = SectionUi(
                section = s,
                profile = ProfileData.of(pts.map { it.distM }, pts.map { it.ele }),
                surface = s.surfaceShare(),
                riddenM = prog?.rideDistanceM ?: 0.0,
                ridden = prog?.ridden == true,
                fileDate = file?.fileDate ?: 0,
            )
            if (s.gravelM < 0) {
                _ui.value = _ui.value.copy(analyzing = true)
                val st = runCatching { surface.analyze(id) }.getOrNull()
                _ui.value = _ui.value.copy(analyzing = false, surface = st?.let { SurfaceShare(it.gravelM, it.dirtM, it.asphaltM, it.unknownM) })
            }
            refreshChecks(false)
        }
    }

    fun refreshChecks(reversed: Boolean) = viewModelScope.launch {
        val s = _ui.value.section ?: return@launch
        val here = session.lastFix.value?.let { GeoPoint(it.lat, it.lon) }
        _ui.value = _ui.value.copy(checks = runCatching { logistik.sectionChecks(s.id, reversed, here) }.getOrNull())
    }

    suspend fun start(reversed: Boolean) {
        val s = _ui.value.section ?: return
        session.start(s.id, reversed)
    }

    private fun SectionEntity.surfaceShare(): SurfaceShare? =
        if (gravelM < 0) null else SurfaceShare(gravelM, dirtM, asphaltM, unknownM)
}
