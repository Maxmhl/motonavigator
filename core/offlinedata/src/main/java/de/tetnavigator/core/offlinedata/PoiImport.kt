package de.tetnavigator.core.offlinedata

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** OSM-Tags → POI-Kategorie (gleiche Regeln wie tools/build-packages/build_poi.py). */
object PoiTags {
    fun category(t: Map<String, String>): PoiCategory? {
        val tourism = t["tourism"]; val amenity = t["amenity"]; val shop = t["shop"]
        return when {
            tourism == "camp_site" || tourism == "caravan_site" || t["leisure"] == "camp_site" -> PoiCategory.CAMPSITE
            tourism == "alpine_hut" || tourism == "wilderness_hut" -> PoiCategory.HUT
            amenity == "shelter" && t["shelter_type"].let { it == null || it == "basic_hut" || it == "lean_to" } -> PoiCategory.HUT
            amenity == "drinking_water" || amenity == "water_point" || t["man_made"] == "water_tap" -> PoiCategory.WATER
            t["natural"] == "spring" && t["drinking_water"] != "no" -> PoiCategory.WATER
            shop == "supermarket" || shop == "convenience" || shop == "general" -> PoiCategory.SUPERMARKET
            shop == "motorcycle" || shop == "motorcycle_repair" || shop == "car_repair" || t["craft"] == "motorcycle_repair" || amenity == "motorcycle_repair" -> PoiCategory.WORKSHOP
            amenity == "fuel" -> PoiCategory.FUEL
            else -> null
        }
    }

    /** Overpass-Abfrage für alle POI-Kategorien eines Landes (ISO 3166-1 alpha-2). */
    fun overpassQuery(countryCode: String): String = """
        [out:json][timeout:600][maxsize:1073741824];
        area["ISO3166-1"="${countryCode.uppercase()}"]["admin_level"="2"]->.a;
        (
          nwr["amenity"="fuel"](area.a);
          nwr["tourism"~"^(camp_site|caravan_site|alpine_hut|wilderness_hut)$"](area.a);
          nwr["amenity"="shelter"]["shelter_type"~"^(basic_hut|lean_to)$"](area.a);
          nwr["amenity"~"^(drinking_water|water_point)$"](area.a);
          nwr["man_made"="water_tap"](area.a);
          node["natural"="spring"]["drinking_water"="yes"](area.a);
          nwr["shop"~"^(supermarket|convenience|general|motorcycle|motorcycle_repair|car_repair)$"](area.a);
          nwr["craft"="motorcycle_repair"](area.a);
        );
        out center tags qt;
    """.trimIndent()
}

/** Baut aus einer Overpass-JSON-Antwort die POI-SQLite (Schema wie build_poi.py, ohne Belag-Tabelle – der Belag kommt im Direkt-Modus aus BRouter). */
object PoiDbBuilder {
    fun fromOverpassJson(json: File, target: File): Int {
        val root = Json.parseToJsonElement(json.readText()).jsonObject
        val elements = root["elements"]?.jsonArray ?: error("Ungültige Overpass-Antwort")
        val tmp = File(target.path + ".tmp").apply { delete() }
        val db = SQLiteDatabase.openOrCreateDatabase(tmp, null)
        var n = 0
        try {
            db.execSQL("CREATE TABLE poi(id INTEGER PRIMARY KEY, lat REAL, lon REAL, category TEXT, name TEXT)")
            db.beginTransaction()
            val st = db.compileStatement("INSERT INTO poi(lat, lon, category, name) VALUES (?,?,?,?)")
            for (el in elements) {
                val o = el.jsonObject
                val tags = (o["tags"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: continue
                val cat = PoiTags.category(tags) ?: continue
                val center = o["center"] as? JsonObject
                val lat = o["lat"]?.jsonPrimitive?.doubleOrNull ?: center?.get("lat")?.jsonPrimitive?.doubleOrNull ?: continue
                val lon = o["lon"]?.jsonPrimitive?.doubleOrNull ?: center?.get("lon")?.jsonPrimitive?.doubleOrNull ?: continue
                st.bindDouble(1, lat); st.bindDouble(2, lon); st.bindString(3, cat.id); st.bindString(4, tags["name"] ?: tags["brand"] ?: "")
                st.executeInsert(); st.clearBindings(); n++
            }
            db.setTransactionSuccessful()
            db.endTransaction()
            db.execSQL("CREATE INDEX poi_cat ON poi(category)")
            db.execSQL("CREATE INDEX poi_lat ON poi(lat)")
        } finally {
            if (db.inTransaction()) db.endTransaction()
            db.close()
        }
        if (target.exists()) target.delete()
        tmp.renameTo(target)
        File(tmp.path + "-journal").delete()
        return n
    }
}
