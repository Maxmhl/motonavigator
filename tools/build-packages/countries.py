"""Länder-Tabelle für den Paketbau: Geofabrik-Pfad und Bounding-Box (minLon, minLat, maxLon, maxLat)."""

COUNTRIES = {
    "NO": ("europe/norway", (4.5, 57.9, 31.2, 71.2)),
    "SE": ("europe/sweden", (10.9, 55.3, 24.2, 69.1)),
    "FI": ("europe/finland", (20.5, 59.7, 31.6, 70.1)),
    "DK": ("europe/denmark", (8.0, 54.5, 15.2, 57.8)),
    "DE": ("europe/germany", (5.8, 47.2, 15.1, 55.1)),
    "NL": ("europe/netherlands", (3.3, 50.7, 7.3, 53.6)),
    "BE": ("europe/belgium", (2.5, 49.5, 6.4, 51.6)),
    "LU": ("europe/luxembourg", (5.7, 49.4, 6.6, 50.2)),
    "FR": ("europe/france", (-4.9, 42.3, 8.3, 51.1)),
    "ES": ("europe/spain", (-9.4, 35.9, 3.4, 43.9)),
    "PT": ("europe/portugal", (-9.6, 36.9, -6.1, 42.2)),
    "IT": ("europe/italy", (6.6, 36.6, 18.6, 47.1)),
    "CH": ("europe/switzerland", (5.9, 45.8, 10.6, 47.9)),
    "AT": ("europe/austria", (9.5, 46.3, 17.2, 49.1)),
    "CZ": ("europe/czech-republic", (12.0, 48.5, 18.9, 51.1)),
    "SK": ("europe/slovakia", (16.8, 47.7, 22.6, 49.7)),
    "PL": ("europe/poland", (14.1, 49.0, 24.2, 54.9)),
    "HU": ("europe/hungary", (16.1, 45.7, 22.9, 48.6)),
    "SI": ("europe/slovenia", (13.3, 45.4, 16.6, 46.9)),
    "HR": ("europe/croatia", (13.4, 42.4, 19.5, 46.6)),
    "BA": ("europe/bosnia-herzegovina", (15.7, 42.5, 19.7, 45.3)),
    "RS": ("europe/serbia", (18.8, 42.2, 23.0, 46.2)),
    "ME": ("europe/montenegro", (18.4, 41.8, 20.4, 43.6)),
    "AL": ("europe/albania", (19.2, 39.6, 21.1, 42.7)),
    "MK": ("europe/macedonia", (20.4, 40.8, 23.1, 42.4)),
    "GR": ("europe/greece", (19.3, 34.8, 28.3, 41.8)),
    "BG": ("europe/bulgaria", (22.3, 41.2, 28.7, 44.3)),
    "RO": ("europe/romania", (20.2, 43.6, 29.8, 48.3)),
    "TR": ("europe/turkey", (25.6, 35.8, 44.9, 42.2)),
    "LT": ("europe/lithuania", (20.9, 53.9, 26.9, 56.5)),
    "LV": ("europe/latvia", (20.9, 55.6, 28.3, 58.1)),
    "EE": ("europe/estonia", (21.7, 57.5, 28.3, 59.8)),
    "GB": ("europe/great-britain", (-8.7, 49.8, 1.8, 60.9)),
    "IE": ("europe/ireland-and-northern-ireland", (-10.7, 51.4, -5.9, 55.4)),
    "IS": ("europe/iceland", (-24.6, 63.2, -13.4, 66.6)),
    "UA": ("europe/ukraine", (22.1, 44.3, 40.3, 52.4)),
    "MD": ("europe/moldova", (26.6, 45.4, 30.2, 48.5)),
    "XK": ("europe/kosovo", (20.0, 41.8, 21.8, 43.3)),
}


def rd5_tiles(bbox):
    """BRouter-Segmentkacheln (5°×5°) für eine Bounding-Box, z. B. 'E5_N60'."""
    import math
    min_lon, min_lat, max_lon, max_lat = bbox
    out = []
    lon = int(math.floor(min_lon / 5.0)) * 5
    while lon <= max_lon:
        lat = int(math.floor(min_lat / 5.0)) * 5
        while lat <= max_lat:
            ew = ("E%d" % lon) if lon >= 0 else ("W%d" % -lon)
            ns = ("N%d" % lat) if lat >= 0 else ("S%d" % -lat)
            out.append("%s_%s" % (ew, ns))
            lat += 5
        lon += 5
    return out


def dem_tiles(bbox):
    """Copernicus-GLO-30-Kacheln (1°×1°) als (lat, lon)-Paare."""
    import math
    min_lon, min_lat, max_lon, max_lat = bbox
    for lat in range(int(math.floor(min_lat)), int(math.ceil(max_lat))):
        for lon in range(int(math.floor(min_lon)), int(math.ceil(max_lon))):
            yield lat, lon


def dem_url(lat, lon):
    ns = "N" if lat >= 0 else "S"
    ew = "E" if lon >= 0 else "W"
    name = "Copernicus_DSM_COG_10_%s%02d_00_%s%03d_00_DEM" % (ns, abs(lat), ew, abs(lon))
    return "https://copernicus-dem-30m.s3.amazonaws.com/%s/%s.tif" % (name, name)


if __name__ == "__main__":
    import sys
    cc = sys.argv[2] if len(sys.argv) > 2 else "NO"
    if sys.argv[1] == "rd5":
        print(" ".join(rd5_tiles(COUNTRIES[cc][1])))
    elif sys.argv[1] == "geofabrik":
        print(COUNTRIES[cc][0])
    elif sys.argv[1] == "bbox":
        print(",".join(str(v) for v in COUNTRIES[cc][1]))
    elif sys.argv[1] == "dem-urls":
        for la, lo in dem_tiles(COUNTRIES[cc][1]):
            print(dem_url(la, lo))
