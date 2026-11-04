package de.tetnavigator.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import de.tetnavigator.R
import de.tetnavigator.core.common.Countries
import de.tetnavigator.core.designsystem.ButtonKind
import de.tetnavigator.core.designsystem.ChipKind
import de.tetnavigator.core.designsystem.DashedDivider
import de.tetnavigator.core.designsystem.Panel
import de.tetnavigator.core.designsystem.ScreenHeader
import de.tetnavigator.core.designsystem.SecondaryButton
import de.tetnavigator.core.designsystem.SectionLabel
import de.tetnavigator.core.designsystem.StatusChip
import de.tetnavigator.core.designsystem.StepperRow
import de.tetnavigator.core.designsystem.TetButton
import de.tetnavigator.core.designsystem.TetTheme
import de.tetnavigator.core.designsystem.ToggleRow
import de.tetnavigator.core.preferences.AppSettings
import de.tetnavigator.feature.bedienung.RemoteAction
import de.tetnavigator.feature.bedienung.RemoteKeyMapper
import de.tetnavigator.feature.settings.SettingsInteractor
import de.tetnavigator.ui.AppEvents
import de.tetnavigator.ui.Routes
import de.tetnavigator.ui.bytes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(val interactor: SettingsInteractor, val events: AppEvents) : ViewModel() {
    val settings: StateFlow<AppSettings> = interactor.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    fun update(block: (AppSettings) -> AppSettings) = viewModelScope.launch { interactor.update(block) }
    fun run(block: suspend SettingsInteractor.() -> Unit) = viewModelScope.launch { interactor.block() }
}

@Composable
fun SettingsScreen(nav: NavHostController, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val learning by vm.events.learning.collectAsStateWithLifecycle()
    val c = TetTheme.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val longHint = stringResource(R.string.long_press_hint)
    LaunchedEffect(Unit) {
        vm.events.learned.collect { (a, code) -> vm.update { it.copy(remoteMapping = it.remoteMapping.filterValues { v -> v != code } + (a.name to code)) } }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.settings), onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            SectionLabel(stringResource(R.string.set_navigation))
            StepperRow(stringResource(R.string.set_deviation), "${s.deviationThresholdM} m", { vm.run { stepDeviation(-10) } }, { vm.run { stepDeviation(10) } }, subtitle = stringResource(R.string.set_deviation_sub))
            ToggleRow(stringResource(R.string.set_alarm_sound), null, s.alarmSound) { v -> vm.update { it.copy(alarmSound = v) } }
            ToggleRow(stringResource(R.string.set_alarm_vibrate), null, s.alarmVibrate) { v -> vm.update { it.copy(alarmVibrate = v) } }
            ToggleRow(stringResource(R.string.heading_up), null, s.headingUp) { v -> vm.update { it.copy(headingUp = v) } }
            ToggleRow(stringResource(R.string.set_auto_zoom), stringResource(R.string.set_auto_zoom_sub), s.autoZoom) { v -> vm.update { it.copy(autoZoom = v) } }
            ToggleRow(stringResource(R.string.set_turn_preview), stringResource(R.string.set_turn_preview_sub), s.turnPreview) { v -> vm.update { it.copy(turnPreview = v) } }
            DashedDivider()

            SectionLabel(stringResource(R.string.set_import))
            StepperRow(stringResource(R.string.set_max_age), "${s.gpxMaxAgeDays} d", { vm.run { stepMaxAge(-10) } }, { vm.run { stepMaxAge(10) } }, subtitle = stringResource(R.string.set_max_age_sub))
            DashedDivider()

            SectionLabel(stringResource(R.string.set_fuel))
            StepperRow(stringResource(R.string.set_tank), String.format(Locale.GERMANY, "%.0f l", s.tankLiters), { vm.run { stepTank(-1.0) } }, { vm.run { stepTank(1.0) } })
            StepperRow(stringResource(R.string.set_consumption), String.format(Locale.GERMANY, "%.1f", s.consumptionLPer100), { vm.run { stepConsumption(-0.1) } }, { vm.run { stepConsumption(0.1) } }, subtitle = "l/100 km")
            Text(stringResource(R.string.set_range, s.rangeKm.toInt(), s.fullRangeKm.toInt()), style = TetTheme.type.body, color = c.text)
            Spacer(Modifier.height(6.dp))
            SecondaryButton(stringResource(R.string.refuel), { vm.run { refuel() } }, Modifier.fillMaxWidth())
            StepperRow(stringResource(R.string.set_fallback_speed), "${s.fallbackOffroadKmh.toInt()}", { vm.run { stepFallbackSpeed(-1.0) } }, { vm.run { stepFallbackSpeed(1.0) } }, subtitle = stringResource(R.string.set_fallback_speed_sub))
            DashedDivider()

            SectionLabel(stringResource(R.string.set_controls))
            ToggleRow(stringResource(R.string.set_glove), stringResource(R.string.set_glove_sub), s.gloveMode) { v -> vm.update { it.copy(gloveMode = v) } }
            ToggleRow(stringResource(R.string.set_volume_zoom), null, s.volumeKeysZoom) { v -> vm.update { it.copy(volumeKeysZoom = v) } }
            ToggleRow(stringResource(R.string.set_battery), stringResource(R.string.set_battery_sub), s.batteryMode) { v -> vm.update { it.copy(batteryMode = v) } }
            ToggleRow(stringResource(R.string.day_mode), stringResource(R.string.day_mode_sub), s.dayMode) { v -> vm.update { it.copy(dayMode = v) } }
            DashedDivider()

            SectionLabel(stringResource(R.string.set_remote))
            Text(stringResource(R.string.set_remote_sub), style = TetTheme.type.bodySmall, color = c.sand)
            RemoteAction.entries.forEach { a ->
                val code = s.remoteMapping[a.name]
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.labelDe, style = TetTheme.type.bodyStrong, color = c.text)
                        Text(code?.let { RemoteKeyMapper.keyName(it) } ?: stringResource(R.string.set_remote_default), style = TetTheme.type.bodySmall, color = c.sand)
                    }
                    if (learning == a) StatusChip(stringResource(R.string.set_press_key), ChipKind.Accent)
                    else SecondaryButton(stringResource(R.string.set_learn), { vm.events.learning.value = a }, Modifier.width(140.dp))
                }
            }
            if (s.remoteMapping.isNotEmpty()) SecondaryButton(stringResource(R.string.set_remote_reset), { vm.update { it.copy(remoteMapping = emptyMap()) } }, Modifier.fillMaxWidth())
            DashedDivider()

            SectionLabel(stringResource(R.string.set_packages))
            Panel(Modifier.fillMaxWidth()) {
                val installed = vm.interactor.installedCountries()
                Text(
                    if (installed.isEmpty()) stringResource(R.string.set_no_packages) else installed.sorted().joinToString(", ") { Countries.name(it) },
                    style = TetTheme.type.bodyStrong, color = c.text,
                )
                Text(bytes(vm.interactor.installedBytes()), style = TetTheme.type.bodySmall, color = c.sand)
                Spacer(Modifier.height(6.dp))
                if (s.offlineLocked && !s.reloadUnlocked) StatusChip(stringResource(R.string.set_offline_locked), ChipKind.Ok)
                else if (s.reloadUnlocked) StatusChip(stringResource(R.string.set_reload_open), ChipKind.Warning)
                else StatusChip(stringResource(R.string.set_not_locked), ChipKind.Warning)
            }
            Spacer(Modifier.height(8.dp))
            TetButton(
                stringResource(R.string.set_reload), {
                    scope.launch { vm.interactor.unlockForReload(); nav.navigate(Routes.SETUP) }
                }, Modifier.fillMaxWidth(), ButtonKind.Secondary, requireLongPress = true,
                onShortTapHint = { Toast.makeText(ctx, longHint, Toast.LENGTH_SHORT).show() },
            )
            if (s.reloadUnlocked) SecondaryButton(stringResource(R.string.set_reload_cancel), { vm.run { cancelReload() } }, Modifier.fillMaxWidth())
            Text(stringResource(R.string.set_reload_sub), style = TetTheme.type.bodySmall, color = c.sand)
            DashedDivider()

            SectionLabel(stringResource(R.string.set_about))
            Text(stringResource(R.string.set_privacy), style = TetTheme.type.body, color = c.text)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.set_licenses), style = TetTheme.type.bodySmall, color = c.sand)
            Spacer(Modifier.height(6.dp))
            SecondaryButton(stringResource(R.string.set_permissions_again), { vm.update { it.copy(permissionsExplained = false) }; nav.navigate(Routes.PERMISSIONS) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
        }
    }
}

