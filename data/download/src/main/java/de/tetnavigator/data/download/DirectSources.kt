package de.tetnavigator.data.download

/**
 * Öffentliche Quellen für die Einrichtung direkt auf dem Handy (ohne PC/Server).
 * Werden ausschließlich vom Download-Modul und nur im Einrichtungs-Assistenten benutzt.
 */
object DirectSources {
    /** Liste der täglichen Protomaps-Planet-Builds (PMTiles, OSM-Daten, ODbL). */
    const val PROTOMAPS_BUILDS = "https://build-metadata.protomaps.dev/builds.json"
    const val PROTOMAPS_BASE = "https://build.protomaps.com/"

    /** Terrarium-Höhenkacheln (Mapzen/Tilezen, AWS Open Data). */
    fun terrarium(z: Int, x: Int, y: Int) = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/$z/$x/$y.png"

    /** BRouter-Routing-Segmente (5°×5°). */
    fun rd5(name: String) = "https://brouter.de/brouter/segments4/$name.rd5"

    /** Overpass-API-Server (öffentlich), werden der Reihe nach versucht. */
    val OVERPASS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )

    const val USER_AGENT = "TET-Navigator/0.1 (Android; Offline-Navigation; einmaliger Download)"

    /** Namen der BRouter-Kacheln, die ein Land (Maske) berühren. */
    fun rd5Tiles(intersects: (Double, Double, Double, Double) -> Boolean): List<String> {
        val out = ArrayList<String>()
        var lon = -180
        while (lon < 180) {
            var lat = -90
            while (lat < 90) {
                if (intersects(lon.toDouble(), lat.toDouble(), lon + 5.0, lat + 5.0)) {
                    out += (if (lon >= 0) "E$lon" else "W${-lon}") + "_" + (if (lat >= 0) "N$lat" else "S${-lat}")
                }
                lat += 5
            }
            lon += 5
        }
        return out
    }
}
