package de.tetnavigator.ui.setup

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.tetnavigator.R
import de.tetnavigator.core.designsystem.ChipKind
import de.tetnavigator.core.designsystem.CountryBadge
import de.tetnavigator.core.designsystem.MinTouch
import de.tetnavigator.core.designsystem.Panel
import de.tetnavigator.core.designsystem.PrimaryButton
import de.tetnavigator.core.designsystem.ScreenHeader
import de.tetnavigator.core.designsystem.SecondaryButton
import de.tetnavigator.core.designsystem.StatusChip
import de.tetnavigator.core.designsystem.TetIcons
import de.tetnavigator.core.designsystem.TetProgressBar
import de.tetnavigator.core.designsystem.TetTheme
import de.tetnavigator.core.designsystem.ToggleRow
import de.tetnavigator.core.offlinedata.Manifest
import de.tetnavigator.core.preferences.SettingsRepository
import de.tetnavigator.data.download.DownloadState
import de.tetnavigator.data.download.DirectOptions
import de.tetnavigator.feature.setup.CountryOption
import de.tetnavigator.feature.setup.DirectCheck
import de.tetnavigator.feature.setup.PlanCheck
import de.tetnavigator.feature.setup.SetupInteractor
import de.tetnavigator.ui.bytes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SetupStep { WELCOME, SOURCE, DIRECT_COUNTRIES, DIRECT_PLAN, SERVER, COUNTRIES, DOWNLOAD, DONE }

data class SetupUi(
    val step: SetupStep = SetupStep.WELCOME,
    val baseUrl: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val manifest: Manifest? = null,
    val options: List<CountryOption> = emptyList(),
    val selected: Set<String> = emptySet(),
    val wifiOnly: Boolean = true,
    val check: PlanCheck? = null,
    // Direkt-Modus (ohne PC)
    val directOptions: List<CountryOption> = emptyList(),
    val directSelected: Set<String> = emptySet(),
    val maxZoom: Int = 13,
    val dem: Boolean = true,
    val estimating: Boolean = false,
    val directCheck: DirectCheck? = null,
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val setup: SetupInteractor,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(SetupUi())
    val ui: StateFlow<SetupUi> = _ui
    val download: StateFlow<DownloadState> = setup.downloadState.stateIn(viewModelScope, SharingStarted.Eagerly, DownloadState.Idle)

    init {
        viewModelScope.launch {
            val s = settings.current()
            // Läuft (oder pausiert) bereits ein Download → direkt dorthin
            if (s.activeDownload.isNotBlank()) _ui.value = _ui.value.copy(step = SetupStep.DOWNLOAD)
            _ui.value = _ui.value.copy(
                baseUrl = s.baseUrl, wifiOnly = s.wifiOnly, selected = s.selectedCountries,
                directOptions = setup.directCountries(), directSelected = s.selectedCountries,
                maxZoom = s.directMaxZoom, dem = s.directDem,
            )
        }
    }

    fun toggleDirect(code: String) {
        val u = _ui.value
        _ui.value = u.copy(directSelected = if (code in u.directSelected) u.directSelected - code else u.directSelected + code, directCheck = null)
    }

    fun setZoom(z: Int) { _ui.value = _ui.value.copy(maxZoom = z, directCheck = null) }
    fun setDem(d: Boolean) { _ui.value = _ui.value.copy(dem = d, directCheck = null) }

    private fun directOptions() = _ui.value.let { DirectOptions(it.directSelected, it.maxZoom, it.dem) }

    fun estimate() = viewModelScope.launch {
        _ui.value = _ui.value.copy(step = SetupStep.DIRECT_PLAN, estimating = true, error = null, directCheck = null)
        try {
            _ui.value = _ui.value.copy(estimating = false, directCheck = setup.estimateDirect(directOptions()))
        } catch (e: Exception) {
            _ui.value = _ui.value.copy(estimating = false, error = e.message ?: e.toString())
        }
    }

    fun startDirect() = viewModelScope.launch {
        val u = _ui.value
        setup.startDirect(directOptions(), u.directCheck?.estimate?.build, u.wifiOnly)
        _ui.value = u.copy(step = SetupStep.DOWNLOAD)
    }

    fun go(step: SetupStep) { _ui.value = _ui.value.copy(step = step, error = null) }
    fun setUrl(u: String) { _ui.value = _ui.value.copy(baseUrl = u) }
    fun setWifi(w: Boolean) { _ui.value = _ui.value.copy(wifiOnly = w) }

    fun loadManifest() = viewModelScope.launch {
        _ui.value = _ui.value.copy(loading = true, error = null)
        try {
            val m = setup.loadManifest(_ui.value.baseUrl)
            val opts = setup.options(m)
            val sel = _ui.value.selected.filter { s -> opts.any { it.code == s } }.toSet()
            _ui.value = _ui.value.copy(loading = false, manifest = m, options = opts, selected = sel, step = SetupStep.COUNTRIES, check = setup.check(m, sel))
        } catch (e: Exception) {
            _ui.value = _ui.value.copy(loading = false, error = e.message ?: e.toString())
        }
    }

    fun toggle(code: String) {
        val u = _ui.value
        val sel = if (code in u.selected) u.selected - code else u.selected + code
        _ui.value = u.copy(selected = sel, check = u.manifest?.let { setup.check(it, sel) })
    }

    fun startDownload() = viewModelScope.launch {
        val u = _ui.value
        setup.start(u.selected, u.wifiOnly)
        _ui.value = u.copy(step = SetupStep.DOWNLOAD)
    }

    fun cancel() = setup.cancel()
    fun skip(onDone: () -> Unit) = viewModelScope.launch { setup.skip(); onDone() }
}

/** Einrichtungs-Assistent: einmaliger Download aller Pakete (Spezifikation 4). */
@Composable
fun SetupScreen(onDone: () -> Unit, onBack: (() -> Unit)?, vm: SetupViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val dl by vm.download.collectAsStateWithLifecycle()
    LaunchedEffect(dl) {
        if (dl is DownloadState.Done && ui.step == SetupStep.DOWNLOAD) vm.go(SetupStep.DONE)
    }
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        ScreenHeader(
            stringResource(R.string.setup_title),
            subtitle = stringResource(R.string.setup_step, stepNumber(ui.step), 6),
            onBack = onBack,
        )
        Column(Modifier.weight(1f).padding(16.dp)) {
            when (ui.step) {
                SetupStep.WELCOME -> Welcome(onNext = { vm.go(SetupStep.SOURCE) }, onSkip = { vm.skip(onDone) })
                SetupStep.SOURCE -> Source(vm)
                SetupStep.DIRECT_COUNTRIES -> DirectCountries(ui, vm)
                SetupStep.DIRECT_PLAN -> DirectPlan(ui, vm)
                SetupStep.SERVER -> Server(ui, vm)
                SetupStep.COUNTRIES -> CountriesStep(ui, vm)
                SetupStep.DOWNLOAD -> DownloadStep(dl, vm, onLeave = if (onBack != null) onBack else onDone)
                SetupStep.DONE -> DoneStep(onDone)
            }
        }
    }
}

@Composable
private fun Welcome(onNext: () -> Unit, onSkip: () -> Unit) {
    val c = TetTheme.colors
    Column(Modifier.fillMaxSize()) {
        Icon(TetIcons.Download, null, Modifier.size(64.dp), tint = c.accent)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.setup_welcome_title).uppercase(), style = TetTheme.type.title, color = c.text)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.setup_welcome_text), style = TetTheme.type.body, color = c.text)
        Spacer(Modifier.height(16.dp))
        Panel(borderColor = c.moss) {
            Text(stringResource(R.string.setup_offline_hint), style = TetTheme.type.bodyStrong, color = c.text)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(stringResource(R.string.setup_skip), onSkip, Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.next), onNext, Modifier.weight(1.4f))
        }
    }
}

private fun stepNumber(s: SetupStep) = when (s) {
    SetupStep.WELCOME -> 1
    SetupStep.SOURCE -> 2
    SetupStep.DIRECT_COUNTRIES, SetupStep.SERVER -> 3
    SetupStep.DIRECT_PLAN, SetupStep.COUNTRIES -> 4
    SetupStep.DOWNLOAD -> 5
    SetupStep.DONE -> 6
}

/** Quelle wählen: direkt aus dem Internet (ohne PC) oder eigener Server. */
@Composable
private fun Source(vm: SetupViewModel) {
    val c = TetTheme.colors
    Column(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.setup_source_text), style = TetTheme.type.body, color = c.text)
        Spacer(Modifier.height(14.dp))
        Panel(Modifier.fillMaxWidth(), borderColor = c.accent, onClick = { vm.go(SetupStep.DIRECT_COUNTRIES) }) {
            Text(stringResource(R.string.setup_source_direct).uppercase(), style = TetTheme.type.button, color = c.accent)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.setup_source_direct_sub), style = TetTheme.type.bodySmall, color = c.text)
        }
        Spacer(Modifier.height(10.dp))
        Panel(Modifier.fillMaxWidth(), onClick = { vm.go(SetupStep.SERVER) }) {
            Text(stringResource(R.string.setup_source_server).uppercase(), style = TetTheme.type.button, color = c.text)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.setup_source_server_sub), style = TetTheme.type.bodySmall, color = c.sand)
        }
        Spacer(Modifier.weight(1f))
        SecondaryButton(stringResource(R.string.back), { vm.go(SetupStep.WELCOME) }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun DirectCountries(ui: SetupUi, vm: SetupViewModel) {
    val c = TetTheme.colors
    Column(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.setup_countries_text), style = TetTheme.type.body, color = c.text)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ui.directOptions, key = { it.code }) { o ->
                val sel = o.code in ui.directSelected
                Panel(onClick = { vm.toggleDirect(o.code) }, borderColor = if (sel) c.accent else c.outline, padding = androidx.compose.foundation.layout.PaddingValues(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CountryBadge(o.code, sel, size = 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(o.name, style = TetTheme.type.bodyStrong, color = c.text, modifier = Modifier.weight(1f))
                        if (sel) Icon(TetIcons.Check, null, Modifier.size(28.dp), tint = c.accent)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.setup_detail).uppercase(), style = TetTheme.type.label, color = c.sand)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(12 to R.string.setup_detail_low, 13 to R.string.setup_detail_std, 14 to R.string.setup_detail_high).forEach { (z, label) ->
                de.tetnavigator.core.designsystem.TetButton(
                    stringResource(label), { vm.setZoom(z) }, Modifier.weight(1f),
                    kind = if (ui.maxZoom == z) de.tetnavigator.core.designsystem.ButtonKind.Primary else de.tetnavigator.core.designsystem.ButtonKind.Secondary,
                )
            }
        }
        ToggleRow(stringResource(R.string.setup_dem), stringResource(R.string.setup_dem_sub), ui.dem, onChange = vm::setDem)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(stringResource(R.string.back), { vm.go(SetupStep.SOURCE) }, Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.setup_estimate), { vm.estimate() }, Modifier.weight(1.4f), enabled = ui.directSelected.isNotEmpty())
        }
    }
}

@Composable
private fun DirectPlan(ui: SetupUi, vm: SetupViewModel) {
    val c = TetTheme.colors
    val ch: DirectCheck? = ui.directCheck
    Column(Modifier.fillMaxSize()) {
        when {
            ui.estimating -> {
                Text(stringResource(R.string.setup_estimating), style = TetTheme.type.body, color = c.text)
                Spacer(Modifier.height(12.dp))
                TetProgressBar(0.3f)
            }
            ui.error != null -> Panel(borderColor = c.danger) {
                Text(stringResource(R.string.setup_failed), style = TetTheme.type.bodyStrong, color = c.danger)
                Text(ui.error, style = TetTheme.type.body, color = c.text)
            }
            ch != null -> {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ch.estimate.countries, key = { it.code }) { e ->
                        Panel(Modifier.fillMaxWidth()) {
                            Text(de.tetnavigator.core.common.Countries.name(e.code), style = TetTheme.type.bodyStrong, color = c.text)
                            Text(stringResource(R.string.setup_plan_map, bytes(e.mapBytes), e.mapTiles), style = TetTheme.type.bodySmall, color = c.sand)
                            if (e.demTiles > 0) Text(stringResource(R.string.setup_plan_dem, bytes(e.demBytesEstimate)), style = TetTheme.type.bodySmall, color = c.sand)
                        }
                    }
                    item {
                        Panel(Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.setup_plan_routing, bytes(ch.estimate.routingBytes)), style = TetTheme.type.bodySmall, color = c.sand)
                            Text(stringResource(R.string.setup_plan_pois), style = TetTheme.type.bodySmall, color = c.sand)
                            Text(stringResource(R.string.setup_plan_sources, ch.estimate.build.removeSuffix(".pmtiles")), style = TetTheme.type.bodySmall, color = c.sand)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Panel(Modifier.fillMaxWidth(), borderColor = if (ch.fits) c.outline else c.danger) {
                    Text(stringResource(R.string.setup_total_direct, bytes(ch.estimate.totalBytes)), style = TetTheme.type.bodyStrong, color = c.text)
                    Text(stringResource(R.string.setup_free, bytes(ch.freeBytes)), style = TetTheme.type.bodySmall, color = if (ch.fits) c.sand else c.danger)
                    if (!ch.fits) StatusChip(stringResource(R.string.setup_no_space), ChipKind.Danger)
                }
                ToggleRow(stringResource(R.string.setup_wifi_only), stringResource(R.string.setup_wifi_only_sub), ui.wifiOnly, onChange = vm::setWifi)
            }
        }
        if (ch == null) Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(stringResource(R.string.back), { vm.go(SetupStep.DIRECT_COUNTRIES) }, Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.setup_download), { vm.startDirect() }, Modifier.weight(1.4f), enabled = ch?.fits == true)
        }
    }
}

@Composable
private fun Server(ui: SetupUi, vm: SetupViewModel) {
    val c = TetTheme.colors
    Column(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.setup_server_text), style = TetTheme.type.body, color = c.text)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = ui.baseUrl,
            onValueChange = vm::setUrl,
            label = { Text(stringResource(R.string.setup_base_url)) },
            placeholder = { Text("https://mein-server.de/tet/") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            textStyle = TetTheme.type.body,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.outline, focusedTextColor = c.text, unfocusedTextColor = c.text, cursorColor = c.accent),
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.setup_manifest_hint), style = TetTheme.type.bodySmall, color = c.sand)
        ui.error?.let {
            Spacer(Modifier.height(12.dp))
            Panel(borderColor = c.danger) { Text(it, style = TetTheme.type.body, color = c.danger) }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(stringResource(R.string.back), { vm.go(SetupStep.SOURCE) }, Modifier.weight(1f))
            PrimaryButton(stringResource(if (ui.loading) R.string.loading else R.string.setup_load_manifest), { vm.loadManifest() }, Modifier.weight(1.4f), enabled = !ui.loading && ui.baseUrl.isNotBlank())
        }
    }
}

@Composable
private fun CountriesStep(ui: SetupUi, vm: SetupViewModel) {
    val c = TetTheme.colors
    Column(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.setup_countries_text), style = TetTheme.type.body, color = c.text)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ui.options, key = { it.code }) { o ->
                val sel = o.code in ui.selected
                Panel(onClick = { vm.toggle(o.code) }, borderColor = if (sel) c.accent else c.outline) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CountryBadge(o.code, sel)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(o.name, style = TetTheme.type.bodyStrong, color = c.text)
                            Text(bytes(o.bytes) + " · " + o.types.joinToString(", ") { it.labelDe }, style = TetTheme.type.bodySmall, color = c.sand)
                            if (o.missing.isNotEmpty()) Text(stringResource(R.string.setup_missing, o.missing.joinToString(", ") { it.labelDe }), style = TetTheme.type.bodySmall, color = c.warning)
                        }
                        if (sel) Icon(TetIcons.Check, null, Modifier.size(30.dp), tint = c.accent)
                    }
                }
            }
        }
        ui.check?.let { ch ->
            Spacer(Modifier.height(8.dp))
            Panel(borderColor = if (ch.fits) c.outline else c.danger) {
                Text(stringResource(R.string.setup_total, bytes(ch.totalBytes), ch.fileCount), style = TetTheme.type.bodyStrong, color = c.text)
                Text(stringResource(R.string.setup_free, bytes(ch.freeBytes)), style = TetTheme.type.bodySmall, color = if (ch.fits) c.sand else c.danger)
                if (!ch.fits) StatusChip(stringResource(R.string.setup_no_space), ChipKind.Danger)
            }
        }
        ToggleRow(stringResource(R.string.setup_wifi_only), stringResource(R.string.setup_wifi_only_sub), ui.wifiOnly, onChange = vm::setWifi)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(stringResource(R.string.back), { vm.go(SetupStep.SERVER) }, Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.setup_download), { vm.startDownload() }, Modifier.weight(1.4f), enabled = ui.selected.isNotEmpty() && ui.check?.fits == true)
        }
    }
}

@Composable
private fun DownloadStep(dl: DownloadState, vm: SetupViewModel, onLeave: () -> Unit, dvm: de.tetnavigator.ui.downloads.DownloadsViewModel = hiltViewModel()) {
    val c = TetTheme.colors
    val request by dvm.request.collectAsStateWithLifecycle()
    // Gestoppt → zurück zur Quellenwahl (erst, nachdem der Auftrag einmal aktiv war)
    var seen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(dl, request) {
        if (request != null || dl.active) seen = true
        else if (seen && dl == DownloadState.Idle) vm.go(SetupStep.SOURCE)
    }
    Column(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.setup_downloading).uppercase(), style = TetTheme.type.title, color = c.text)
        Spacer(Modifier.height(12.dp))
        de.tetnavigator.ui.downloads.DownloadPanel(dl, request, dvm)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.setup_background_hint), style = TetTheme.type.bodySmall, color = c.sand)
        Spacer(Modifier.weight(1f))
        // App weiter benutzen (z. B. schon GPX importieren), Download läuft im Hintergrund
        SecondaryButton(stringResource(R.string.dl_continue_in_app), onLeave, Modifier.fillMaxWidth())
    }
}

@Composable
private fun DoneStep(onDone: () -> Unit) {
    val c = TetTheme.colors
    var clicked by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Icon(TetIcons.Check, null, Modifier.size(72.dp), tint = c.moss)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.setup_done_title).uppercase(), style = TetTheme.type.title, color = c.text)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.setup_done_text), style = TetTheme.type.body, color = c.text)
        Spacer(Modifier.weight(1f))
        PrimaryButton(stringResource(R.string.setup_import_now), { if (!clicked) { clicked = true; onDone() } }, Modifier.fillMaxWidth(), icon = TetIcons.Upload)
    }
}
