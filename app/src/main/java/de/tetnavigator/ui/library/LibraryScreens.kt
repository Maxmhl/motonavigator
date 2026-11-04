package de.tetnavigator.ui.library

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import de.tetnavigator.R
import de.tetnavigator.core.common.Countries
import de.tetnavigator.core.common.Format
import de.tetnavigator.core.designsystem.ButtonKind
import de.tetnavigator.core.designsystem.ChipKind
import de.tetnavigator.core.designsystem.CountryBadge
import de.tetnavigator.core.designsystem.DashedBox
import de.tetnavigator.core.designsystem.Panel
import de.tetnavigator.core.designsystem.PrimaryButton
import de.tetnavigator.core.designsystem.ScreenHeader
import de.tetnavigator.core.designsystem.SecondaryButton
import de.tetnavigator.core.designsystem.SectionLabel
import de.tetnavigator.core.designsystem.StatusChip
import de.tetnavigator.core.designsystem.StatusDot
import de.tetnavigator.core.designsystem.TetButton
import de.tetnavigator.core.designsystem.TetIcons
import de.tetnavigator.core.designsystem.TetProgressBar
import de.tetnavigator.core.designsystem.TetTheme
import de.tetnavigator.feature.importer.GpxAge
import de.tetnavigator.feature.logistik.CheckLevel
import de.tetnavigator.ui.Routes
import de.tetnavigator.ui.components.ElevationProfile
import de.tetnavigator.ui.components.SurfaceBar
import de.tetnavigator.ui.go
import kotlinx.coroutines.launch

/** 2 · Track-Bibliothek */
@Composable
fun LibraryScreen(nav: NavHostController, vm: LibraryViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val c = TetTheme.colors
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            stringResource(R.string.tracks_title),
            subtitle = stringResource(R.string.tracks_subtitle, ui.countries.size, ui.sectionCount),
            action = { PrimaryButton(stringResource(R.string.add_gpx), { nav.navigate(Routes.IMPORT) }, Modifier.width(130.dp)) },
        )
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Spacer(Modifier.height(2.dp)) }
            if (ui.countries.isEmpty()) {
                item {
                    Panel {
                        Text(stringResource(R.string.tracks_empty), style = TetTheme.type.body, color = c.text)
                    }
                }
            }
            items(ui.countries, key = { it.countryCode }) { s ->
                val frac = if (s.totalLengthM > 0) (s.riddenM / s.totalLengthM).toFloat() else 0f
                val old = s.oldestFileDate?.let { GpxAge.isOld(it, ui.settings.gpxMaxAgeDays) } == true
                Panel(onClick = { nav.navigate(Routes.country(s.countryCode)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CountryBadge(s.countryCode, frac > 0)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(Countries.name(s.countryCode), style = TetTheme.type.bodyStrong.copy(fontSize = TetTheme.type.title.fontSize * 0.75f), color = c.text, modifier = Modifier.weight(1f))
                                Text(stringResource(R.string.sections_count, s.sectionCount), style = TetTheme.type.bodySmall, color = c.sand)
                            }
                            Spacer(Modifier.height(6.dp))
                            TetProgressBar(frac)
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (frac > 0) stringResource(R.string.percent_ridden, (frac * 100).toInt()) else stringResource(R.string.not_ridden),
                                    style = TetTheme.type.bodySmall, color = c.sand, modifier = Modifier.weight(1f),
                                )
                                if (old) StatusChip(stringResource(R.string.gpx_days_old, GpxAge.ageDays(s.oldestFileDate!!)), ChipKind.Warning)
                                else Text(stringResource(R.string.gpx_current).uppercase(), style = TetTheme.type.label, color = c.moss)
                            }
                        }
                    }
                }
            }
            item {
                DashedBox(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tracks_share_hint), style = TetTheme.type.bodySmall, color = c.sand, textAlign = TextAlign.Center)
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

/** Sektionsliste eines Landes. */
@Composable
fun CountryScreen(nav: NavHostController, cc: String, vm: CountryViewModel = hiltViewModel()) {
    LaunchedEffect(cc) { vm.load(cc) }
    val sections by vm.sections.collectAsStateWithLifecycle()
    val files by vm.files.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val c = TetTheme.colors
    var confirmDelete by remember { mutableStateOf<Long?>(null) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            Countries.name(cc),
            subtitle = stringResource(R.string.country_subtitle, sections.size, Format.km0(sections.sumOf { it.lengthM }), Format.thousands(sections.sumOf { it.ascentM }.toLong())),
            onBack = { nav.popBackStack() },
        )
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Spacer(Modifier.height(2.dp)) }
            items(sections, key = { it.id }) { s ->
                val frac = when {
                    s.ridden == true -> 1f
                    s.lengthM > 0 -> ((s.rideDistanceM ?: 0.0) / s.lengthM).toFloat().coerceIn(0f, 1f)
                    else -> 0f
                }
                Panel(onClick = { nav.navigate(Routes.section(s.id)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, style = TetTheme.type.bodyStrong, color = c.text, modifier = Modifier.weight(1f))
                        if (s.changedRanges.isNotEmpty()) StatusChip(stringResource(R.string.changed), ChipKind.Warning)
                        if (s.ridden == true) { Spacer(Modifier.width(6.dp)); StatusChip(stringResource(R.string.ridden), ChipKind.Ok) }
                    }
                    Text("${Format.km1(s.lengthM)} km · +${Format.thousands(s.ascentM.toLong())} hm", style = TetTheme.type.bodySmall, color = c.sand)
                    Spacer(Modifier.height(6.dp))
                    TetProgressBar(frac)
                }
            }
            item {
                SectionLabel(stringResource(R.string.files))
            }
            items(files.filter { it.countryCode == cc }, key = { "f${it.id}" }) { f ->
                val old = GpxAge.isOld(f.fileDate, settings.gpxMaxAgeDays)
                Panel {
                    Text(f.fileName, style = TetTheme.type.bodyStrong, color = c.text)
                    Text(
                        stringResource(R.string.file_info, java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, java.util.Locale.GERMANY).format(java.util.Date(f.fileDate)), GpxAge.ageDays(f.fileDate)),
                        style = TetTheme.type.bodySmall, color = if (old) c.warning else c.sand,
                    )
                    if (f.changedSegments > 0) Text(stringResource(R.string.file_changed, f.changedSegments), style = TetTheme.type.bodySmall, color = c.warning)
                    Spacer(Modifier.height(8.dp))
                    if (confirmDelete == f.id) {
                        TetButton(stringResource(R.string.delete_hold), { vm.deleteFile(f.id); confirmDelete = null }, Modifier.fillMaxWidth(), kind = ButtonKind.Danger, requireLongPress = true)
                    } else {
                        SecondaryButton(stringResource(R.string.delete_file), { confirmDelete = f.id }, Modifier.fillMaxWidth(), icon = TetIcons.Trash)
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/** 4 · Sektions-Details */
@Composable
fun SectionScreen(nav: NavHostController, id: Long, vm: SectionViewModel = hiltViewModel()) {
    LaunchedEffect(id) { vm.load(id) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val s = ui.section
    val c = TetTheme.colors
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        ScreenHeader(
            s?.name ?: "…",
            subtitle = s?.let { stringResource(R.string.section_subtitle, Countries.name(it.countryCode), Format.km0(it.lengthM), Format.thousands(it.ascentM.toLong())) },
            onBack = { nav.popBackStack() },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)) {
            Panel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.elevation_profile).uppercase(), style = TetTheme.type.label, color = c.sand)
                Spacer(Modifier.height(8.dp))
                ElevationProfile(ui.profile, currentM = ui.riddenM.takeIf { it > 0 && !ui.ridden })
            }
            Spacer(Modifier.height(10.dp))
            Panel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.surface).uppercase(), style = TetTheme.type.label, color = c.sand)
                Spacer(Modifier.height(8.dp))
                when {
                    ui.analyzing -> Text(stringResource(R.string.surface_analyzing), style = TetTheme.type.bodySmall, color = c.sand)
                    ui.surface != null -> SurfaceBar(ui.surface!!)
                    else -> Text(stringResource(R.string.surface_none), style = TetTheme.type.bodySmall, color = c.sand)
                }
            }
            SectionLabel(stringResource(R.string.checks_before_start))
            val ch = ui.checks
            CheckRow(ch?.fuel, stringResource(R.string.check_fuel), ch?.fuelText ?: "…")
            Spacer(Modifier.height(8.dp))
            CheckRow(ch?.light, stringResource(R.string.check_light), ch?.lightText ?: "…")
            Spacer(Modifier.height(8.dp))
            CheckRow(ch?.let { if (it.hazards == 0) CheckLevel.OK else CheckLevel.BAD }, stringResource(R.string.check_hazards), ch?.hazardText ?: "…")
            if (s != null && s.changedRanges.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Panel(borderColor = c.warning) { Text(stringResource(R.string.section_changed_hint), style = TetTheme.type.bodySmall, color = c.warning) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(stringResource(R.string.reverse), {
                scope.launch { vm.start(true); startService(ctx); nav.go(Routes.MAP) }
            }, Modifier.weight(1f), icon = TetIcons.Reverse)
            PrimaryButton(stringResource(R.string.go), {
                scope.launch { vm.start(false); startService(ctx); nav.go(Routes.MAP) }
            }, Modifier.weight(1.4f), enabled = s != null)
        }
    }
}

private fun startService(ctx: Context) {
    de.tetnavigator.location.LocationService.start(ctx)
}

@Composable
private fun CheckRow(level: CheckLevel?, title: String, text: String) {
    val c = TetTheme.colors
    val col: Color = when (level) { CheckLevel.OK -> c.moss; CheckLevel.WARN -> c.warning; CheckLevel.BAD -> c.danger; null -> c.outline }
    Panel(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(col)
            Spacer(Modifier.width(12.dp))
            Text("$title: ", style = TetTheme.type.bodyStrong, color = c.text)
            Text(text, style = TetTheme.type.body, color = c.text)
        }
    }
}

