# tools/build-packages – Offline-Pakete am PC bauen

Die App lädt **einmalig** im Einrichtungs-Assistenten alle Pakete von deinem eigenen
Server und arbeitet danach komplett offline. Diese Skripte bauen die Pakete.

## Voraussetzungen

| Werkzeug | Zweck | Installation (Debian/Ubuntu) |
|---|---|---|
| Java 21+ | Planetiler (Vektorkarte → PMTiles) | `apt install openjdk-21-jre` |
| GDAL | Höhendaten mosaikieren, Höhenlinien | `apt install gdal-bin` |
| Python 3 + osmium | POIs und Belag-Daten aus OSM | `pip install osmium` |
| rio-rgbify | Terrain-RGB für die Schummerung | `pip install rio-rgbify` |
| tippecanoe ≥ 2.17 | Höhenlinien → PMTiles | `apt install tippecanoe` oder Quellcode |
| pmtiles-CLI | MBTiles → PMTiles | https://github.com/protomaps/go-pmtiles/releases |
| curl | Downloads | `apt install curl` |

## Bauen

```bash
cd tools/build-packages
./build.sh NO SE DK DE
```

Quellen (alle offen): OpenStreetMap-Extrakte von Geofabrik (ODbL), Copernicus GLO-30 DEM
(AWS Open Data), BRouter-Segmente von brouter.de.

## Ergebnis

```
out/
  manifest.json
  NO/no.pmtiles            map        Vektorkarte (OpenMapTiles-Schema)
  NO/no-dem.pmtiles        hillshade  Terrain-RGB (mapbox-Kodierung)
  NO/no-contours.pmtiles   contours   Layer "contour", Attribut "ele"
  NO/no-poi.sqlite         poi        poi + way_surface (mit R*Tree)
  routing/E5_N60.rd5       routing    BRouter-Segmente (länderübergreifend)
```

`manifest.json` (Format laut Spezifikation, Pfade relativ zur Basis-URL):

```json
{
  "version": 1,
  "countries": {
    "NO": [
      {"type": "map", "file": "NO/no.pmtiles", "bytes": 123, "sha256": "…"},
      {"type": "hillshade", "file": "NO/no-dem.pmtiles", "bytes": 123, "sha256": "…"},
      {"type": "routing", "file": "routing/E5_N60.rd5", "bytes": 123, "sha256": "…"},
      {"type": "poi", "file": "NO/no-poi.sqlite", "bytes": 123, "sha256": "…"}
    ]
  }
}
```

## Bereitstellen

```bash
python3 serve.py out 8080
```

`serve.py` unterstützt HTTP-Range, damit unterbrochene Downloads fortgesetzt werden.
In der App: **Einrichtung → Basis-URL** `http://<IP-des-PCs>:8080/`.
Jeder andere Webserver (nginx, Caddy, …) mit Range-Unterstützung geht auch.

## Ohne Server

Pakete können auch per USB kopiert werden: Inhalt von `out/` nach
`Android/data/de.tetnavigator.app/files/packages/` und eine `installed.json`
(siehe `core/offlinedata/OfflineStorage.kt`) – der Download-Weg ist aber der empfohlene,
weil er Größe und SHA-256 prüft.
