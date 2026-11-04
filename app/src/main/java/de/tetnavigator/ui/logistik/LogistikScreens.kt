package de.tetnavigator.ui.logistik

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import dagger.hilt.android.lifecycle.HiltViewModel
import de.tetnavigator.R
import de.tetnavigator.core.common.Format
import de.tetnavigator.core.database.StageItemEntity
import de.tetnavigator.core.database.TetDatabase
import de.tetnavigator.core.designsystem.Panel
import de.tetnavigator.core.designsystem.PrimaryButton
import de.tetnavigator.core.designsystem.ScreenHeader
import de.tetnavigator.core.designsystem.SecondaryButton
import de.tetnavigator.core.designsystem.SectionLabel
import de.tetnavigator.core.designsystem.StepperRow
import de.tetnavigator.core.designsystem.TetIconButton
import de.tetnavigator.core.designsystem.TetIcons
import de.tetnavigator.core.designsystem.TetTheme
import de.tetnavigator.core.offlinedata.PoiCategory
import de.tetnavigator.feature.logistik.DayStage
import de.tetnavigator.feature.logistik.LogistikRepository
import de.tetnavigator.feature.logistik.PoiHit
import de.tetnavigator.feature.logistik.StagePlanner
import de.tetnavigator.feature.logistik.StageSection
import de.tetnavigator.feature.navigation.NavigationSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---------------------------------------------------------------- POIs ----

@HiltViewModel
class PoiViewModel @Inject constructor(private val repo: LogistikRepository, private val session: NavigationSession) : ViewModel() {
    val category = MutableStateFlow<PoiCategory?>(null)
    private val _hits = MutableStateFlow<List<PoiHit>>(emptyList())
    val hits: StateFlow<List<PoiHit>> = _hits
    val hasData get() = repo.hasPoiData
    val hasFix get() = session.lastFix.value != null

    fun load() = viewModelScope.launch {
        val f = session.lastFix.value ?: return@launch
        val cats = category.value?.let { setOf(it) } ?: PoiCategory.entries.toSet()
        _hits.value = repo.poisNear(f.lat, f.lon, 30_000.0, cats).take(100)
    }

    fun select(c: PoiCategory?) { category.value = c; load() }
}

/** Camping & Versorgung (Offline-POIs). */
@Composable
fun PoiScreen(nav: NavHostController, vm: PoiViewModel = hiltViewModel()) {
    val hits by vm.hits.collectAsStateWithLifecycle()
    val cat by vm.category.collectAsStateWithLifecycle()
    val c = TetTheme.colors
    LaunchedEffect(Unit) { vm.load() }
    val icons = mapOf(
        PoiCategory.CAMPSITE to TetIcons.Tent, PoiCategory.HUT to TetIcons.Hut, PoiCategory.WATER to TetIcons.Water,
        PoiCategory.SUPERMARKET to TetIcons.Cart, PoiCategory.WORKSHOP to TetIcons.Wrench, PoiCategory.FUEL to TetIcons.Fuel,
    )
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.pois), subtitle = stringResource(R.string.pois_sub), onBack = { nav.popBackStack() })
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TetIconButton(TetIcons.Layers, stringResource(R.string.all), { vm.select(null) }, active = cat == null)
            PoiCategory.entries.forEach { pc -> TetIconButton(icons.getValue(pc), pc.labelDe, { vm.select(pc) }, active = cat == pc) }
        }
        when {
            !vm.hasData -> Text(stringResource(R.string.no_poi_data), Modifier.padding(16.dp), style = TetTheme.type.body, color = c.sand)
            !vm.hasFix -> Text(stringResource(R.string.waiting_gps), Modifier.padding(16.dp), style = TetTheme.type.body, color = c.sand)
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(hits, key = { "${it.poi.country}-${it.poi.id}" }) { h ->
                Panel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icons.getValue(h.poi.category), null, Modifier.size(30.dp), tint = c.accent)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.poi.name.ifBlank { h.poi.category.labelDe }, style = TetTheme.type.bodyStrong, color = c.text)
                            Text(h.poi.category.labelDe, style = TetTheme.type.bodySmall, color = c.sand)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Icon(TetIcons.Navigate, null, Modifier.size(26.dp).rotate(h.bearing.toFloat()), tint = c.sand)
                            Text(Format.distance(h.distanceM), style = TetTheme.type.bodyStrong, color = c.text)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------- Etappen ----

data class StageUi(
    val days: List<DayStage> = emptyList(),
    val unplanned: List<StageSection> = emptyList(),
    val avgKmh: Double = 25.0,
    val measured: Boolean = false,
    val hoursPerDay: Double = 6.0,
)

@HiltViewModel
class StagePlannerViewModel @Inject constructor(private val db: TetDatabase, private val repo: LogistikRepository) : ViewModel() {
    private val _ui = MutableStateFlow(StageUi())
    val ui: StateFlow<StageUi> = _ui
    private var all: Map<Long, StageSection> = emptyMap()

    init { reload() }

    private fun reload() = viewModelScope.launch {
        all = db.trackDao().allSections().associate { it.id to StageSection(it.id, it.name, it.countryCode, it.lengthM, it.ascentM, false) }
        val items = db.stageDao().all()
        val (avg, measured) = repo.averageKmh()
        val planned = items.mapNotNull { i -> all[i.sectionId]?.let { i.dayIndex to it.copy(reversed = i.reversed) } }
        _ui.value = _ui.value.copy(
            days = StagePlanner.group(planned),
            unplanned = all.values.filter { s -> items.none { it.sectionId == s.sectionId } },
            avgKmh = avg, measured = measured,
        )
    }

    private fun save(days: List<DayStage>) = viewModelScope.launch {
        val items = days.filter { it.sections.isNotEmpty() }.flatMapIndexed { d, day ->
            day.sections.mapIndexed { i, s -> StageItemEntity(s.sectionId, d, i, s.reversed) }
        }
        db.stageDao().replaceAll(items)
        reload()
    }

    fun addToLastDay(s: StageSection) {
        val days = _ui.value.days.toMutableList()
        if (days.isEmpty()) days += DayStage(0, emptyList())
        days[days.lastIndex] = days.last().copy(sections = days.last().sections + s)
        save(days)
    }

    fun newDay() {
        val days = _ui.value.days.toMutableList(); days += DayStage(days.size, emptyList())
        _ui.value = _ui.value.copy(days = days)
    }

    fun move(day: Int, idx: Int, delta: Int) {
        // Sektion einen Platz nach oben/unten; über Tagesgrenzen hinweg in den Nachbartag
        val days = _ui.value.days.map { it.sections.toMutableList() }.toMutableList()
        val s = days[day].removeAt(idx)
        val target = idx + delta
        when {
            target < 0 && day > 0 -> days[day - 1].add(s)
            target > days[day].size && day < days.lastIndex -> days[day + 1].add(0, s)
            else -> days[day].add(target.coerceIn(0, days[day].size), s)
        }
        save(days.mapIndexed { i, l -> DayStage(i, l) })
    }

    fun remove(day: Int, idx: Int) {
        val days = _ui.value.days.map { it.sections.toMutableList() }
        days[day].removeAt(idx)
        save(days.mapIndexed { i, l -> DayStage(i, l) })
    }

    fun autoPlan() {
        val sections = (_ui.value.days.flatMap { it.sections } + _ui.value.unplanned)
        save(StagePlanner.suggest(sections, _ui.value.avgKmh, _ui.value.hoursPerDay))
    }

    fun stepHours(d: Double) { _ui.value = _ui.value.copy(hoursPerDay = (_ui.value.hoursPerDay + d).coerceIn(2.0, 12.0)) }
}

/** Etappenplaner: Sektionen zu Tagesetappen, Zeit nach eigenem Offroad-Schnitt. */
@Composable
fun StagePlannerScreen(nav: NavHostController, vm: StagePlannerViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val c = TetTheme.colors
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            stringResource(R.string.stages),
            subtitle = stringResource(if (ui.measured) R.string.avg_measured else R.string.avg_fallback, ui.avgKmh.toInt()),
            onBack = { nav.popBackStack() },
        )
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                StepperRow(stringResource(R.string.hours_per_day), "${ui.hoursPerDay.toInt()} h", { vm.stepHours(-1.0) }, { vm.stepHours(1.0) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(stringResource(R.string.auto_plan), { vm.autoPlan() }, Modifier.weight(1f))
                    SecondaryButton(stringResource(R.string.new_day), { vm.newDay() }, Modifier.weight(1f), icon = TetIcons.Plus)
                }
            }
            ui.days.forEachIndexed { d, day ->
                item(key = "day$d") {
                    SectionLabel(stringResource(R.string.day_n, d + 1, Format.km0(day.lengthM), Format.hm(day.durationMs(ui.avgKmh)), Format.thousands(day.ascentM.toLong())))
                }
                day.sections.forEachIndexed { i, s ->
                    item(key = "d$d-${s.sectionId}") {
                        Panel(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                                    Text("${s.countryCode} · ${s.name}", style = TetTheme.type.bodyStrong, color = c.text)
                                    Text("${Format.km0(s.lengthM)} km · ${Format.hm((s.lengthM / 1000 / ui.avgKmh * 3_600_000).toLong())} h", style = TetTheme.type.bodySmall, color = c.sand)
                                }
                                TetIconButton(TetIcons.Back, stringResource(R.string.move_up), { vm.move(d, i, -1) }, Modifier.rotate(90f))
                                Spacer(Modifier.width(6.dp))
                                TetIconButton(TetIcons.Forward, stringResource(R.string.move_down), { vm.move(d, i, 1) }, Modifier.rotate(90f))
                                Spacer(Modifier.width(6.dp))
                                TetIconButton(TetIcons.Close, stringResource(R.string.remove), { vm.remove(d, i) })
                            }
                        }
                    }
                }
            }
            if (ui.unplanned.isNotEmpty()) item { SectionLabel(stringResource(R.string.unplanned)) }
            items(ui.unplanned, key = { "u${it.sectionId}" }) { s ->
                Panel(Modifier.fillMaxWidth(), onClick = { vm.addToLastDay(s) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${s.countryCode} · ${s.name}", style = TetTheme.type.bodyStrong, color = c.text)
                            Text("${Format.km0(s.lengthM)} km", style = TetTheme.type.bodySmall, color = c.sand)
                        }
                        Icon(TetIcons.Plus, null, Modifier.size(28.dp), tint = c.accent)
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
