package de.tetnavigator

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import de.tetnavigator.core.designsystem.TetTheme
import de.tetnavigator.core.preferences.AppSettings
import de.tetnavigator.core.preferences.SettingsRepository
import de.tetnavigator.feature.bedienung.BatteryMonitor
import de.tetnavigator.feature.bedienung.RemoteKeyMapper
import de.tetnavigator.ui.AppEvents
import de.tetnavigator.ui.AppRoot
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var events: AppEvents
    @Inject lateinit var battery: BatteryMonitor

    private var settings = AppSettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        battery.start()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(settingsRepo.settings, events.keepScreenOn, battery.state) { s, keep, b -> Triple(s, keep, b) }
                    .collect { (s, keep, b) ->
                        settings = s
                        if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        val lp = window.attributes
                        lp.screenBrightness = if (keep) BatteryMonitor.brightness(s.batteryMode, b.temperatureC) else -1f
                        window.attributes = lp
                    }
            }
        }
        setContent {
            val s by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = null)
            val cur = s
            if (cur != null) {
                TetTheme(dayMode = cur.dayMode, gloveMode = cur.gloveMode) {
                    AppRoot(cur)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        battery.stop()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        events.offer(uris)
    }

    /** Lautstärketasten = Zoom (Handschuh-Modus) und Bluetooth-Lenker-Remote. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val learning = events.learning.value
        if (learning != null && keyCode != KeyEvent.KEYCODE_BACK) {
            events.learned(learning, keyCode)
            events.learning.value = null
            return true
        }
        val action = mappedAction(keyCode) ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) events.emit(action)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (mappedAction(keyCode) != null) true else super.onKeyUp(keyCode, event)

    /** Nur im Fahr-Screen aktiv – sonst behalten die Tasten ihre normale Funktion. */
    private fun mappedAction(keyCode: Int) =
        if (!events.keepScreenOn.value) null
        else RemoteKeyMapper.map(keyCode, settings.remoteMapping, settings.volumeKeysZoom && settings.gloveMode)
}
