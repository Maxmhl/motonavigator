package de.tetnavigator.ui.downloads

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import dagger.hilt.android.lifecycle.HiltViewModel
import de.tetnavigator.R
import de.tetnavigator.core.common.Countries
import de.tetnavigator.core.designsystem.ButtonKind
import de.tetnavigator.core.designsystem.MinTouch
import de.tetnavigator.core.designsystem.Panel
import de.tetnavigator.core.designsystem.ScreenHeader
import de.tetnavigator.core.designsystem.SecondaryButton
import de.tetnavigator.core.designsystem.TetButton
import de.tetnavigator.core.designsystem.TetIcons
import de.tetnavigator.core.designsystem.TetProgressBar
import de.tetnavigator.core.designsystem.TetTheme
import de.tetnavigator.data.download.DownloadController
import de.tetnavigator.data.download.DownloadRequest
import de.tetnavigator.data.download.DownloadState
import de.tetnavigator.ui.bytes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(private val controller: DownloadController) : ViewModel() {
    val state: StateFlow<DownloadState> = controller.state.stateIn(viewModelScope, SharingStarted.Eagerly, DownloadState.Idle)
    val request: StateFlow<DownloadRequest?> = controller.request.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun pause() = viewModelScope.launch { controller.pause(state.value) }
    fun resume() = viewModelScope.launch { controller.resume() }
    fun stop() = viewModelScope.launch { controller.stop() }
}

/** Kurzer Statustext eines Downloads. */
@Composable
fun statusText(s: DownloadState): String = when (s) {
    is DownloadState.Queued -> stringResource(if (s.wifiOnly) R.string.dl_waiting_wifi else R.string.dl_waiting_network)
    is DownloadState.Running -> s.fileName.ifBlank { stringResource(R.string.dl_running) }
    is DownloadState.Paused -> stringResource(R.string.dl_paused) + if (s.step.isNotBlank()) " · ${s.step}" else ""
    is DownloadState.Failed -> stringResource(R.string.dl_failed, s.message)
    DownloadState.Done -> stringResource(R.string.dl_done)
    DownloadState.Idle -> ""
}

fun fractionOf(s: DownloadState): Float? = when (s) {
    is DownloadState.Running -> s.fraction
    is DownloadState.Paused -> s.fraction
    else -> null
}

/** Pausieren / Fortsetzen / Stoppen (Stoppen nur per Lang-Drücken – verwirft Teildaten). */
@Composable
fun DownloadControls(state: DownloadState, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val hint = stringResource(R.string.dl_stop_hint)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when (state) {
            is DownloadState.Running, is DownloadState.Queued ->
                TetButton(stringResource(R.string.dl_pause), onPause, Modifier.weight(1f), ButtonKind.Secondary, icon = TetIcons.Stop)
            is DownloadState.Paused ->
                TetButton(stringResource(R.string.dl_resume), onResume, Modifier.weight(1f), ButtonKind.Primary, icon = TetIcons.Download)
            is DownloadState.Failed ->
                TetButton(stringResource(R.string.retry), onResume, Modifier.weight(1f), ButtonKind.Primary, icon = TetIcons.Download)
            else -> Unit
        }
        if (state.active) {
            TetButton(
                stringResource(R.string.dl_stop), onStop, Modifier.weight(1f), ButtonKind.Danger, icon = TetIcons.Close,
                requireLongPress = true, onShortTapHint = { Toast.makeText(ctx, hint, Toast.LENGTH_SHORT).show() },
            )
        }
    }
}

/** Fortschritt + Steuerung, wiederverwendet in Einrichtung und Download-Screen. */
@Composable
fun DownloadPanel(state: DownloadState, request: DownloadRequest?, vm: DownloadsViewModel, modifier: Modifier = Modifier) {
    val c = TetTheme.colors
    Panel(modifier.fillMaxWidth(), borderColor = when (state) {
        is DownloadState.Failed -> c.danger
        is DownloadState.Paused -> c.warning
        else -> c.accent
    }) {
        Text(statusText(state), style = TetTheme.type.bodyStrong, color = c.text)
        Spacer(Modifier.height(10.dp))
        val f = fractionOf(state)
        TetProgressBar(f ?: 0f, color = if (state is DownloadState.Paused) c.warning else c.accent)
        Spacer(Modifier.height(6.dp))
        Row {
            Text(f?.let { "${(it * 100).toInt()} %" } ?: "", style = TetTheme.type.bodySmall, color = c.sand, modifier = Modifier.weight(1f))
            if (state is DownloadState.Running && state.bytesTotal > 0) {
                Text("${bytes(state.bytesDone)} / ${bytes(state.bytesTotal)}", style = TetTheme.type.bodySmall, color = c.sand)
            }
        }
        request?.let { r ->
            Spacer(Modifier.height(6.dp))
            Text(
                r.countries.sorted().joinToString(", ") { Countries.name(it) } +
                    if (r.mode == DownloadRequest.MODE_DIRECT) " · " + stringResource(R.string.dl_direct_info, r.maxZoom) else " · " + stringResource(R.string.dl_server_info),
                style = TetTheme.type.bodySmall, color = c.sand,
            )
        }
        Spacer(Modifier.height(12.dp))
        DownloadControls(state, vm::pause, vm::resume, vm::stop)
        if (state is DownloadState.Paused) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.dl_paused_hint), style = TetTheme.type.bodySmall, color = c.sand)
        }
    }
}

/** Eigener Screen „Downloads“ (aus Banner, Benachrichtigung oder Einstellungen erreichbar). */
@Composable
fun DownloadsScreen(nav: NavHostController, vm: DownloadsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val request by vm.request.collectAsStateWithLifecycle()
    val c = TetTheme.colors
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        ScreenHeader(stringResource(R.string.dl_title), onBack = { nav.popBackStack() })
        Column(Modifier.padding(12.dp)) {
            if (!state.active && state != DownloadState.Done) {
                Text(stringResource(R.string.dl_none), style = TetTheme.type.body, color = c.sand)
            } else {
                DownloadPanel(state, request, vm)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.dl_background_hint), style = TetTheme.type.bodySmall, color = c.sand)
            }
        }
    }
}

/** Banner auf allen Screens, solange ein Download läuft, wartet, pausiert oder fehlgeschlagen ist. */
@Composable
fun DownloadBanner(onOpen: () -> Unit, vm: DownloadsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (!state.active) return
    val c = TetTheme.colors
    val col = when (state) { is DownloadState.Failed -> c.danger; is DownloadState.Paused -> c.warning; else -> c.accent }
    Column(
        Modifier.fillMaxWidth().background(c.surface).clickable(onClick = onOpen).heightIn(min = MinTouch).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(TetIcons.Download, null, Modifier.size(24.dp), tint = col)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.dl_banner_title).uppercase(), style = TetTheme.type.label, color = col)
                Text(statusText(state), style = TetTheme.type.bodySmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            fractionOf(state)?.let { Text("${(it * 100).toInt()} %", style = TetTheme.type.bodyStrong, color = c.text) }
            Spacer(Modifier.width(8.dp))
            when (state) {
                is DownloadState.Running, is DownloadState.Queued ->
                    SecondaryButton(stringResource(R.string.dl_pause), vm::pause, Modifier.width(132.dp))
                is DownloadState.Paused, is DownloadState.Failed ->
                    TetButton(stringResource(R.string.dl_resume), vm::resume, Modifier.width(150.dp), ButtonKind.Primary)
                else -> Unit
            }
        }
        fractionOf(state)?.let { Spacer(Modifier.height(6.dp)); TetProgressBar(it, color = col) }
    }
}
