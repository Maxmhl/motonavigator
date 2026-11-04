package de.tetnavigator.location

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Ton + Vibration für Abweich-Alarm und Kurven-Vorschau (rein lokal). */
@Singleton
class AlarmPlayer @Inject constructor(@ApplicationContext private val context: Context) {
    private val tone by lazy { runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }.getOrNull() }
    private val vibrator by lazy {
        if (android.os.Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(android.os.Vibrator::class.java)
    }

    fun deviation(sound: Boolean, vibrate: Boolean) {
        if (sound) tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500)
        if (vibrate) vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 150, 400, 150, 700), -1))
    }

    fun backOnTrack(sound: Boolean, vibrate: Boolean) {
        if (sound) tone?.startTone(ToneGenerator.TONE_PROP_ACK, 300)
        if (vibrate) vibrator?.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun turn(vibrate: Boolean) {
        if (vibrate) vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 90, 90, 90), -1))
    }
}
