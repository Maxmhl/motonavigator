package de.tetnavigator.core.offlinedata

/** Untergrund-Klasse nach OSM `surface` / `tracktype` / `highway`. */
enum class SurfaceClass(val code: Int, val labelDe: String) {
    UNKNOWN(0, "unbekannt"),
    GRAVEL(1, "Schotter"),
    DIRT(2, "Erde"),
    ASPHALT(3, "Asphalt"),
    ;

    val offroad: Boolean get() = this == GRAVEL || this == DIRT

    companion object {
        fun ofCode(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN

        private val paved = setOf("asphalt", "paved", "concrete", "concrete:plates", "concrete:lanes", "paving_stones", "sett", "chipseal", "metal", "wood", "cobblestone", "unhewn_cobblestone", "bricks")
        private val gravel = setOf("gravel", "fine_gravel", "compacted", "pebblestone", "unpaved", "rock", "stone", "shells")
        private val dirt = setOf("dirt", "earth", "ground", "mud", "sand", "grass", "soil", "woodchips", "clay", "grass_paver", "snow", "ice")
        private val roadsUsuallyPaved = setOf("motorway", "trunk", "primary", "secondary", "tertiary", "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link", "residential", "living_street")

        fun classify(surface: String?, tracktype: String?, highway: String?): SurfaceClass {
            val s = surface?.lowercase()?.substringBefore(';')
            when {
                s == null -> Unit
                s in paved -> return ASPHALT
                s in gravel -> return GRAVEL
                s in dirt -> return DIRT
            }
            when (tracktype?.lowercase()) {
                "grade1" -> return GRAVEL
                "grade2", "grade3" -> return GRAVEL
                "grade4", "grade5" -> return DIRT
            }
            val h = highway?.lowercase()
            return when {
                h == null -> UNKNOWN
                h in roadsUsuallyPaved -> ASPHALT
                h == "track" -> DIRT
                h == "path" || h == "bridleway" -> DIRT
                else -> UNKNOWN
            }
        }
    }
}
