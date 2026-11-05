package de.tetnavigator.core.preferences

/** Alle Einstellungen der App (DataStore). */
data class AppSettings(
    /** Nach erfolgreichem Download: keine Netzwerk-Requests mehr (Spezifikation 1.1). */
    val offlineLocked: Boolean = false,
    /** Nutzer hat in den Einstellungen bewusst „Pakete neu laden“ gestartet. */
    val reloadUnlocked: Boolean = false,
    val setupDone: Boolean = false,
    val permissionsExplained: Boolean = false,
    val baseUrl: String = "",
    val wifiOnly: Boolean = true,
    val selectedCountries: Set<String> = emptySet(),
    val installedCountries: Set<String> = emptySet(),
    /** "direct" = ohne PC aus öffentlichen Quellen, "server" = eigener Server (manifest.json). */
    val setupMode: String = "direct",
    val directMaxZoom: Int = 13,
    val directDem: Boolean = true,
    // Navigation
    val deviationThresholdM: Int = 40,
    val alarmSound: Boolean = true,
    val alarmVibrate: Boolean = true,
    val headingUp: Boolean = true,
    val autoZoom: Boolean = true,
    val turnPreview: Boolean = true,
    val activeSectionId: Long? = null,
    val activeReversed: Boolean = false,
    // Import
    val gpxMaxAgeDays: Int = 60,
    // Logistik
    val tankLiters: Double = 13.0,
    val consumptionLPer100: Double = 4.5,
    val fuelRemainingL: Double = 13.0,
    val fallbackOffroadKmh: Double = 25.0,
    // Bedienung
    val gloveMode: Boolean = true,
    val volumeKeysZoom: Boolean = true,
    val batteryMode: Boolean = false,
    val dayMode: Boolean = false,
    /** Eigene Tastenbelegung der Lenker-Fernbedienung: Aktion → KeyCode. */
    val remoteMapping: Map<String, Int> = emptyMap(),
    // Kartenebenen
    val layerHillshade: Boolean = true,
    val layerContours: Boolean = true,
    val layerPois: Boolean = true,
    val layerHazards: Boolean = true,
    val layerSurface: Boolean = false,
) {
    val rangeKm: Double get() = if (consumptionLPer100 <= 0) 0.0 else fuelRemainingL / consumptionLPer100 * 100.0
    val fullRangeKm: Double get() = if (consumptionLPer100 <= 0) 0.0 else tankLiters / consumptionLPer100 * 100.0
}
