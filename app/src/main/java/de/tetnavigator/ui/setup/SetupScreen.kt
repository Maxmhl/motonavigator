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
import de.tetnavigator.feature.setup.CountryOption
import de.tetnavigator.feature.setup.PlanCheck
import de.tetnavigator.feature.setup.SetupInteractor
import de.tetnavigator.ui.bytes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SetupStep { WELCOME, SERVER, COUNTRIES, DOWNLOAD, DONE }

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
            _ui.value = _ui.value.copy(baseUrl = s.baseUrl, wifiOnly = s.wifiOnly, selected = s.selectedCountries)
        }
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
            subtitle = stringResource(R.string.setup_step, ui.step.ordinal + 1, SetupStep.entries.size),
            onBack = onBack,
        )
        Column(Modifier.weight(1f).padding(16.dp)) {
            when (ui.step) {
                SetupStep.WELCOME -> Welcome(onNext = { vm.go(SetupStep.SERVER) }, onSkip = { vm.skip(onDone) })
                SetupStep.SERVER -> Server(ui, vm)
                SetupStep.COUNTRIES -> CountriesStep(ui, vm)
                SetupStep.DOWNLOAD -> DownloadStep(dl, vm)
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
            SecondaryButton(stringResource(R.string.back), { vm.go(SetupStep.WELCOME) }, Modifier.weight(1f))
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
private fun DownloadStep(dl: DownloadState, vm: SetupViewModel) {
    val c = TetTheme.colors
    Column(Modifier.fillMaxSize()) {
        when (dl) {
            is DownloadState.Running -> {
                Text(stringResource(R.string.setup_downloading).uppercase(), style = TetTheme.type.title, color = c.text)
                Spacer(Modifier.height(16.dp))
                TetProgressBar(dl.fraction)
                Spacer(Modifier.height(8.dp))
                Text("${dl.fileIndex}/${dl.fileCount} · ${dl.fileName}", style = TetTheme.type.body, color = c.text)
                Text("${bytes(dl.bytesDone)} / ${bytes(dl.bytesTotal)}", style = TetTheme.type.bodySmall, color = c.sand)
            }
            is DownloadState.Failed -> {
                Panel(borderColor = c.danger) {
                    Text(stringResource(R.string.setup_failed), style = TetTheme.type.bodyStrong, color = c.danger)
                    Text(dl.message, style = TetTheme.type.body, color = c.text)
                }
                Spacer(Modifier.height(12.dp))
                PrimaryButton(stringResource(R.string.retry), { vm.startDownload() }, Modifier.fillMaxWidth())
            }
            else -> {
                Text(stringResource(R.string.setup_waiting), style = TetTheme.type.body, color = c.text)
                Spacer(Modifier.height(16.dp))
                TetProgressBar(0f)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.setup_background_hint), style = TetTheme.type.bodySmall, color = c.sand)
        Spacer(Modifier.weight(1f))
        SecondaryButton(stringResource(R.string.cancel), { vm.cancel(); vm.go(SetupStep.COUNTRIES) }, Modifier.fillMaxWidth())
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
