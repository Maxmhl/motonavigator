package de.tetnavigator.ui.importer

import android.net.Uri
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import dagger.hilt.android.lifecycle.HiltViewModel
import de.tetnavigator.R
import de.tetnavigator.core.common.Countries
import de.tetnavigator.core.common.Format
import de.tetnavigator.core.designsystem.DashedBox
import de.tetnavigator.core.designsystem.MinTouch
import de.tetnavigator.core.designsystem.Panel
import de.tetnavigator.core.designsystem.PrimaryButton
import de.tetnavigator.core.designsystem.ScreenHeader
import de.tetnavigator.core.designsystem.SecondaryButton
import de.tetnavigator.core.designsystem.SectionLabel
import de.tetnavigator.core.designsystem.TetIcons
import de.tetnavigator.core.designsystem.TetTheme
import de.tetnavigator.core.designsystem.ValueCell
import de.tetnavigator.feature.importer.ImportRepository
import de.tetnavigator.feature.importer.ImportResult
import de.tetnavigator.feature.importer.StagedImport
import de.tetnavigator.feature.importer.SurfaceAnalyzer
import de.tetnavigator.ui.AppEvents
import de.tetnavigator.ui.components.MiniMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ImportUi(
    val busy: Boolean = false,
    val progress: String? = null,
    val staged: StagedImport? = null,
    val countries: Map<String, String> = emptyMap(),
    val error: String? = null,
    val result: ImportResult? = null,
)

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val repo: ImportRepository,
    private val surface: SurfaceAnalyzer,
    private val events: AppEvents,
) : ViewModel() {
    private val _ui = MutableStateFlow(ImportUi())
    val ui: StateFlow<ImportUi> = _ui

    fun takeIncoming() { events.consumeIncoming().firstOrNull()?.let(::stage) }

    fun stage(uri: Uri) = viewModelScope.launch {
        _ui.value.staged?.let(repo::discard)
        _ui.value = ImportUi(busy = true)
        try {
            val st = repo.stage(uri)
            _ui.value = ImportUi(staged = st, countries = st.files.associate { it.entryName to (it.detectedCountry ?: "XX") })
        } catch (e: Exception) {
            _ui.value = ImportUi(error = e.message ?: e.toString())
        }
    }

    fun setCountry(entry: String, cc: String) { _ui.value = _ui.value.copy(countries = _ui.value.countries + (entry to cc)) }

    fun commit(onDone: () -> Unit) = viewModelScope.launch {
        val st = _ui.value.staged ?: return@launch
        _ui.value = _ui.value.copy(busy = true)
        try {
            val r = repo.commit(st, _ui.value.countries) { name -> _ui.value = _ui.value.copy(progress = name) }
            _ui.value = ImportUi(result = r)
            launch { runCatching { surface.analyzePending() } }
            onDone()
        } catch (e: Exception) {
            _ui.value = _ui.value.copy(busy = false, error = e.message ?: e.toString())
        }
    }

    fun cancel() { _ui.value.staged?.let(repo::discard); _ui.value = ImportUi() }

    override fun onCleared() { _ui.value.staged?.let(repo::discard) }
}

/** 3 · GPX-Import */
@Composable
fun ImportScreen(nav: NavHostController, vm: ImportViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val c = TetTheme.colors
    LaunchedEffect(Unit) { vm.takeIncoming() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::stage) }
    val st = ui.staged

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        ScreenHeader(stringResource(R.string.import_title), onBack = { vm.cancel(); nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)) {
            DashedBox(Modifier.fillMaxWidth(), color = c.accent, onClick = {
                picker.launch(arrayOf("application/gpx+xml", "application/gpx", "application/zip", "application/x-zip-compressed", "application/octet-stream", "text/xml", "application/xml", "*/*"))
            }) {
                Icon(TetIcons.Upload, null, Modifier.size(36.dp), tint = c.accent)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.choose_file).uppercase(), style = TetTheme.type.button, color = c.text)
                Text(stringResource(R.string.choose_file_sub), style = TetTheme.type.bodySmall, color = c.sand)
            }
            if (ui.busy) {
                Spacer(Modifier.height(16.dp))
                Text(ui.progress?.let { stringResource(R.string.importing, it) } ?: stringResource(R.string.reading), style = TetTheme.type.body, color = c.sand)
            }
            ui.error?.let {
                Spacer(Modifier.height(12.dp))
                Panel(borderColor = c.danger) { Text(it, style = TetTheme.type.body, color = c.danger) }
            }
            if (st != null) {
                SectionLabel(stringResource(R.string.preview))
                Panel(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    MiniMap(st.files.flatMap { it.polyline }, st.files.flatMap { it.changedPolylines }, Modifier.fillMaxWidth().height(170.dp))
                    Column(Modifier.padding(14.dp)) {
                        Text(st.sourceName, style = TetTheme.type.bodyStrong, color = c.text)
                        Spacer(Modifier.height(6.dp))
                        Row {
                            ValueCell(stringResource(R.string.sections), st.sectionCount.toString(), "", Modifier.weight(1f))
                            ValueCell("km", Format.km0(st.lengthM), "", Modifier.weight(1f))
                            ValueCell(stringResource(R.string.waypoints), st.waypointCount.toString(), "", Modifier.weight(1f))
                        }
                        for (f in st.files) {
                            Spacer(Modifier.height(10.dp))
                            if (st.files.size > 1) Text(f.displayName, style = TetTheme.type.bodySmall, color = c.sand)
                            Text(stringResource(R.string.assign_country), style = TetTheme.type.bodySmall, color = c.sand)
                            CountryPicker(ui.countries[f.entryName] ?: "XX", detected = f.detectedCountry) { vm.setCountry(f.entryName, it) }
                            if (f.alreadyImported) Text(stringResource(R.string.already_imported), style = TetTheme.type.bodySmall, color = c.sand)
                        }
                    }
                }
                val olderCount = st.files.count { it.olderVersion != null }
                if (olderCount > 0) {
                    Spacer(Modifier.height(10.dp))
                    Panel(Modifier.fillMaxWidth(), borderColor = c.warning, background = c.warning.copy(alpha = 0.12f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(TetIcons.Warning, null, Modifier.size(28.dp), tint = c.warning)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(stringResource(R.string.older_version), style = TetTheme.type.bodyStrong, color = c.text)
                                Text(stringResource(R.string.older_version_text, st.changedSegments), style = TetTheme.type.bodySmall, color = c.text)
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(stringResource(R.string.cancel), { vm.cancel(); nav.popBackStack() }, Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.do_import), { vm.commit { nav.popBackStack() } }, Modifier.weight(1.4f), enabled = st != null && !ui.busy)
        }
    }
}

@Composable
private fun CountryPicker(code: String, detected: String?, onPick: (String) -> Unit) {
    val c = TetTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        SecondaryButton(
            Countries.name(code) + if (code == detected) " (" + stringResource(R.string.detected) + ")" else "",
            { open = true }, Modifier.fillMaxWidth(),
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            Countries.all.sortedBy { it.nameDe }.forEach { country ->
                DropdownMenuItem(
                    text = { Text(country.nameDe, style = TetTheme.type.body, color = c.text) },
                    onClick = { onPick(country.code); open = false },
                    modifier = Modifier.heightIn(min = MinTouch),
                )
            }
        }
    }
}
