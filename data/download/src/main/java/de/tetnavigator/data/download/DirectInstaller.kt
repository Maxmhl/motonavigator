package de.tetnavigator.data.download

import de.tetnavigator.core.common.Countries
import de.tetnavigator.core.offlinedata.CountryShape
import de.tetnavigator.core.offlinedata.CountryShapes
import de.tetnavigator.core.offlinedata.InstalledPackage
import de.tetnavigator.core.offlinedata.OfflineStorage
import de.tetnavigator.core.offlinedata.PackageFile
import de.tetnavigator.core.offlinedata.PackageType
import de.tetnavigator.core.offlinedata.PoiDbBuilder
import de.tetnavigator.core.offlinedata.PoiTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Optionen für die Einrichtung direkt aus dem Internet. */
data class DirectOptions(
    val countries: Set<String>,
    /** Höchste Karten-Zoomstufe (13 = Standard, 14 = hoch). */
    val maxZoom: Int = 13,
    val dem: Boolean = true,
    val demZoom: Int = 10,
    val pois: Boolean = true,
    val routing: Boolean = true,
)

data class CountryEstimate(
    val code: String,
    val mapBytes: Long,
    val mapTiles: Int,
    val demTiles: Int,
    val routingTiles: List<String>,
) {
    /** Terrarium-Kacheln sind im Schnitt ~60 kB groß (Land), Meer fehlt meist. */
    val demBytesEstimate: Long get() = demTiles * 60_000L
}

data class DirectEstimate(
    val build: String,
    val countries: List<CountryEstimate>,
    val routingBytes: Long,
    val poiBytesEstimate: Long,
    val directoryBytes: Long,
) {
    val totalBytes: Long get() = countries.sumOf { it.mapBytes + it.demBytesEstimate } + routingBytes + poiBytesEstimate + directoryBytes
}

data class DirectProgress(val step: String, val fraction: Float, val bytesDone: Long, val bytesTotal: Long)

/**
 * Einrichtung ohne PC: Karte (Protomaps-Ausschnitt), Höhendaten (Terrarium),
 * Routing (BRouter) und POIs (Overpass) werden direkt geladen und lokal zu den
 * gleichen Paketen verarbeitet, die sonst am PC gebaut werden.
 */
@Singleton
class DirectInstaller @Inject constructor(
    private val client: OkHttpClient,
    private val storage: OfflineStorage,
    private val shapes: CountryShapes,
) {
    private val protomaps = ProtomapsExtractor(client)
    private val terrarium = TerrariumDownloader(client)
    private val overpass = OverpassClient(client)
    private val files = PackageDownloader(client)

    private fun shape(cc: String): CountryShape = shapes.of(cc) ?: throw DownloadException("Keine Landesgrenze für $cc")

    suspend fun estimate(o: DirectOptions): DirectEstimate = withContext(Dispatchers.IO) {
        val build = protomaps.latestBuild()
        var dirBytes = 0L
        val list = o.countries.sorted().map { cc ->
            val s = shape(cc)
            val plan = protomaps.plan(build, s.tileIds(o.maxZoom))
            dirBytes += plan.directoryBytes
            val dem = if (o.dem) demTiles(s, o.demZoom).size else 0
            CountryEstimate(cc, plan.tileBytes, plan.tileCount, dem, if (o.routing) DirectSources.rd5Tiles { a, b, c, d -> s.intersects(a, b, c, d) } else emptyList())
        }
        val rd5 = list.flatMap { it.routingTiles }.distinct()
        val rd5Bytes = rd5.sumOf { contentLength(DirectSources.rd5(it)) }
        DirectEstimate(build, list, rd5Bytes, if (o.pois) list.size * 8_000_000L else 0, dirBytes)
    }

    suspend fun install(o: DirectOptions, build: String?, onProgress: (DirectProgress) -> Unit): List<InstalledPackage> = withContext(Dispatchers.IO) {
        val b = build ?: protomaps.latestBuild()
        val installed = ArrayList<InstalledPackage>()
        val countries = o.countries.sorted()
        val steps = countries.size * ((if (o.dem) 1 else 0) + 1 + (if (o.pois) 1 else 0)) + if (o.routing) 1 else 0
        var step = 0
        fun report(label: String, within: Float, done: Long = 0, total: Long = 0) =
            onProgress(DirectProgress(label, ((step + within.coerceIn(0f, 1f)) / steps.coerceAtLeast(1)), done, total))

        for (cc in countries) {
            val s = shape(cc)
            val name = Countries.name(cc)
            val lc = cc.lowercase()

            // 1. Karte
            report("$name · Karte wird geplant", 0f)
            val plan = protomaps.plan(b, s.tileIds(o.maxZoom))
            val mapFile = PackageFile(PackageType.MAP_PROTOMAPS.id, "$lc-protomaps.pmtiles")
            val mapTarget = storage.fileFor(cc, mapFile)
            if (plan.tileCount > 0) {
                protomaps.extract(plan, mapTarget, storage.workDir("map-$cc")) { d, t -> report("$name · Karte", if (t > 0) d.toFloat() / t else 0f, d, t) }
                installed += InstalledPackage(cc, mapFile.type, mapFile.file, mapTarget.length(), "", System.currentTimeMillis())
                storage.register(listOf(installed.last()))
            }
            step++

            // 2. Höhendaten
            if (o.dem) {
                val demFile = PackageFile(PackageType.DEM_TERRARIUM.id, "$lc-terrarium.pmtiles")
                val demTarget = storage.fileFor(cc, demFile)
                val tiles = demTiles(s, o.demZoom)
                val bb = s.rings.let { r -> doubleArrayOf(r.minOf { it.minLon }, r.minOf { it.minLat }, r.maxOf { it.maxLon }, r.maxOf { it.maxLat }) }
                terrarium.download(tiles, demTarget, storage.workDir("dem-$cc"), bb) { d, t -> report("$name · Höhendaten $d/$t", d.toFloat() / t) }
                installed += InstalledPackage(cc, demFile.type, demFile.file, demTarget.length(), "", System.currentTimeMillis())
                storage.register(listOf(installed.last()))
                step++
            }

            // 3. POIs (Camping & Versorgung) – nicht kritisch: Fehler brechen die Einrichtung nicht ab
            if (o.pois) {
                report("$name · Camping & Versorgung", 0.1f)
                val poiFile = PackageFile(PackageType.POI.id, "$lc-poi.sqlite")
                val target = storage.fileFor(cc, poiFile)
                val json = File(storage.workDir("poi-$cc"), "overpass.json")
                try {
                    overpass.download(PoiTags.overpassQuery(cc), json)
                    report("$name · POIs werden verarbeitet", 0.8f)
                    PoiDbBuilder.fromOverpassJson(json, target)
                    installed += InstalledPackage(cc, poiFile.type, poiFile.file, target.length(), "", System.currentTimeMillis())
                    storage.register(listOf(installed.last()))
                } catch (e: OfflineLockedException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("DirectInstaller", "POIs für $cc nicht geladen", e)
                } finally {
                    json.parentFile?.deleteRecursively()
                }
                step++
            }
        }

        // 4. Routing-Segmente (länderübergreifend, je Kachel einmal)
        if (o.routing) {
            val tiles = countries.flatMap { cc -> DirectSources.rd5Tiles { a, b2, c, d -> shape(cc).intersects(a, b2, c, d) }.map { cc to it } }
                .distinctBy { it.second }
            tiles.forEachIndexed { i, (cc, name) ->
                report("Routing ${i + 1}/${tiles.size} · $name", i.toFloat() / tiles.size)
                val pf = PackageFile(PackageType.ROUTING.id, "$name.rd5")
                val target = storage.fileFor(cc, pf)
                val url = PackageDownloader.resolve(DirectSources.rd5(name), DirectSources.rd5(name))
                val size = contentLength(url.toString())
                if (size > 0) {
                    files.download(url, storage.partFileFor(cc, pf), target, size, "")
                    installed += InstalledPackage(cc, pf.type, pf.file, target.length(), "", System.currentTimeMillis())
                    storage.register(listOf(installed.last()))
                }
            }
            step++
        }
        onProgress(DirectProgress("Fertig", 1f, 0, 0))
        installed
    }

    private fun demTiles(s: CountryShape, maxZoom: Int): List<Triple<Int, Int, Int>> =
        (0..maxZoom).flatMap { z -> s.tilesAt(z, 5_000.0) }

    /** Größe per HEAD; 0 = existiert nicht (z. B. Kachel nur Meer). */
    private fun contentLength(url: String): Long =
        client.newCall(Request.Builder().url(url).head().build()).execute().use { r ->
            if (r.isSuccessful) r.header("Content-Length")?.toLongOrNull() ?: 0 else 0
        }
}
