package de.tetnavigator.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(private val store: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = store.data.map(::toSettings)

    suspend fun current(): AppSettings = settings.first()

    /** Darf das Download-Modul gerade Requests senden? */
    suspend fun networkAllowed(): Boolean {
        val s = current()
        return !s.offlineLocked || s.reloadUnlocked
    }

    suspend fun update(block: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val old = toSettings(prefs)
            val new = block(old)
            write(prefs, new)
        }
    }

    /** Nach erfolgreichem Download: Sperre setzen, Neu-Laden-Freigabe zurücknehmen. */
    suspend fun lockOffline(installed: Set<String>) = update {
        it.copy(offlineLocked = true, reloadUnlocked = false, setupDone = true, installedCountries = installed)
    }

    /** „Pakete neu laden“ – bewusste Freigabe durch den Nutzer. */
    suspend fun unlockForReload() = update { it.copy(reloadUnlocked = true) }
    suspend fun cancelReload() = update { it.copy(reloadUnlocked = false) }

    private fun toSettings(p: Preferences) = AppSettings(
        offlineLocked = p[K.offlineLocked] ?: false,
        reloadUnlocked = p[K.reloadUnlocked] ?: false,
        setupDone = p[K.setupDone] ?: false,
        permissionsExplained = p[K.permissionsExplained] ?: false,
        baseUrl = p[K.baseUrl] ?: "",
        wifiOnly = p[K.wifiOnly] ?: true,
        selectedCountries = p[K.selectedCountries] ?: emptySet(),
        installedCountries = p[K.installedCountries] ?: emptySet(),
        deviationThresholdM = p[K.deviationThresholdM] ?: 40,
        alarmSound = p[K.alarmSound] ?: true,
        alarmVibrate = p[K.alarmVibrate] ?: true,
        headingUp = p[K.headingUp] ?: true,
        autoZoom = p[K.autoZoom] ?: true,
        turnPreview = p[K.turnPreview] ?: true,
        activeSectionId = p[K.activeSectionId]?.takeIf { it > 0 },
        activeReversed = p[K.activeReversed] ?: false,
        gpxMaxAgeDays = p[K.gpxMaxAgeDays] ?: 60,
        tankLiters = p[K.tankLiters] ?: 13.0,
        consumptionLPer100 = p[K.consumption] ?: 4.5,
        fuelRemainingL = p[K.fuelRemaining] ?: (p[K.tankLiters] ?: 13.0),
        fallbackOffroadKmh = p[K.fallbackKmh] ?: 25.0,
        gloveMode = p[K.gloveMode] ?: true,
        volumeKeysZoom = p[K.volumeKeysZoom] ?: true,
        batteryMode = p[K.batteryMode] ?: false,
        dayMode = p[K.dayMode] ?: false,
        remoteMapping = decodeMapping(p[K.remoteMapping]),
        layerHillshade = p[K.layerHillshade] ?: true,
        layerContours = p[K.layerContours] ?: true,
        layerPois = p[K.layerPois] ?: true,
        layerHazards = p[K.layerHazards] ?: true,
        layerSurface = p[K.layerSurface] ?: false,
    )

    private fun write(p: MutablePreferences, s: AppSettings) {
        p[K.offlineLocked] = s.offlineLocked
        p[K.reloadUnlocked] = s.reloadUnlocked
        p[K.setupDone] = s.setupDone
        p[K.permissionsExplained] = s.permissionsExplained
        p[K.baseUrl] = s.baseUrl
        p[K.wifiOnly] = s.wifiOnly
        p[K.selectedCountries] = s.selectedCountries
        p[K.installedCountries] = s.installedCountries
        p[K.deviationThresholdM] = s.deviationThresholdM
        p[K.alarmSound] = s.alarmSound
        p[K.alarmVibrate] = s.alarmVibrate
        p[K.headingUp] = s.headingUp
        p[K.autoZoom] = s.autoZoom
        p[K.turnPreview] = s.turnPreview
        p[K.activeSectionId] = s.activeSectionId ?: 0L
        p[K.activeReversed] = s.activeReversed
        p[K.gpxMaxAgeDays] = s.gpxMaxAgeDays
        p[K.tankLiters] = s.tankLiters
        p[K.consumption] = s.consumptionLPer100
        p[K.fuelRemaining] = s.fuelRemainingL
        p[K.fallbackKmh] = s.fallbackOffroadKmh
        p[K.gloveMode] = s.gloveMode
        p[K.volumeKeysZoom] = s.volumeKeysZoom
        p[K.batteryMode] = s.batteryMode
        p[K.dayMode] = s.dayMode
        p[K.remoteMapping] = encodeMapping(s.remoteMapping)
        p[K.layerHillshade] = s.layerHillshade
        p[K.layerContours] = s.layerContours
        p[K.layerPois] = s.layerPois
        p[K.layerHazards] = s.layerHazards
        p[K.layerSurface] = s.layerSurface
    }

    private object K {
        val offlineLocked = booleanPreferencesKey("offlineLocked")
        val reloadUnlocked = booleanPreferencesKey("reloadUnlocked")
        val setupDone = booleanPreferencesKey("setupDone")
        val permissionsExplained = booleanPreferencesKey("permissionsExplained")
        val baseUrl = stringPreferencesKey("baseUrl")
        val wifiOnly = booleanPreferencesKey("wifiOnly")
        val selectedCountries = stringSetPreferencesKey("selectedCountries")
        val installedCountries = stringSetPreferencesKey("installedCountries")
        val deviationThresholdM = intPreferencesKey("deviationThresholdM")
        val alarmSound = booleanPreferencesKey("alarmSound")
        val alarmVibrate = booleanPreferencesKey("alarmVibrate")
        val headingUp = booleanPreferencesKey("headingUp")
        val autoZoom = booleanPreferencesKey("autoZoom")
        val turnPreview = booleanPreferencesKey("turnPreview")
        val activeSectionId = longPreferencesKey("activeSectionId")
        val activeReversed = booleanPreferencesKey("activeReversed")
        val gpxMaxAgeDays = intPreferencesKey("gpxMaxAgeDays")
        val tankLiters = doublePreferencesKey("tankLiters")
        val consumption = doublePreferencesKey("consumption")
        val fuelRemaining = doublePreferencesKey("fuelRemaining")
        val fallbackKmh = doublePreferencesKey("fallbackKmh")
        val gloveMode = booleanPreferencesKey("gloveMode")
        val volumeKeysZoom = booleanPreferencesKey("volumeKeysZoom")
        val batteryMode = booleanPreferencesKey("batteryMode")
        val dayMode = booleanPreferencesKey("dayMode")
        val remoteMapping = stringPreferencesKey("remoteMapping")
        val layerHillshade = booleanPreferencesKey("layerHillshade")
        val layerContours = booleanPreferencesKey("layerContours")
        val layerPois = booleanPreferencesKey("layerPois")
        val layerHazards = booleanPreferencesKey("layerHazards")
        val layerSurface = booleanPreferencesKey("layerSurface")
    }

    companion object {
        fun encodeMapping(m: Map<String, Int>) = m.entries.joinToString(",") { "${it.key}=${it.value}" }
        fun decodeMapping(s: String?): Map<String, Int> =
            s.orEmpty().split(',').mapNotNull {
                val (k, v) = it.split('=').takeIf { p -> p.size == 2 } ?: return@mapNotNull null
                v.toIntOrNull()?.let { code -> k to code }
            }.toMap()

        fun create(context: Context, name: String = "settings"): DataStore<Preferences> =
            PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(name) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object PreferencesModule {
    @Provides @Singleton
    fun dataStore(@ApplicationContext ctx: Context): DataStore<Preferences> = SettingsRepository.create(ctx)
}
