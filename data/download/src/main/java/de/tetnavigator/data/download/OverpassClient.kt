package de.tetnavigator.data.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Lädt die POIs eines Landes einmalig über die Overpass-API (mehrere Server als Ausweich). */
class OverpassClient(base: OkHttpClient, private val endpoints: List<String> = DirectSources.OVERPASS) {
    private val client = base.newBuilder().readTimeout(15, TimeUnit.MINUTES).callTimeout(20, TimeUnit.MINUTES).build()

    suspend fun download(query: String, target: File) = withContext(Dispatchers.IO) {
        val errors = ArrayList<String>()
        for (url in endpoints) {
            try {
                val req = Request.Builder().url(url).post(FormBody.Builder().add("data", query).build()).build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) { errors += "$url: HTTP ${r.code}"; return@use }
                    val tmp = File(target.path + ".part")
                    r.body?.byteStream()?.use { i -> tmp.outputStream().use { i.copyTo(it) } } ?: run { errors += "$url: leer"; return@use }
                    val head = tmp.inputStream().use { s -> ByteArray(4096).let { b -> String(b, 0, s.read(b).coerceAtLeast(0)) } }
                    if (!head.trimStart().startsWith("{") || !head.contains("\"elements\"")) { errors += "$url: keine JSON-Antwort"; tmp.delete(); return@use }
                    // Overpass meldet Laufzeitfehler (Timeout/Speicher) im Feld "remark"
                    val tail = tmp.readText().takeLast(2000)
                    if (tail.contains("\"remark\"") && (tail.contains("runtime error") || tail.contains("Query timed out"))) {
                        errors += "$url: Abfrage abgebrochen"; tmp.delete(); return@use
                    }
                    if (target.exists()) target.delete()
                    tmp.renameTo(target)
                    return@withContext
                }
            } catch (e: OfflineLockedException) {
                throw e
            } catch (e: Exception) {
                errors += "$url: ${e.message}"
            }
        }
        throw DownloadException("POI-Download fehlgeschlagen (${errors.joinToString("; ")})")
    }
}
