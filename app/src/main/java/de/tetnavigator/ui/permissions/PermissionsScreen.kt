package de.tetnavigator.ui.permissions

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.tetnavigator.R
import de.tetnavigator.core.designsystem.DashedDivider
import de.tetnavigator.core.designsystem.PrimaryButton
import de.tetnavigator.core.designsystem.SecondaryButton
import de.tetnavigator.core.designsystem.TetIcons
import de.tetnavigator.core.designsystem.TetProgressBar
import de.tetnavigator.core.designsystem.TetTheme
import de.tetnavigator.core.preferences.SettingsRepository
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PermissionsViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel() {
    suspend fun done() = settings.update { it.copy(permissionsExplained = true) }
}

private data class PermStep(val title: Int, val text: Int, val icon: ImageVector, val perms: List<String>)

/** Jede Berechtigung beim ersten Start mit eigenem Erklär-Screen (Spezifikation 6). */
@Composable
fun PermissionsScreen(onDone: () -> Unit, vm: PermissionsViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val steps = remember {
        buildList {
            add(PermStep(R.string.perm_location_title, R.string.perm_location_text, TetIcons.Navigate, listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)))
            add(PermStep(R.string.perm_background_title, R.string.perm_background_text, TetIcons.Center, listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)))
            if (Build.VERSION.SDK_INT >= 33) add(PermStep(R.string.perm_notifications_title, R.string.perm_notifications_text, TetIcons.Warning, listOf(Manifest.permission.POST_NOTIFICATIONS)))
            if (Build.VERSION.SDK_INT >= 31) add(PermStep(R.string.perm_bluetooth_title, R.string.perm_bluetooth_text, TetIcons.Bluetooth, listOf(Manifest.permission.BLUETOOTH_CONNECT)))
            add(PermStep(R.string.perm_camera_title, R.string.perm_camera_text, TetIcons.Camera, listOf(Manifest.permission.CAMERA)))
        }
    }
    var index by remember { mutableIntStateOf(0) }
    fun next() {
        if (index < steps.lastIndex) index++ else scope.launch { vm.done(); onDone() }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { next() }
    val step = steps[index]
    val granted = step.perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    val c = TetTheme.colors

    Column(Modifier.fillMaxSize().padding(20.dp).navigationBarsPadding()) {
        Text(stringResource(R.string.perm_header).uppercase(), style = TetTheme.type.label, color = c.sand)
        Spacer(Modifier.height(8.dp))
        TetProgressBar((index + 1f) / steps.size)
        Spacer(Modifier.height(32.dp))
        Icon(step.icon, null, Modifier.size(72.dp), tint = c.accent)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(step.title).uppercase(), style = TetTheme.type.title, color = c.text)
        Spacer(Modifier.height(12.dp))
        DashedDivider()
        Spacer(Modifier.height(16.dp))
        Text(stringResource(step.text), style = TetTheme.type.body, color = c.text)
        if (granted) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.perm_granted), style = TetTheme.type.bodyStrong, color = c.moss)
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(stringResource(R.string.later), { next() }, Modifier.weight(1f))
            PrimaryButton(
                stringResource(if (granted) R.string.next else R.string.allow),
                { if (granted) next() else launcher.launch(step.perms.toTypedArray()) },
                Modifier.weight(1.4f),
            )
        }
    }
}
