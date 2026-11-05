package de.tetnavigator.core.offlinedata

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ablage aller Offline-Pakete auf dem Gerät:
 * ```
 * packages/<CC>/<datei>         Karte, Schummerung, Höhenlinien, POIs je Land
 * packages/routing/<kachel>.rd5 BRouter-Segmente (länderübergreifend)
 * packages/installed.json       Registry
 * ```
 */
@Singleton
class OfflineStorage @Inject constructor(@ApplicationContext private val context: Context) {

    val root: File by lazy {
        (context.getExternalFilesDir("packages") ?: File(context.filesDir, "packages")).apply { mkdirs() }
    }
    val segmentsDir: File get() = File(root, "routing").apply { mkdirs() }
    val profilesDir: File get() = File(context.filesDir, "brouter-profiles").apply { mkdirs() }
    private val registryFile get() = File(root, "installed.json")

    private val _registry = MutableStateFlow(load())
    val registry: StateFlow<InstalledRegistry> = _registry

    fun fileFor(country: String, f: PackageFile): File = File(root, relativePath(country, f))
    fun partFileFor(country: String, f: PackageFile): File = File(fileFor(country, f).path + ".part")

    fun installed(type: PackageType): List<Pair<String, File>> =
        _registry.value.packages.filter { PackageType.of(it.type) == type }
            .map { it.country to fileFor(it.country, PackageFile(it.type, it.file)) }
            .filter { it.second.exists() }

    fun installedCountries(): Set<String> = _registry.value.packages.map { it.country }.toSet()

    fun mapFiles() = installed(PackageType.MAP)
    fun protomapsFiles() = installed(PackageType.MAP_PROTOMAPS)
    fun hillshadeFiles() = installed(PackageType.HILLSHADE)
    fun terrariumFiles() = installed(PackageType.DEM_TERRARIUM)
    fun hasAnyMap() = mapFiles().isNotEmpty() || protomapsFiles().isNotEmpty()

    /** Arbeitsverzeichnis für unterbrochene Direkt-Downloads. */
    fun workDir(name: String): File = File(root, ".work/$name").apply { mkdirs() }
    fun contourFiles() = installed(PackageType.CONTOURS)
    fun poiFiles() = installed(PackageType.POI)
    fun hasRouting() = installed(PackageType.ROUTING).isNotEmpty()

    @Synchronized
    fun register(pkgs: List<InstalledPackage>) {
        val keep = _registry.value.packages.filterNot { old -> pkgs.any { it.country == old.country && it.file == old.file } }
        val reg = InstalledRegistry(keep + pkgs)
        registryFile.writeText(Manifest.json.encodeToString(InstalledRegistry.serializer(), reg))
        _registry.value = reg
    }

    fun freeBytes(): Long = root.usableSpace

    fun totalInstalledBytes(): Long = _registry.value.packages.sumOf { it.bytes }

    private fun load(): InstalledRegistry = try {
        if (registryFile.exists()) Manifest.json.decodeFromString(InstalledRegistry.serializer(), registryFile.readText()) else InstalledRegistry()
    } catch (_: Exception) {
        InstalledRegistry()
    }

    companion object {
        fun relativePath(country: String, f: PackageFile): String {
            val name = File(f.file).name // keine Pfad-Traversierung aus dem Manifest
            return if (PackageType.of(f.type) == PackageType.ROUTING) "routing/$name" else "${country.uppercase()}/$name"
        }
    }
}
