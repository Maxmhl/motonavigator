package de.tetnavigator.feature.setup

import de.tetnavigator.core.common.Countries
import de.tetnavigator.core.offlinedata.Manifest
import de.tetnavigator.core.offlinedata.PackageType
import de.tetnavigator.core.preferences.SettingsRepository
import de.tetnavigator.data.download.DownloadController
import de.tetnavigator.data.download.DownloadPlan
import de.tetnavigator.data.download.DownloadState
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

data class CountryOption(
    val code: String,
    val name: String,
    val bytes: Long,
    val types: Set<PackageType>,
) {
    /** Hinweis, wenn wichtige Pakete fehlen. */
    val missing: List<PackageType> get() = listOf(PackageType.MAP, PackageType.ROUTING, PackageType.POI).filter { it !in types }
}

data class PlanCheck(
    val totalBytes: Long,
    val remainingBytes: Long,
    val freeBytes: Long,
    val fits: Boolean,
    val fileCount: Int,
)

/**
 * Einrichtungs-Assistent (Spezifikation 4):
 * Willkommen → Länder wählen → Download aller Pakete → Fertig (offlineLocked = true).
 */
@Singleton
class SetupInteractor @Inject constructor(
    private val settings: SettingsRepository,
    private val download: DownloadController,
) {
    val downloadState: Flow<DownloadState> = download.state

    suspend fun loadManifest(baseUrl: String): Manifest {
        val url = baseUrl.trim()
        require(url.startsWith("http://") || url.startsWith("https://")) { "Basis-URL muss mit http:// oder https:// beginnen" }
        val m = download.fetchManifest(url)
        settings.update { it.copy(baseUrl = url) }
        return m
    }

    fun options(manifest: Manifest): List<CountryOption> =
        manifest.countries.map { (cc, files) ->
            CountryOption(
                code = cc.uppercase(),
                name = manifest.names[cc] ?: Countries.name(cc),
                bytes = files.sumOf { it.bytes },
                types = files.mapNotNull { it.packageType }.toSet(),
            )
        }.sortedBy { it.name }

    fun check(manifest: Manifest, countries: Set<String>): PlanCheck {
        val plan: DownloadPlan = download.plan(manifest, countries)
        val free = download.freeBytes()
        return PlanCheck(plan.totalBytes, plan.remainingBytes, free, plan.fitsInto(free), plan.files.size)
    }

    suspend fun start(countries: Set<String>, wifiOnly: Boolean) = download.start(countries, wifiOnly)
    fun cancel() = download.cancel()

    /** Einrichtung ohne Download überspringen (z. B. Pakete schon per USB kopiert). */
    suspend fun skip() = settings.update { it.copy(setupDone = true) }
}
