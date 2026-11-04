package de.tetnavigator.data.download

import de.tetnavigator.core.preferences.SettingsRepository
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Entscheidet, ob das Download-Modul überhaupt ins Netz darf. */
fun interface NetworkPolicy {
    suspend fun allowed(): Boolean
}

/** offlineLocked = true ⇒ nur nach bewusstem „Pakete neu laden“. */
@Singleton
class SettingsNetworkPolicy @Inject constructor(private val settings: SettingsRepository) : NetworkPolicy {
    override suspend fun allowed(): Boolean = settings.networkAllowed()
}

class OfflineLockedException : IOException(
    "Offline-Modus aktiv: Netzwerkzugriff gesperrt. In den Einstellungen „Pakete neu laden“ wählen.",
)

/**
 * Letzte Verteidigungslinie: jeder Request läuft durch diesen Interceptor und wird
 * verweigert, bevor eine Verbindung aufgebaut wird, wenn offlineLocked aktiv ist.
 */
class OfflineGuardInterceptor(private val policy: NetworkPolicy) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val ok = runBlocking { policy.allowed() }
        if (!ok) throw OfflineLockedException()
        return chain.proceed(chain.request())
    }
}
