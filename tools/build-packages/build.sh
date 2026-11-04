#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# TET Navigator – Offline-Pakete am PC bauen
#
#   ./build.sh NO SE            # Pakete für Norwegen und Schweden
#   python3 serve.py out 8080   # im WLAN bereitstellen (Range-fähig)
#
# Ergebnis (out/):
#   NO/no.pmtiles           Vektorkarte (Planetiler, OpenMapTiles-Schema)
#   NO/no-dem.pmtiles       Höhendaten als Terrain-RGB für Schummerung
#   NO/no-contours.pmtiles  Höhenlinien (Layer "contour", Attribut "ele")
#   NO/no-poi.sqlite        POIs + Belag-Daten (way_surface)
#   routing/E5_N60.rd5 …    BRouter-Segmente
#   manifest.json           Dateiliste mit Größe und SHA-256
#
# Voraussetzungen (siehe README.md): java 21+, gdal, python3 + osmium,
# rio-rgbify, tippecanoe (≥ 2.17, kann .pmtiles schreiben), pmtiles-CLI, curl.
# ---------------------------------------------------------------------------
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$HERE/work}"
OUT="${OUT:-$HERE/out}"
PLANETILER_VERSION="${PLANETILER_VERSION:-0.8.4}"
CONTOUR_INTERVAL="${CONTOUR_INTERVAL:-20}"
DEM_MAXZOOM="${DEM_MAXZOOM:-12}"
mkdir -p "$WORK" "$OUT/routing"

need() { command -v "$1" >/dev/null 2>&1 || { echo "Fehlt: $1 (siehe README.md)"; exit 1; }; }
need java; need curl; need python3; need gdalbuildvrt; need gdal_contour; need rio; need tippecanoe; need pmtiles

PLANETILER="$WORK/planetiler.jar"
if [ ! -f "$PLANETILER" ]; then
  curl -fL -o "$PLANETILER" "https://github.com/onthegomap/planetiler/releases/download/v${PLANETILER_VERSION}/planetiler.jar"
fi

for CC in "$@"; do
  CC="$(echo "$CC" | tr '[:lower:]' '[:upper:]')"
  cc="$(echo "$CC" | tr '[:upper:]' '[:lower:]')"
  GEOFABRIK="$(python3 "$HERE/countries.py" geofabrik "$CC")"
  BBOX="$(python3 "$HERE/countries.py" bbox "$CC")"
  D="$OUT/$CC"; W="$WORK/$CC"
  mkdir -p "$D" "$W"
  echo "=== $CC ($GEOFABRIK) ==="

  # 1. OSM-Extrakt
  PBF="$W/$cc-latest.osm.pbf"
  [ -f "$PBF" ] || curl -fL -o "$PBF" "https://download.geofabrik.de/${GEOFABRIK}-latest.osm.pbf"

  # 2. Vektorkarte (PMTiles direkt aus Planetiler)
  if [ ! -f "$D/$cc.pmtiles" ]; then
    java -Xmx6g -jar "$PLANETILER" --osm-path="$PBF" --output="$D/$cc.pmtiles" \
      --download --download-dir="$WORK/sources" --force --maxzoom=14
  fi

  # 3. Höhendaten: Copernicus GLO-30 (offen, AWS Open Data)
  mkdir -p "$W/dem"
  python3 "$HERE/countries.py" dem-urls "$CC" | while read -r URL; do
    F="$W/dem/$(basename "$URL")"
    [ -f "$F" ] || curl -fsL -o "$F" "$URL" || rm -f "$F"   # Kacheln über Meer existieren nicht
  done
  gdalbuildvrt -q "$W/dem.vrt" "$W"/dem/*.tif
  gdalwarp -q -overwrite -t_srs EPSG:3857 -r bilinear -co COMPRESS=DEFLATE -co TILED=YES "$W/dem.vrt" "$W/dem-3857.tif"

  # 3a. Schummerung: Terrain-RGB ("mapbox"-Kodierung) → PMTiles
  if [ ! -f "$D/$cc-dem.pmtiles" ]; then
    rio rgbify -b -10000 -i 0.1 --min-z 5 --max-z "$DEM_MAXZOOM" -j 4 --format png "$W/dem-3857.tif" "$W/dem.mbtiles"
    pmtiles convert "$W/dem.mbtiles" "$D/$cc-dem.pmtiles"
  fi

  # 3b. Höhenlinien
  if [ ! -f "$D/$cc-contours.pmtiles" ]; then
    gdal_contour -q -a ele -i "$CONTOUR_INTERVAL" -f GeoJSONSeq "$W/dem.vrt" "$W/contours.geojsons"
    tippecanoe -q -f -l contour -Z10 -z14 --drop-densest-as-needed --simplification=4 \
      -o "$D/$cc-contours.pmtiles" "$W/contours.geojsons"
  fi

  # 4. POIs + Belag (surface/tracktype) für Untergrund-Färbung
  [ -f "$D/$cc-poi.sqlite" ] || python3 "$HERE/build_poi.py" "$PBF" "$D/$cc-poi.sqlite"

  # 5. BRouter-Segmente (5°-Kacheln)
  TILES="$(python3 "$HERE/countries.py" rd5 "$CC")"
  echo "$TILES" > "$OUT/routing/$CC.txt"
  for T in $TILES; do
    [ -f "$OUT/routing/$T.rd5" ] || curl -fsL -o "$OUT/routing/$T.rd5" "https://brouter.de/brouter/segments4/$T.rd5" || rm -f "$OUT/routing/$T.rd5"
  done
done

# 6. Manifest
python3 "$HERE/manifest.py" "$OUT"
echo "Fertig. Bereitstellen mit: python3 $HERE/serve.py $OUT 8080"
