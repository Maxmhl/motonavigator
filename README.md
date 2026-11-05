# TET Navigator

Native Android-App zum Navigieren des **Trans Euro Trail (TET)** mit selbst importierten
GPX-Dateien – im Offroad-Look, handschuhtauglich und **nach dem einmaligen Einrichten
komplett offline**.

> Für den Eigengebrauch. Vor einer Play-Store-Veröffentlichung den TET fragen oder einen
> neutralen Namen wählen. Die App liefert keine TET-Tracks mit und verteilt keine GPX weiter.

## Harte Regeln – wie sie umgesetzt sind

| Regel | Umsetzung |
|---|---|
| Einmal laden, dann offline | Netzwerk nur in `:data:download` (OkHttp). Nach erfolgreichem Download setzt der Worker `offlineLocked = true` (DataStore). Jeder Request läuft durch `OfflineGuardInterceptor`, der bei aktiver Sperre **vor** dem Verbindungsaufbau abbricht. Nur „Einstellungen → Pakete neu laden“ (Lang-Drücken) hebt die Sperre einmalig auf. MapLibre läuft mit `MapLibre.setConnected(false)` und einem Stil ohne eine einzige `http(s)`-URL. |
| Kein HTTP-Client außerhalb `data/download` | Gradle-Task `verifyNoNetworkOutsideDownload` hängt an jedem `preBuild`/`check` und bricht bei `okhttp3`, `java.net.URL`, `HttpURLConnection`, Retrofit, Ktor … außerhalb des Moduls ab. Test `noRequestWhenOfflineLocked` prüft, dass nach `offlineLocked` kein Request rausgeht. |
| Keine Analytics / Crash-Reporting / Online-Tiles / Online-Fonts | Keine solchen Abhängigkeiten (der Check verbietet sie auch in Build-Dateien). Schriften (Saira Stencil One, Barlow Condensed) liegen in `core/designsystem/res/font`, Karten-Glyphen (Noto Sans) in `app/src/main/assets/glyphs`. |
| GPX kommt immer vom Nutzer | Import nur per Dateiauswahl (SAF), „Teilen mit…“/„Öffnen mit…“ oder ZIP. Kein Teilen/Cloud für TET-GPX, `allowBackup=false`. Exporte enthalten nur eigene Fahrten/Notizen/Pins. |
| Nicht im Umfang | Kein SOS, keine Sturzerkennung, keine Notfall-SMS, kein LoRa/Mesh/Gruppen, kein Wetter, kein Login. |
| Handschuh-tauglich | Alle Tasten ≥ 64 dp (`MinTouch`, per Compose-Test geprüft), Bedientasten im unteren Drittel, „Track wechseln“, „Richtung umkehren“, „Navigation beenden“, „Abschnitt sperren“ und „Aufzeichnung beenden“ nur per Lang-Drücken mit Füllbalken. Handschuh-Modus: Wischgesten aus, Zoom über Tasten/Lautstärketasten. |
| OSM-Attribution | „© OpenStreetMap-Mitwirkende“ ist auf dem Fahr-Screen immer eingeblendet. |

## Funktionsumfang

**MVP:** Einrichtungs-Assistent mit einmaligem Download · GPX-Import (Datei, Teilen, ZIP) ·
Track-Bibliothek nach Land → Sektion · Richtung umkehren · Offline-Vektorkarte (PMTiles) mit
Höhenlinien und Schummerung · Höhenprofil · Track folgen (Heading-up, Auto-Zoom, Distanz/Pfeil zur
nächsten Kurve) · Abweich-Alarm mit Hysterese · Aufzeichnung · Handschuh-Modus.

**Phase 2:** Update-Vergleich (geänderte Abschnitte gelb, Fortschritt bleibt) · Alter-Warnung ·
Untergrund-Färbung (OSM `surface`/`tracktype`) · Gefahren-Pins mit Foto · Kurven-Vorschau ·
„Zurück zum Trail“ (BRouter offline) · Tank-Reichweite mit Warnung vor der letzten Tankstelle ·
Tageslicht-Rechner (NOAA) · Camping & Versorgung (Offline-POIs) · Länderstempel ·
Lenker-Fernbedienung (lernbare Tasten) · Akku-Modus · Tag-Modus.

**Phase 3:** Umfahrung gesperrter Abschnitte · Etappenplaner · Reisetagebuch (Fotos + Notizen) ·
Export als GPX/PDF · Trail-Notizen als GPX.

## Projektstruktur

```
app/                  Compose-UI, Navigation, Fahr-Screen, Karte (MapLibre), GNSS-Service
core/common           Geo-Mathematik, Länder, Formatierung (reines Kotlin)
core/designsystem     Farb-Tokens, Typografie, Komponenten (Abschnitt 5)
core/database         Room-Entities/DAOs, R*Tree über Track-Segmente
core/preferences      DataStore inkl. offlineLocked
core/offlinedata      Paket-Ablage, Manifest-Modell, POI-/Belag-Datenbank
data/download         EINZIGER Netzwerkzugriff: Manifest, Range-Resume, SHA-256, Foreground-Worker
feature/import        Streaming-GPX-Parser, ZIP, Länder-Zuordnung, Diff alt/neu, Untergrund-Analyse
feature/navigation    Map-Matching, Fortschritt, Abweich-Alarm, Kurven-Vorschau, BRouter
feature/logistik      Tank-Reichweite, Tageslicht (NOAA), POI-Suche, Etappenplaner
feature/logbuch       Aufzeichnung, Statistik, Länderstempel, Tagebuch, GPX/PDF-Export
feature/bedienung     Tasten-/Remote-Events, Akku-Modus
feature/setup         Einrichtungs-Assistent
feature/settings      Einstellungen, „Pakete neu laden“
tools/build-packages  Skripte für PMTiles/Höhen/POI/Routing-Pakete (PC)
```

## Bauen & Testen

Voraussetzungen: JDK 17+ und Android SDK (compileSdk 36).

```bash
./gradlew assembleDebug                # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest            # Unit- und Robolectric-Tests aller Module
./gradlew verifyNoNetworkOutsideDownload
./gradlew :app:lintDebug

# optional: Live-Tests gegen die öffentlichen Quellen bzw. echte BRouter-Segmente
./gradlew :data:download:testDebugUnitTest -PtetLiveOut=/tmp/tet-live
./gradlew :feature:navigation:testDebugUnitTest -PtetSegmentsDir=/pfad/mit/E10_N60.rd5
```

## Releases

Ein Tag `vX.Y.Z` startet `.github/workflows/release.yml`: Tests, Netzwerk-Regel, Release-Build (R8)
und ein GitHub-Release mit `tet-navigator-vX.Y.Z.apk` (+ SHA-256). Der Release-Text kommt aus
`docs/releases/vX.Y.Z.md`; `v0.*`-Versionen werden als Vorabversion markiert.

Für eine gleichbleibende Signatur (Updates ohne Neuinstallation) diese Repository-Secrets setzen:
`TET_KEYSTORE_BASE64` (Keystore als Base64), `TET_KEYSTORE_PASSWORD`, `TET_KEY_ALIAS`,
`TET_KEY_PASSWORD`. Ohne Secrets wird mit dem Debug-Schlüssel signiert. Lokal genügen die
Umgebungsvariablen `TET_KEYSTORE_FILE`, `TET_KEYSTORE_PASSWORD`, `TET_KEY_ALIAS`, `TET_KEY_PASSWORD`.

## Erste Schritte

1. App starten → Berechtigungen (je ein Erklär-Screen) → Einrichtung → Quelle wählen:
   - **Direkt aus dem Internet (empfohlen, ohne PC):** Länder wählen, Kartendetail (Sparsam = Zoom 12,
     Standard = Zoom 13, Hoch = Zoom 14), Höhendaten an/aus → „Größe berechnen“ → Download.
   - **Eigener Server:** Pakete am PC bauen (siehe [`tools/build-packages/README.md`](tools/build-packages/README.md)),
     Basis-URL eingeben, Länder wählen → Download.
2. Der Download läuft als Foreground-Arbeit (fortsetzbar, nur WLAN optional). Danach ist die
   Offline-Sperre aktiv.
3. TET-GPX importieren: „+ GPX“ oder im Browser „Teilen mit… → TET Navigator“.
4. Land → Sektion → **LOSFAHREN** (oder **RÜCKWÄRTS**).

## Einrichtung ohne PC – Quellen und Verarbeitung auf dem Handy

| Paket | Quelle | Verarbeitung auf dem Gerät |
|---|---|---|
| Karte | Täglicher Protomaps-Planet-Build (`build.protomaps.com`, OSM-Daten) | Ausschnitt per HTTP-Range wie `pmtiles extract`: Landesgrenze (Natural Earth, in der App) + 3 km Rand, Zoom 0 bis 12/13/14; Kacheln per gzip-CRC geprüft; fortsetzbar |
| Höhendaten | Terrain Tiles (Terrarium, AWS Open Data) bis Zoom 10 | in ein lokales PMTiles gepackt; Schummerung in MapLibre, **Höhenlinien werden live aus den Höhendaten berechnet** (20/50/100 m je nach Zoom) |
| Routing | BRouter-Segmente von `brouter.de` | unverändert; zusätzlich Quelle für die **Untergrund-Färbung** (Belag-Tags der Wege entlang des Tracks) |
| POIs | Overpass-API (mehrere öffentliche Server als Ausweich) | zu derselben POI-SQLite verarbeitet wie am PC; schlägt der Abruf fehl, wird die Einrichtung ohne POIs abgeschlossen |

Richtwerte für Norwegen (gemessen): Karte bis Zoom 12 ≈ 480 MB, bis 13 ≈ 920 MB, bis 14 ≈ 1,6 GB;
Höhendaten ≈ 2.350 Kacheln (≈ 140 MB). Auch hier gilt: nur das Download-Modul hat Netzwerkzugriff,
nach Abschluss ist `offlineLocked` aktiv.

## Kern-Algorithmus „Track folgen“

1. Sektion wird in Segmente zerlegt: R*Tree in SQLite (`segment_rtree`) für „nächster Trackpunkt“
   über alle Sektionen, In-Memory-Raster für die aktive Sektion.
2. Jede GPS-Position wird zuerst nur im Fenster entlang des Tracks um die letzte Position
   (−60 m … +max(250 m, 3 × Weg/Takt)) projiziert, Segmente gegen die Fahrtrichtung werden bestraft –
   so springt das Matching in Kehren nicht auf den Gegenast. Erst wenn das nicht passt: globale Suche.
3. Fortschritt = kumulierte Distanz bis zum Projektionspunkt.
4. Abweichung = Abstand Position ↔ Projektion. Alarm erst, wenn > Schwellwert länger als 5 s;
   „Auf Track“ erst wieder bei < Schwellwert × 0,5.
5. Kurven-Vorschau: Richtungsänderung über 30-m-Fenster; > 60° Kurve, > 120° Kehre.

## Lizenzen

Kartendaten © OpenStreetMap-Mitwirkende (ODbL), Kartenkacheln im Direkt-Modus von Protomaps.
Höhendaten: Mapzen/Tilezen Terrain Tiles (AWS Open Data). Landesgrenzen: Natural Earth (gemeinfrei).
Schriften unter SIL OFL (`licenses/`).
Offline-Routing mit [BRouter](https://github.com/abrensch/brouter) (`tet-schotter.brf` abgeleitet von
`moped.brf`). Karten-Rendering mit MapLibre Native.
