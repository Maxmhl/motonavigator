package de.tetnavigator.data.download

import de.tetnavigator.core.offlinedata.Manifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

class DownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Lädt Manifest und Paketdateien. Fortsetzbar über HTTP Range (.part-Datei),
 * prüft Größe und SHA-256 und verschiebt erst danach an den Zielort.
 */
class PackageDownloader(private val client: OkHttpClient) {

    constructor(policy: NetworkPolicy) : this(HttpClients.create(policy))

    suspend fun fetchManifest(baseUrl: String): Manifest = withContext(Dispatchers.IO) {
        val url = resolve(baseUrl, "manifest.json")
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw DownloadException("Manifest nicht erreichbar (HTTP ${r.code})")
            val body = r.body?.string() ?: throw DownloadException("Manifest leer")
            try {
                Manifest.parse(body)
            } catch (e: Exception) {
                throw DownloadException("Manifest ungültig: ${e.message}", e)
            }
        }
    }

    /**
     * @param onProgress (bytesDieserDatei, gesamtDieserDatei)
     */
    suspend fun download(
        url: HttpUrl,
        part: File,
        target: File,
        expectedBytes: Long,
        sha256: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        if (target.exists() && verify(target, expectedBytes, sha256)) return@withContext target

        var offset = if (part.exists()) part.length() else 0L
        if (expectedBytes > 0 && offset > expectedBytes) { part.delete(); offset = 0 }

        val req = Request.Builder().url(url).apply {
            if (offset > 0) header("Range", "bytes=$offset-")
        }.build()

        client.newCall(req).execute().use { r ->
            val append = when {
                r.code == 206 -> true
                r.code == 416 && expectedBytes > 0 && offset == expectedBytes -> true // schon komplett
                r.isSuccessful -> { offset = 0; false } // Server ignoriert Range → neu beginnen
                else -> throw DownloadException("Download fehlgeschlagen: ${url.encodedPath} (HTTP ${r.code})")
            }
            if (r.code != 416) {
                val body = r.body ?: throw DownloadException("Leere Antwort")
                val total = if (expectedBytes > 0) expectedBytes else offset + body.contentLength().coerceAtLeast(0)
                FileOutputStream(part, append).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var done = offset
                        var lastReport = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastReport > 256 * 1024) { onProgress(done, total); lastReport = done }
                        }
                        onProgress(done, total)
                    }
                }
            }
        }

        if (!verify(part, expectedBytes, sha256)) {
            part.delete()
            throw DownloadException("Prüfsumme falsch: ${target.name} – Datei wird neu geladen")
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true); part.delete()
        }
        target
    }

    companion object {
        fun normalizeBase(baseUrl: String): String {
            val t = baseUrl.trim()
            return if (t.endsWith("/")) t else "$t/"
        }

        fun resolve(baseUrl: String, path: String): HttpUrl {
            val base = normalizeBase(baseUrl).toHttpUrlOrNull()
                ?: throw DownloadException("Ungültige Basis-URL: $baseUrl")
            return path.toHttpUrlOrNull() ?: base.resolve(path) ?: throw DownloadException("Ungültiger Pfad: $path")
        }

        fun isValidSha(sha: String) = sha.length == 64 && sha.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

        fun verify(file: File, expectedBytes: Long, sha256: String): Boolean {
            if (!file.exists()) return false
            if (expectedBytes > 0 && file.length() != expectedBytes) return false
            if (!isValidSha(sha256)) return true // kein Hash im Manifest → nur Größe
            return sha256(file).equals(sha256, ignoreCase = true)
        }

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
