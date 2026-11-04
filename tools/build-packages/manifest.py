#!/usr/bin/env python3
"""
manifest.json aus dem Ausgabeordner erzeugen (Dateiliste, Größe, SHA-256).

    python3 manifest.py out/

Ordnerstruktur:
    out/NO/no.pmtiles            -> type map
    out/NO/no-dem.pmtiles        -> type hillshade
    out/NO/no-contours.pmtiles   -> type contours
    out/NO/no-poi.sqlite         -> type poi
    out/routing/E5_N60.rd5       -> type routing (Zuordnung über routing/<CC>.txt)
"""
import hashlib
import json
import os
import sys


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def classify(name):
    n = name.lower()
    if n.endswith(".rd5"):
        return "routing"
    if n.endswith("-dem.pmtiles") or n.endswith("-hillshade.pmtiles"):
        return "hillshade"
    if n.endswith("-contours.pmtiles"):
        return "contours"
    if n.endswith(".sqlite"):
        return "poi"
    if n.endswith(".pmtiles"):
        return "map"
    return None


def build(out):
    countries = {}
    routing_dir = os.path.join(out, "routing")
    for cc in sorted(os.listdir(out)):
        d = os.path.join(out, cc)
        if not os.path.isdir(d) or cc == "routing" or len(cc) != 2:
            continue
        files = []
        for name in sorted(os.listdir(d)):
            t = classify(name)
            if t is None or t == "routing":
                continue
            p = os.path.join(d, name)
            files.append({"type": t, "file": "%s/%s" % (cc, name), "bytes": os.path.getsize(p), "sha256": sha256(p)})
        tiles_txt = os.path.join(routing_dir, cc + ".txt")
        if os.path.exists(tiles_txt):
            for tile in open(tiles_txt).read().split():
                p = os.path.join(routing_dir, tile + ".rd5")
                if os.path.exists(p):
                    files.append({"type": "routing", "file": "routing/%s.rd5" % tile, "bytes": os.path.getsize(p), "sha256": sha256(p)})
        countries[cc] = files
    return {"version": 1, "countries": countries}


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "out"
    m = build(out)
    with open(os.path.join(out, "manifest.json"), "w") as f:
        json.dump(m, f, indent=2, ensure_ascii=False)
    print("manifest.json: %d Länder, %d Dateien" % (len(m["countries"]), sum(len(v) for v in m["countries"].values())))
