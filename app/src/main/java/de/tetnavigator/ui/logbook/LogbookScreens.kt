package de.tetnavigator.ui.logbook

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.tetnavigator.R
import de.tetnavigator.core.common.Format
import de.tetnavigator.core.database.JournalEntryEntity
import de.tetnavigator.core.designsystem.ButtonKind
import de.tetnavigator.core.designsystem.CountryStamp
import de.tetnavigator.core.designsystem.Panel
import de.tetnavigator.core.designsystem.ScreenHeader
import de.tetnavigator.core.designsystem.SecondaryButton
import de.tetnavigator.core.designsystem.SectionLabel
import de.tetnavigator.core.designsystem.TetButton
import de.tetnavigator.core.designsystem.TetIcons
import de.tetnavigator.core.designsystem.TetTheme
import de.tetnavigator.feature.logbuch.LogbookOverview
import de.tetnavigator.feature.logbuch.LogbuchExporter
import de.tetnavigator.feature.logbuch.LogbuchRepository
import de.tetnavigator.ui.Routes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class LogbookViewModel @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val repo: LogbuchRepository,
    private val exporter: LogbuchExporter,
) : ViewModel() {
    val overview: StateFlow<LogbookOverview?> = repo.overview().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val journal: StateFlow<List<JournalEntryEntity>> = repo.journal().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun export(uri: android.net.Uri, kind: String, done: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                when (kind) {
                    "pdf" -> exporter.exportPdf(out, context.contentResolver)
                    "notes" -> exporter.exportTrailNotes(out)
                    else -> exporter.exportGpx(out)
                }
            } ?: error("Datei nicht beschreibbar")
        }.isSuccess
        done(ok)
    }

    fun deleteJournal(id: Long) = viewModelScope.launch { repo.deleteJournal(id) }
    fun deleteRide(id: Long) = viewModelScope.launch { repo.deleteRide(id) }
}

/** 5 · Logbuch & Länderstempel */
@Composable
fun LogbookScreen(nav: NavHostController, vm: LogbookViewModel = hiltViewModel()) {
    val ov by vm.overview.collectAsStateWithLifecycle()
    val c = TetTheme.colors
    val ctx = LocalContext.current
    var exportKind by remember { mutableStateOf("gpx") }
    val okText = stringResource(R.string.export_done)
    val failText = stringResource(R.string.export_failed)
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        uri?.let { vm.export(it, exportKind) { ok -> Toast.makeText(ctx, if (ok) okText else failText, Toast.LENGTH_SHORT).show() } }
    }
    val stamp = remember { SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Date()) }
    var showExport by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.logbook_title), subtitle = stringResource(R.string.season, Calendar.getInstance().get(Calendar.YEAR)))
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile(stringResource(R.string.trail_km), Format.thousands((ov?.trailKm ?: 0.0).toLong()), Modifier.weight(1f), accent = true)
                    Tile(stringResource(R.string.offroad), Format.percent(ov?.offroadFraction ?: 0.0), Modifier.weight(1f))
                    Tile(stringResource(R.string.ascent), Format.compact(ov?.ascentM ?: 0.0), Modifier.weight(1f))
                }
            }
            item { SectionLabel(stringResource(R.string.country_stamps)) }
            item {
                val stamps = ov?.stamps.orEmpty()
                if (stamps.isEmpty()) Text(stringResource(R.string.no_stamps), style = TetTheme.type.bodySmall, color = c.sand)
                stamps.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEachIndexed { i, s -> CountryStamp(s.code, s.percent, rotation = listOf(-8f, 5f, -3f, 7f)[(i + s.code.hashCode()).mod(4)]) }
                        repeat(4 - row.size) { Box(Modifier.size(72.dp)) }
                    }
                }
            }
            item { SectionLabel(stringResource(R.string.recent_rides)) }
            val rides = ov?.recent.orEmpty()
            if (rides.isEmpty()) item { Text(stringResource(R.string.no_rides), style = TetTheme.type.bodySmall, color = c.sand) }
            items(rides, key = { it.id }) { r ->
                Panel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(TetIcons.Camera, null, Modifier.size(26.dp), tint = c.sand) }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.title.ifBlank { stringResource(R.string.ride) }, style = TetTheme.type.bodyStrong, color = c.text)
                            Text(
                                "${Format.km0(r.distanceM)} km · ${Format.hm((r.endedAt ?: System.currentTimeMillis()) - r.startedAt)} h" +
                                    (if (r.photoCount > 0) " · ${r.photoCount} Fotos" else "") +
                                    (if (r.noteCount > 0) " · ${r.noteCount} Notizen" else "") +
                                    (if (r.hazardCount > 0) " · ${r.hazardCount} Gefahren-Pin" else ""),
                                style = TetTheme.type.bodySmall, color = c.sand,
                            )
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                SecondaryButton(stringResource(R.string.journal), { nav.navigate(Routes.JOURNAL) }, Modifier.fillMaxWidth(), icon = TetIcons.Note)
                Spacer(Modifier.height(8.dp))
                SecondaryButton(stringResource(R.string.stages), { nav.navigate(Routes.STAGES) }, Modifier.fillMaxWidth(), icon = TetIcons.Calendar)
                Spacer(Modifier.height(8.dp))
                if (!showExport) {
                    SecondaryButton(stringResource(R.string.export_gpx_pdf), { showExport = true }, Modifier.fillMaxWidth(), icon = TetIcons.Download)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TetButton("GPX", { exportKind = "gpx"; saver.launch("tet-logbuch-$stamp.gpx") }, Modifier.weight(1f), ButtonKind.Primary)
                        TetButton("PDF", { exportKind = "pdf"; saver.launch("tet-reisetagebuch-$stamp.pdf") }, Modifier.weight(1f), ButtonKind.Primary)
                    }
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton(stringResource(R.string.export_notes), { exportKind = "notes"; saver.launch("tet-trail-notizen-$stamp.gpx") }, Modifier.fillMaxWidth())
                    Text(stringResource(R.string.export_hint), style = TetTheme.type.bodySmall, color = c.sand)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier, accent: Boolean = false) {
    val c = TetTheme.colors
    Panel(modifier, padding = androidx.compose.foundation.layout.PaddingValues(10.dp)) {
        Text(label.uppercase(), style = TetTheme.type.label, color = c.sand)
        Text(value, style = TetTheme.type.numberM, color = if (accent) c.accent else c.text, maxLines = 1)
    }
}

/** Reisetagebuch: Fotos + Notizen am Ort. */
@Composable
fun JournalScreen(nav: NavHostController, vm: LogbookViewModel = hiltViewModel()) {
    val entries by vm.journal.collectAsStateWithLifecycle()
    val c = TetTheme.colors
    val df = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY) }
    var confirm by remember { mutableStateOf<Long?>(null) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.journal), subtitle = stringResource(R.string.journal_sub, entries.size), onBack = { nav.popBackStack() })
        LazyColumn(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (entries.isEmpty()) item { Text(stringResource(R.string.journal_empty), style = TetTheme.type.body, color = c.sand) }
            items(entries, key = { it.id }) { e ->
                Panel(Modifier.fillMaxWidth()) {
                    Text(df.format(Date(e.createdAt)) + " · " + "%.4f, %.4f".format(Locale.US, e.lat, e.lon), style = TetTheme.type.bodySmall, color = c.sand)
                    if (e.text.isNotBlank()) Text(e.text, style = TetTheme.type.body, color = c.text)
                    if (e.photoUri != null) Text(stringResource(R.string.photo_attached), style = TetTheme.type.bodySmall, color = c.moss)
                    Spacer(Modifier.height(6.dp))
                    if (confirm == e.id) TetButton(stringResource(R.string.delete_hold), { vm.deleteJournal(e.id); confirm = null }, Modifier.fillMaxWidth(), ButtonKind.Danger, requireLongPress = true)
                    else SecondaryButton(stringResource(R.string.delete), { confirm = e.id }, Modifier.fillMaxWidth(), icon = TetIcons.Trash)
                }
            }
        }
    }
}
