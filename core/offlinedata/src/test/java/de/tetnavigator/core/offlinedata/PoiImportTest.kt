package de.tetnavigator.core.offlinedata

import android.database.sqlite.SQLiteDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Overpass-JSON (Format laut Overpass-API „out center tags“) → POI-SQLite. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PoiImportTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun buildsPoiDatabaseFromOverpassResponse() {
        val json = File(tmp.root, "op.json").apply {
            writeText(
                """
                {"version":0.6,"generator":"Overpass API","osm3s":{"timestamp_osm_base":"2026-11-05T06:00:00Z"},
                 "elements":[
                  {"type":"node","id":1,"lat":62.5745,"lon":11.3810,"tags":{"amenity":"fuel","name":"Circle K Røros"}},
                  {"type":"way","id":2,"center":{"lat":62.60,"lon":11.39},"tags":{"tourism":"camp_site","name":"Teltplass"}},
                  {"type":"node","id":3,"lat":62.61,"lon":11.37,"tags":{"amenity":"drinking_water"}},
                  {"type":"node","id":4,"lat":62.62,"lon":11.36,"tags":{"amenity":"shelter","shelter_type":"picnic_shelter"}},
                  {"type":"node","id":5,"lat":62.63,"lon":11.35,"tags":{"shop":"motorcycle","brand":"MC Røros"}},
                  {"type":"relation","id":6,"center":{"lat":62.64,"lon":11.34},"tags":{"shop":"supermarket","name":"Coop"}},
                  {"type":"node","id":7,"lat":62.65,"lon":11.33}
                 ]}
                """.trimIndent(),
            )
        }
        val target = File(tmp.root, "no-poi.sqlite")
        val n = PoiDbBuilder.fromOverpassJson(json, target)
        assertThat(n).isEqualTo(5) // Picknick-Unterstand und Knoten ohne Tags ignoriert
        val db = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY)
        val rows = db.rawQuery("SELECT category, name FROM poi ORDER BY lat", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }
        db.close()
        assertThat(rows).containsExactly(
            "fuel" to "Circle K Røros", "campsite" to "Teltplass", "water" to "", "workshop" to "MC Røros", "supermarket" to "Coop",
        ).inOrder()
    }

    @Test fun queryCoversAllCategories() {
        val q = PoiTags.overpassQuery("no")
        assertThat(q).contains("\"ISO3166-1\"=\"NO\"")
        for (k in listOf("fuel", "camp_site", "alpine_hut", "drinking_water", "supermarket", "motorcycle")) assertThat(q).contains(k)
        assertThat(q).contains("out center tags")
    }
}
