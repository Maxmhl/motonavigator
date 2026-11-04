#!/usr/bin/env python3
"""
POI- und Belag-Paket (SQLite) aus einem OSM-Extrakt bauen.

    python3 build_poi.py norway-latest.osm.pbf out/NO/no-poi.sqlite

Tabellen (siehe core/offlinedata/PoiDatabase.kt):
    poi(id, lat, lon, category, name) + poi_rtree
    way_surface(id, surface, tracktype, highway, coords) + way_rtree

Benötigt: pip install osmium
"""
import sqlite3
import sys

import osmium

ROADS = {
    "primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link",
    "unclassified", "residential", "service", "track", "road", "living_street", "trunk", "trunk_link",
}


def category(tags):
    t = dict(tags)
    tourism = t.get("tourism")
    amenity = t.get("amenity")
    shop = t.get("shop")
    if tourism in ("camp_site", "caravan_site") or t.get("leisure") == "camp_site":
        return "campsite"
    if tourism in ("alpine_hut", "wilderness_hut") or amenity == "shelter" and t.get("shelter_type") in ("basic_hut", "lean_to", None):
        return "hut"
    if amenity in ("drinking_water", "water_point") or t.get("natural") == "spring" or t.get("man_made") == "water_tap":
        return "water"
    if shop in ("supermarket", "convenience", "general"):
        return "supermarket"
    if shop in ("motorcycle", "motorcycle_repair") or t.get("craft") == "motorcycle_repair" or amenity == "motorcycle_repair" or shop == "car_repair":
        return "workshop"
    if amenity == "fuel":
        return "fuel"
    return None


class Handler(osmium.SimpleHandler):
    def __init__(self, db):
        super().__init__()
        self.db = db
        self.pois = []
        self.ways = []
        self.n = 0

    def flush(self):
        if self.pois:
            self.db.executemany("INSERT INTO poi(lat, lon, category, name) VALUES (?,?,?,?)", self.pois)
            self.pois = []
        if self.ways:
            for surface, tracktype, highway, coords, bbox in self.ways:
                cur = self.db.execute("INSERT INTO way_surface(surface, tracktype, highway, coords) VALUES (?,?,?,?)", (surface, tracktype, highway, coords))
                self.db.execute("INSERT INTO way_rtree(id, minLat, maxLat, minLon, maxLon) VALUES (?,?,?,?,?)", (cur.lastrowid,) + bbox)
            self.ways = []

    def add_poi(self, lat, lon, tags):
        c = category(tags)
        if c:
            self.pois.append((lat, lon, c, tags.get("name", "")))
            if len(self.pois) > 5000:
                self.flush()

    def node(self, n):
        if n.tags and n.location.valid():
            self.add_poi(n.location.lat, n.location.lon, n.tags)

    def way(self, w):
        tags = w.tags
        hw = tags.get("highway")
        try:
            pts = [(nd.lat, nd.lon) for nd in w.nodes if nd.location.valid()]
        except osmium.InvalidLocationError:
            return
        if not pts:
            return
        # Flächen-POIs (Campingplatz als Polygon): Schwerpunkt
        if category(tags):
            lat = sum(p[0] for p in pts) / len(pts)
            lon = sum(p[1] for p in pts) / len(pts)
            self.add_poi(lat, lon, tags)
        if hw in ROADS and len(pts) >= 2:
            # Koordinaten auf 6 Nachkommastellen, Punkte ausdünnen (> ~5 m)
            thin = [pts[0]]
            for p in pts[1:]:
                if abs(p[0] - thin[-1][0]) + abs(p[1] - thin[-1][1]) > 0.00005:
                    thin.append(p)
            if thin[-1] != pts[-1]:
                thin.append(pts[-1])
            coords = ";".join("%.6f,%.6f" % p for p in thin)
            lats = [p[0] for p in thin]
            lons = [p[1] for p in thin]
            self.ways.append((tags.get("surface"), tags.get("tracktype"), hw, coords, (min(lats), max(lats), min(lons), max(lons))))
            if len(self.ways) > 5000:
                self.flush()


def main(src, dst):
    db = sqlite3.connect(dst)
    db.executescript(
        """
        DROP TABLE IF EXISTS poi; DROP TABLE IF EXISTS poi_rtree;
        DROP TABLE IF EXISTS way_surface; DROP TABLE IF EXISTS way_rtree;
        CREATE TABLE poi(id INTEGER PRIMARY KEY, lat REAL, lon REAL, category TEXT, name TEXT);
        CREATE TABLE way_surface(id INTEGER PRIMARY KEY, surface TEXT, tracktype TEXT, highway TEXT, coords TEXT);
        CREATE VIRTUAL TABLE way_rtree USING rtree(id, minLat, maxLat, minLon, maxLon);
        CREATE VIRTUAL TABLE poi_rtree USING rtree(id, minLat, maxLat, minLon, maxLon);
        """
    )
    h = Handler(db)
    h.apply_file(src, locations=True, idx="flex_mem")
    h.flush()
    db.execute("INSERT INTO poi_rtree(id, minLat, maxLat, minLon, maxLon) SELECT id, lat, lat, lon, lon FROM poi")
    db.execute("CREATE INDEX poi_cat ON poi(category)")
    db.commit()
    db.execute("VACUUM")
    db.close()


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1], sys.argv[2])
