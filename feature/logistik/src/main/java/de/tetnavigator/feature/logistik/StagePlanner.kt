package de.tetnavigator.feature.logistik

data class StageSection(val sectionId: Long, val name: String, val countryCode: String, val lengthM: Double, val ascentM: Double, val reversed: Boolean)

data class DayStage(
    val dayIndex: Int,
    val sections: List<StageSection>,
) {
    val lengthM get() = sections.sumOf { it.lengthM }
    val ascentM get() = sections.sumOf { it.ascentM }
    fun durationMs(avgKmh: Double): Long = (lengthM / 1000.0 / avgKmh * 3_600_000).toLong()
}

/**
 * Etappenplaner: Sektionen zu Tagesetappen gruppieren; Zeit nach eigenem
 * gemessenem Offroad-Schnitt.
 */
object StagePlanner {
    fun group(items: List<Pair<Int, StageSection>>): List<DayStage> =
        items.groupBy({ it.first }, { it.second }).toSortedMap().map { (day, s) -> DayStage(day, s) }

    /** Automatischer Vorschlag: Sektionen der Reihe nach, bis Tageslimit (h) erreicht. */
    fun suggest(sections: List<StageSection>, avgKmh: Double, hoursPerDay: Double): List<DayStage> {
        val out = ArrayList<DayStage>()
        var cur = ArrayList<StageSection>()
        var curMs = 0L
        val limit = (hoursPerDay * 3_600_000).toLong()
        for (s in sections) {
            val ms = (s.lengthM / 1000.0 / avgKmh * 3_600_000).toLong()
            if (cur.isNotEmpty() && curMs + ms > limit) {
                out += DayStage(out.size, cur); cur = ArrayList(); curMs = 0
            }
            cur += s; curMs += ms
        }
        if (cur.isNotEmpty()) out += DayStage(out.size, cur)
        return out
    }
}
