package de.tetnavigator.feature.settings

import de.tetnavigator.core.offlinedata.OfflineStorage
import de.tetnavigator.core.preferences.AppSettings
import de.tetnavigator.core.preferences.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Einstellungen mit sinnvollen Grenzen für die −/+ Einsteller. */
@Singleton
class SettingsInteractor @Inject constructor(
    private val repo: SettingsRepository,
    private val storage: OfflineStorage,
) {
    val settings: Flow<AppSettings> = repo.settings

    suspend fun update(block: (AppSettings) -> AppSettings) = repo.update(block)

    suspend fun stepDeviation(delta: Int) = repo.update { it.copy(deviationThresholdM = (it.deviationThresholdM + delta).coerceIn(10, 500)) }
    suspend fun stepMaxAge(delta: Int) = repo.update { it.copy(gpxMaxAgeDays = (it.gpxMaxAgeDays + delta).coerceIn(7, 730)) }
    suspend fun stepTank(delta: Double) = repo.update {
        val t = (it.tankLiters + delta).coerceIn(3.0, 60.0)
        it.copy(tankLiters = t, fuelRemainingL = it.fuelRemainingL.coerceAtMost(t))
    }
    suspend fun stepConsumption(delta: Double) = repo.update { it.copy(consumptionLPer100 = ((it.consumptionLPer100 + delta) * 10).let(Math::round) / 10.0).let { s -> s.copy(consumptionLPer100 = s.consumptionLPer100.coerceIn(1.5, 15.0)) } }
    suspend fun stepFallbackSpeed(delta: Double) = repo.update { it.copy(fallbackOffroadKmh = (it.fallbackOffroadKmh + delta).coerceIn(5.0, 80.0)) }

    /** „Getankt“ – Reichweite auf vollen Tank setzen. */
    suspend fun refuel() = repo.update { it.copy(fuelRemainingL = it.tankLiters) }

    /**
     * „Pakete neu laden“: hebt die Offline-Sperre einmalig auf. Nach dem nächsten
     * erfolgreichen Download wird sie automatisch wieder gesetzt.
     */
    suspend fun unlockForReload() = repo.unlockForReload()
    suspend fun cancelReload() = repo.cancelReload()

    fun installedBytes(): Long = storage.totalInstalledBytes()
    fun installedCountries(): Set<String> = storage.installedCountries()
}
