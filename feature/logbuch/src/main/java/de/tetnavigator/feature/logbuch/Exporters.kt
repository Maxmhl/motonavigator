package de.tetnavigator.feature.logbuch

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import de.tetnavigator.core.common.Format
import de.tetnavigator.core.database.HazardPinEntity
import de.tetnavigator.core.database.JournalEntryEntity
import de.tetnavigator.core.database.RideEntity
import de.tetnavigator.core.database.RidePointEntity
import de.tetnavigator.core.database.TetDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.io.Writer
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Schreibt GPX 1.1 (eigene Fahrten, Notizen, Gefahren-Pins). */
object GpxWriter {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun time(ms: Long) = Instant.ofEpochMilli(ms).toString()

    fun write(
        out: Writer,
        name: String,
        rides: List<Pair<RideEntity, List<RidePointEntity>>>,
        notes: List<JournalEntryEntity>,
        hazards: List<HazardPinEntity>,
    ) {
        out.write("""<?xml version="1.0" encoding="UTF-8"?>""" + "\n")
        out.write("""<gpx version="1.1" creator="TET Navigator" xmlns="http://www.topografix.com/GPX/1/1">""" + "\n")
        out.write("  <metadata><name>${esc(name)}</name><time>${time(System.currentTimeMillis())}</time></metadata>\n")
        for (h in hazards) {
            out.write("  <wpt lat=\"${h.lat}\" lon=\"${h.lon}\"><time>${time(h.createdAt)}</time><name>${esc(h.type)}</name>")
            if (h.note.isNotBlank()) out.write("<desc>${esc(h.note)}</desc>")
            out.write("<sym>Danger Area</sym><type>Gefahr</type></wpt>\n")
        }
        for (n in notes) {
            out.write("  <wpt lat=\"${n.lat}\" lon=\"${n.lon}\"><time>${time(n.createdAt)}</time><name>${esc(n.text.take(30).ifBlank { "Notiz" })}</name>")
            out.write("<desc>${esc(n.text)}</desc><sym>Flag</sym><type>Notiz</type></wpt>\n")
        }
        for ((r, pts) in rides) {
            out.write("  <trk><name>${esc(r.title.ifBlank { "Fahrt" })}</name><trkseg>\n")
            for (p in pts) {
                out.write("    <trkpt lat=\"${p.lat}\" lon=\"${p.lon}\">")
                p.ele?.let { out.write("<ele>${"%.1f".format(Locale.US, it)}</ele>") }
                out.write("<time>${time(p.time)}</time></trkpt>\n")
            }
            out.write("  </trkseg></trk>\n")
        }
        out.write("</gpx>\n")
        out.flush()
    }
}

/**
 * Export als lokale Datei (Ziel per Systemdialog gewählt – keine Cloud,
 * kein Teilen von TET-GPX).
 */
@Singleton
class LogbuchExporter @Inject constructor(private val db: TetDatabase) {

    /** Reisetagebuch als GPX: alle Fahrten + Notizen + Pins. */
    suspend fun exportGpx(out: OutputStream, includeRides: Boolean = true) = withContext(Dispatchers.IO) {
        val rides = if (includeRides) db.rideDao().rides().map { it to db.rideDao().points(it.id) } else emptyList()
        out.bufferedWriter().let { w ->
            GpxWriter.write(w, "TET Navigator Logbuch", rides, db.journalDao().all(), db.hazardDao().all())
        }
    }

    /** Trail-Notizen exportieren (nur Notizen + Gefahren-Pins, z. B. für den Linesman). */
    suspend fun exportTrailNotes(out: OutputStream) = withContext(Dispatchers.IO) {
        out.bufferedWriter().let { w -> GpxWriter.write(w, "TET Trail-Notizen", emptyList(), db.journalDao().all(), db.hazardDao().all()) }
    }

    /** Reisetagebuch als PDF (A4, Kennzahlen, Fahrten, Notizen mit Fotos). */
    suspend fun exportPdf(out: OutputStream, resolver: ContentResolver) = withContext(Dispatchers.IO) {
        val doc = PdfDocument()
        val w = 595; val h = 842; val margin = 40f
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
        var c: Canvas = page.canvas
        var y = margin
        val title = Paint().apply { textSize = 22f; isFakeBoldText = true; color = Color.rgb(18, 19, 16) }
        val bold = Paint().apply { textSize = 12f; isFakeBoldText = true; color = Color.rgb(18, 19, 16) }
        val text = Paint().apply { textSize = 11f; color = Color.rgb(50, 50, 45) }
        val accent = Paint().apply { color = Color.rgb(255, 107, 26); strokeWidth = 3f }
        val df = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY)

        fun newPageIfNeeded(need: Float) {
            if (y + need > h - margin) {
                doc.finishPage(page); pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
                c = page.canvas; y = margin
            }
        }

        c.drawText("TET NAVIGATOR · REISETAGEBUCH", margin, y + 20, title); y += 32
        c.drawLine(margin, y, w - margin, y, accent); y += 20
        val t = db.rideDao().totals()
        val dist = t.distanceM ?: 0.0
        c.drawText("Trail-km: ${Format.km0(dist)}   Offroad: ${if (dist > 0) Format.percent((t.offroadM ?: 0.0) / dist) else "–"}   Höhenmeter: ${Format.thousands((t.ascentM ?: 0.0).toLong())}", margin, y, bold); y += 24

        c.drawText("FAHRTEN", margin, y, bold); y += 16
        for (r in db.rideDao().rides()) {
            newPageIfNeeded(30f)
            val countries = db.rideDao().countriesOfRide(r.id).joinToString(", ") { "${it.countryCode} ${Format.km0(it.distanceM)} km" }
            c.drawText("${df.format(Date(r.startedAt))} · ${r.title} · ${Format.km1(r.distanceM)} km · ${Format.hm((r.endedAt ?: r.startedAt) - r.startedAt)} h", margin, y, text); y += 14
            if (countries.isNotBlank()) { c.drawText("   $countries", margin, y, text); y += 14 }
        }
        y += 10
        newPageIfNeeded(20f)
        c.drawText("NOTIZEN & FOTOS", margin, y, bold); y += 16
        for (n in db.journalDao().all()) {
            newPageIfNeeded(40f)
            c.drawText("${df.format(Date(n.createdAt))} · ${"%.5f, %.5f".format(Locale.US, n.lat, n.lon)}", margin, y, bold); y += 14
            for (line in wrap(n.text, 95)) { newPageIfNeeded(14f); c.drawText(line, margin, y, text); y += 14 }
            n.photoUri?.let { uri ->
                val bmp = try {
                    resolver.openInputStream(Uri.parse(uri))?.use { s -> BitmapFactory.decodeStream(s, null, BitmapFactory.Options().apply { inSampleSize = 4 }) }
                } catch (_: Exception) { null }
                if (bmp != null) {
                    val tw = 240f; val th = tw * bmp.height / bmp.width.coerceAtLeast(1)
                    newPageIfNeeded(th + 10)
                    c.drawBitmap(bmp, null, Rect(margin.toInt(), y.toInt(), (margin + tw).toInt(), (y + th).toInt()), null)
                    y += th + 10
                    bmp.recycle()
                }
            }
            y += 6
        }
        y += 10
        newPageIfNeeded(20f)
        c.drawText("GEFAHREN-PINS", margin, y, bold); y += 16
        for (hz in db.hazardDao().all()) {
            newPageIfNeeded(16f)
            c.drawText("${df.format(Date(hz.createdAt))} · ${hz.type} · ${"%.5f, %.5f".format(Locale.US, hz.lat, hz.lon)} ${hz.note}", margin, y, text); y += 14
        }
        newPageIfNeeded(20f)
        y += 10
        c.drawText("Kartendaten © OpenStreetMap-Mitwirkende", margin, y, text)
        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
        out.flush()
    }

    private fun wrap(s: String, n: Int): List<String> {
        val out = ArrayList<String>()
        for (para in s.split('\n')) {
            var line = StringBuilder()
            for (word in para.split(' ')) {
                if (line.length + word.length + 1 > n) { out += line.toString(); line = StringBuilder() }
                if (line.isNotEmpty()) line.append(' ')
                line.append(word)
            }
            out += line.toString()
        }
        return out
    }

}
