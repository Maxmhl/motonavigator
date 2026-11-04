package de.tetnavigator.core.common

/**
 * Länder des Trans Euro Trail inkl. grober Bounding-Box für die automatische
 * Zuordnung (Dateiname → Metadaten → Koordinaten).
 */
data class Country(
    val code: String,
    val nameDe: String,
    val bbox: BoundingBox,
    /** Schlüsselwörter, die in GPX-Dateinamen / -Metadaten vorkommen. */
    val keywords: List<String>,
)

object Countries {
    val all: List<Country> = listOf(
        c("NO", "Norwegen", 57.9, 4.5, 71.2, 31.2, "norway", "norwegen", "norge", "nor"),
        c("SE", "Schweden", 55.3, 10.9, 69.1, 24.2, "sweden", "schweden", "sverige", "swe"),
        c("FI", "Finnland", 59.7, 20.5, 70.1, 31.6, "finland", "finnland", "suomi", "fin"),
        c("DK", "Dänemark", 54.5, 8.0, 57.8, 15.2, "denmark", "dänemark", "daenemark", "danmark", "dnk"),
        c("DE", "Deutschland", 47.2, 5.8, 55.1, 15.1, "germany", "deutschland", "ger", "deu"),
        c("NL", "Niederlande", 50.7, 3.3, 53.6, 7.3, "netherlands", "niederlande", "nederland", "holland", "nld"),
        c("BE", "Belgien", 49.5, 2.5, 51.6, 6.4, "belgium", "belgien", "belgique", "bel"),
        c("LU", "Luxemburg", 49.4, 5.7, 50.2, 6.6, "luxembourg", "luxemburg", "lux"),
        c("FR", "Frankreich", 42.3, -4.9, 51.1, 8.3, "france", "frankreich", "fra"),
        c("ES", "Spanien", 35.9, -9.4, 43.9, 3.4, "spain", "spanien", "espana", "españa", "esp"),
        c("PT", "Portugal", 36.9, -9.6, 42.2, -6.1, "portugal", "prt"),
        c("IT", "Italien", 36.6, 6.6, 47.1, 18.6, "italy", "italien", "italia", "ita"),
        c("CH", "Schweiz", 45.8, 5.9, 47.9, 10.6, "switzerland", "schweiz", "suisse", "che"),
        c("AT", "Österreich", 46.3, 9.5, 49.1, 17.2, "austria", "österreich", "oesterreich", "aut"),
        c("CZ", "Tschechien", 48.5, 12.0, 51.1, 18.9, "czech", "tschechien", "czechia", "cze"),
        c("SK", "Slowakei", 47.7, 16.8, 49.7, 22.6, "slovakia", "slowakei", "svk"),
        c("PL", "Polen", 49.0, 14.1, 54.9, 24.2, "poland", "polen", "polska", "pol"),
        c("HU", "Ungarn", 45.7, 16.1, 48.6, 22.9, "hungary", "ungarn", "hun"),
        c("SI", "Slowenien", 45.4, 13.3, 46.9, 16.6, "slovenia", "slowenien", "svn"),
        c("HR", "Kroatien", 42.4, 13.4, 46.6, 19.5, "croatia", "kroatien", "hrv"),
        c("BA", "Bosnien und Herzegowina", 42.5, 15.7, 45.3, 19.7, "bosnia", "bosnien", "bih"),
        c("RS", "Serbien", 42.2, 18.8, 46.2, 23.0, "serbia", "serbien", "srb"),
        c("ME", "Montenegro", 41.8, 18.4, 43.6, 20.4, "montenegro", "mne"),
        c("AL", "Albanien", 39.6, 19.2, 42.7, 21.1, "albania", "albanien", "alb"),
        c("MK", "Nordmazedonien", 40.8, 20.4, 42.4, 23.1, "macedonia", "mazedonien", "mkd"),
        c("GR", "Griechenland", 34.8, 19.3, 41.8, 28.3, "greece", "griechenland", "grc"),
        c("BG", "Bulgarien", 41.2, 22.3, 44.3, 28.7, "bulgaria", "bulgarien", "bgr"),
        c("RO", "Rumänien", 43.6, 20.2, 48.3, 29.8, "romania", "rumänien", "rumaenien", "rou"),
        c("TR", "Türkei", 35.8, 25.6, 42.2, 44.9, "turkey", "türkei", "tuerkei", "turkiye", "tur"),
        c("LT", "Litauen", 53.9, 20.9, 56.5, 26.9, "lithuania", "litauen", "ltu"),
        c("LV", "Lettland", 55.6, 20.9, 58.1, 28.3, "latvia", "lettland", "lva"),
        c("EE", "Estland", 57.5, 21.7, 59.8, 28.3, "estonia", "estland", "est"),
        c("GB", "Großbritannien", 49.8, -8.7, 60.9, 1.8, "uk", "united kingdom", "britain", "england", "scotland", "wales", "gbr"),
        c("IE", "Irland", 51.4, -10.7, 55.4, -5.9, "ireland", "irland", "irl"),
        c("IS", "Island", 63.2, -24.6, 66.6, -13.4, "iceland", "island", "isl"),
        c("UA", "Ukraine", 44.3, 22.1, 52.4, 40.3, "ukraine", "ukr"),
        c("MD", "Moldau", 45.4, 26.6, 48.5, 30.2, "moldova", "moldau", "mda"),
        c("XK", "Kosovo", 41.8, 20.0, 43.3, 21.8, "kosovo", "xkx"),
    )

    private val byCode = all.associateBy { it.code }

    fun byCode(code: String): Country? = byCode[code.uppercase()]
    fun name(code: String): String = byCode(code)?.nameDe ?: code.uppercase()

    /**
     * Land aus freiem Text (Dateiname, GPX-<name>, <desc>). Längere Schlüsselwörter
     * gewinnen, 3-Buchstaben-Codes nur als ganzes Wort.
     */
    fun detectFromText(text: String): Country? {
        val t = text.lowercase().replace('_', ' ').replace('-', ' ').replace('.', ' ')
        val words = t.split(Regex("""[^\p{L}]+""")).filter { it.isNotBlank() }.toSet()
        var best: Pair<Country, Int>? = null
        for (country in all) for (k in country.keywords) {
            val hit = if (k.length <= 3) k in words else t.contains(k)
            if (hit && (best == null || k.length > best.second)) best = country to k.length
        }
        return best?.first
    }

    /**
     * Land aus Koordinaten: zählt, in wessen Bounding-Box die meisten Punkte liegen;
     * bei Überlappung gewinnt die kleinere Box (genauer).
     */
    fun detectFromPoints(points: Sequence<LatLon>): Country? {
        val counts = HashMap<String, Int>()
        for (p in points) {
            val hit = all.filter { it.bbox.contains(p.lat, p.lon) }
                .minByOrNull { (it.bbox.maxLat - it.bbox.minLat) * (it.bbox.maxLon - it.bbox.minLon) }
            if (hit != null) counts[hit.code] = (counts[hit.code] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key?.let(::byCode)
    }

    private fun c(code: String, name: String, minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, vararg kw: String) =
        Country(code, name, BoundingBox(minLat, minLon, maxLat, maxLon), kw.toList())
}
