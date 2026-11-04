package de.tetnavigator.core.offlinedata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Paket-Typen aus manifest.json (Spezifikation 4). */
enum class PackageType(val id: String, val labelDe: String) {
    MAP("map", "Karte"),
    HILLSHADE("hillshade", "Höhendaten/Schummerung"),
    CONTOURS("contours", "Höhenlinien"),
    ROUTING("routing", "Routing-Segmente"),
    POI("poi", "POIs"),
    ;

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

@Serializable
data class PackageFile(
    val type: String,
    val file: String,
    val bytes: Long = 0,
    val sha256: String = "",
    /** Optional: abweichende relative/absolute URL; sonst Basis-URL + file. */
    val url: String? = null,
) {
    val packageType: PackageType? get() = PackageType.of(type)
}

@Serializable
data class Manifest(
    val version: Int = 1,
    val countries: Map<String, List<PackageFile>> = emptyMap(),
    /** Optional: Anzeigenamen der Länder. */
    val names: Map<String, String> = emptyMap(),
) {
    fun bytesFor(codes: Collection<String>): Long = uniqueFiles(codes).sumOf { it.second.bytes }

    /** Dateien der gewählten Länder; Routing-Kacheln (über Länder geteilt) nur einmal. */
    fun uniqueFiles(codes: Collection<String>): List<Pair<String, PackageFile>> {
        val seen = HashSet<String>()
        val out = ArrayList<Pair<String, PackageFile>>()
        for (cc in codes) for (f in countries[cc].orEmpty()) {
            val key = OfflineStorage.relativePath(cc, f)
            if (seen.add(key)) out += cc to f
        }
        return out
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
        fun parse(text: String): Manifest = json.decodeFromString(serializer(), text)
    }
}

/** Ein installiertes Paket (Registry installed.json). */
@Serializable
data class InstalledPackage(
    val country: String,
    val type: String,
    val file: String,
    val bytes: Long,
    val sha256: String,
    @SerialName("installedAt") val installedAt: Long,
)

@Serializable
data class InstalledRegistry(val packages: List<InstalledPackage> = emptyList())
