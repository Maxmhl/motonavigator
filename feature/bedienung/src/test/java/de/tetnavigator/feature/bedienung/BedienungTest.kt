package de.tetnavigator.feature.bedienung

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BedienungTest {
    @Test fun volumeKeysZoomOnlyWhenEnabled() {
        assertThat(RemoteKeyMapper.map(KeyEvent.KEYCODE_VOLUME_UP, emptyMap(), true)).isEqualTo(RemoteAction.ZOOM_IN)
        assertThat(RemoteKeyMapper.map(KeyEvent.KEYCODE_VOLUME_DOWN, emptyMap(), true)).isEqualTo(RemoteAction.ZOOM_OUT)
        assertThat(RemoteKeyMapper.map(KeyEvent.KEYCODE_VOLUME_UP, emptyMap(), false)).isNull()
    }

    @Test fun customMappingWins() {
        val custom = mapOf(RemoteAction.WAYPOINT.name to KeyEvent.KEYCODE_DPAD_UP)
        assertThat(RemoteKeyMapper.map(KeyEvent.KEYCODE_DPAD_UP, custom, true)).isEqualTo(RemoteAction.WAYPOINT)
        assertThat(RemoteKeyMapper.map(KeyEvent.KEYCODE_ENTER, custom, true)).isEqualTo(RemoteAction.WAYPOINT)
    }

    @Test fun drainEstimate() {
        val e = DrainEstimator()
        assertThat(e.add(0, 80, false)).isNull()
        // 10 % in 60 min → 6 min pro % → bei 70 % noch 7 h
        val ms = e.add(60 * 60_000L, 70, false)!!
        assertThat(ms).isEqualTo(70 * 6 * 60_000L)
        assertThat(e.add(61 * 60_000L, 70, true)).isNull()
    }

    @Test fun gpsIntervalAndBrightness() {
        assertThat(BatteryMonitor.gpsIntervalMs(false, 80, false)).isEqualTo(1000)
        assertThat(BatteryMonitor.gpsIntervalMs(true, 80, false)).isEqualTo(3000)
        assertThat(BatteryMonitor.gpsIntervalMs(false, 10, false)).isEqualTo(3000)
        assertThat(BatteryMonitor.gpsIntervalMs(true, 10, true)).isEqualTo(1000)
        assertThat(BatteryMonitor.brightness(false, 45f)).isLessThan(0.5f)
    }
}
