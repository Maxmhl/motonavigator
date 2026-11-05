package de.tetnavigator.data.download

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** „Pausieren“ direkt aus der Download-Benachrichtigung. */
@AndroidEntryPoint
class DownloadActionReceiver : BroadcastReceiver() {
    @Inject lateinit var controller: DownloadController

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PAUSE) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                controller.pause(controller.state.first())
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "de.tetnavigator.download.PAUSE"

        fun pauseIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, DownloadActionReceiver::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
