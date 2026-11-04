package de.tetnavigator.feature.bedienung

import android.view.KeyEvent

/** Aktionen, die über Lautstärketasten oder Bluetooth-Lenker-Remote auslösbar sind. */
enum class RemoteAction(val labelDe: String) {
    ZOOM_IN("Hineinzoomen"),
    ZOOM_OUT("Herauszoomen"),
    WAYPOINT("Wegpunkt / Pin setzen"),
    TOGGLE_VIEW("Ansicht wechseln"),
    CENTER("Zentrieren"),
}

/**
 * Übersetzt Tastatur-Events (BT-Remotes senden HID-Tastatur- oder Media-Keys)
 * in App-Aktionen. Eigene Belegung (gelernt in den Einstellungen) hat Vorrang.
 */
object RemoteKeyMapper {
    val defaults: Map<Int, RemoteAction> = mapOf(
        KeyEvent.KEYCODE_DPAD_UP to RemoteAction.ZOOM_IN,
        KeyEvent.KEYCODE_PAGE_UP to RemoteAction.ZOOM_IN,
        KeyEvent.KEYCODE_PLUS to RemoteAction.ZOOM_IN,
        KeyEvent.KEYCODE_ZOOM_IN to RemoteAction.ZOOM_IN,
        KeyEvent.KEYCODE_NUMPAD_ADD to RemoteAction.ZOOM_IN,
        KeyEvent.KEYCODE_DPAD_DOWN to RemoteAction.ZOOM_OUT,
        KeyEvent.KEYCODE_PAGE_DOWN to RemoteAction.ZOOM_OUT,
        KeyEvent.KEYCODE_MINUS to RemoteAction.ZOOM_OUT,
        KeyEvent.KEYCODE_ZOOM_OUT to RemoteAction.ZOOM_OUT,
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT to RemoteAction.ZOOM_OUT,
        KeyEvent.KEYCODE_ENTER to RemoteAction.WAYPOINT,
        KeyEvent.KEYCODE_DPAD_CENTER to RemoteAction.WAYPOINT,
        KeyEvent.KEYCODE_SPACE to RemoteAction.WAYPOINT,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to RemoteAction.WAYPOINT,
        KeyEvent.KEYCODE_HEADSETHOOK to RemoteAction.WAYPOINT,
        KeyEvent.KEYCODE_DPAD_LEFT to RemoteAction.TOGGLE_VIEW,
        KeyEvent.KEYCODE_DPAD_RIGHT to RemoteAction.TOGGLE_VIEW,
        KeyEvent.KEYCODE_TAB to RemoteAction.TOGGLE_VIEW,
        KeyEvent.KEYCODE_MEDIA_NEXT to RemoteAction.TOGGLE_VIEW,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS to RemoteAction.CENTER,
        KeyEvent.KEYCODE_MOVE_HOME to RemoteAction.CENTER,
        KeyEvent.KEYCODE_C to RemoteAction.CENTER,
    )

    /**
     * @param custom Aktion-Name → KeyCode aus den Einstellungen
     * @param volumeKeysZoom Lautstärketasten = Zoom (Handschuh-Modus)
     */
    fun map(keyCode: Int, custom: Map<String, Int>, volumeKeysZoom: Boolean): RemoteAction? {
        custom.entries.firstOrNull { it.value == keyCode }?.let { e -> RemoteAction.entries.firstOrNull { it.name == e.key }?.let { return it } }
        if (volumeKeysZoom) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) return RemoteAction.ZOOM_IN
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return RemoteAction.ZOOM_OUT
        }
        return defaults[keyCode]
    }

    fun keyName(code: Int): String = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
}
