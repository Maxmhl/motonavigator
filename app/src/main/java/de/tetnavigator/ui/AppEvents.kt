package de.tetnavigator.ui

import de.tetnavigator.feature.bedienung.RemoteAction
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Verteilt Tasten-/Remote-Aktionen und eingehende Import-URIs an die Screens. */
@Singleton
class AppEvents @Inject constructor() {
    private val _remote = MutableSharedFlow<RemoteAction>(extraBufferCapacity = 16)
    val remote: SharedFlow<RemoteAction> = _remote
    fun emit(a: RemoteAction) = _remote.tryEmit(a)

    /** Lern-Modus für die Lenker-Fernbedienung: nächste Taste wird belegt. */
    val learning = MutableStateFlow<RemoteAction?>(null)
    private val _learned = MutableSharedFlow<Pair<RemoteAction, Int>>(extraBufferCapacity = 4)
    val learned: SharedFlow<Pair<RemoteAction, Int>> = _learned
    fun learned(a: RemoteAction, code: Int) = _learned.tryEmit(a to code)

    /** Über „Teilen mit…“ / „Öffnen mit…“ eingegangene Dateien. */
    private val _incoming = MutableStateFlow<List<android.net.Uri>>(emptyList())
    val incoming: StateFlow<List<android.net.Uri>> = _incoming
    fun offer(uris: List<android.net.Uri>) { if (uris.isNotEmpty()) _incoming.value = uris }
    fun consumeIncoming(): List<android.net.Uri> = _incoming.value.also { _incoming.value = emptyList() }

    /** Benachrichtigung „Download“ angetippt → Download-Anzeige öffnen. */
    val openDownloads = MutableStateFlow(false)

    /** Fahr-Screen aktiv → Display an lassen. */
    val keepScreenOn = MutableStateFlow(false)
}
