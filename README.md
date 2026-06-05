# ☀️ Shadey

**Find the sun.** Shadey shows whether a spot — a café terrace, a park bench, a
canal bank — is in **sunlight or shade** right now (or at any time of day),
computed from real **building shadows**. It's an English, Berlin-first,
fully-private take on [jveuxdusoleil.fr](https://jveuxdusoleil.fr/).

- 🔒 **Private & offline by default.** Berlin's building data and the sun/shade
  maths run **entirely on your device**. No accounts, no tracking, no servers.
- 🧮 **Real shadows.** Sun position from the NOAA solar equations + 3D building
  geometry from OpenStreetMap → an actual ray-cast to the sun.
- 🗺️ **Smooth 3D map.** MapLibre GL with extruded buildings and an animated
  ground-shadow overlay you can scrub through the day.
- 📍 **Add spots easily.** Tap anywhere to check a point; save your own spots.
- 🌍 **Travels with you.** Outside Berlin, *opt in* to fetch building data from
  OpenStreetMap on demand (cached locally). Berlin itself never touches the network.
- 🆓 **100% FOSS.** Kotlin, Jetpack Compose, MapLibre, OpenStreetMap. MIT-licensed code.

> **Status:** the on-device engine is complete and unit-tested (22 tests). The
> Android UI is implemented and builds via CI (this dev container has no Android
> SDK, so the APK is produced by GitHub Actions / your machine — see below).

## How it works

```
        sun azimuth + elevation (NOAA, pure maths)
                       │
   building footprints + heights (OpenStreetMap)
                       │
                       ▼
   ShadowEngine: cast a ray from the point toward the sun.
   If a building's prism blocks it (the ray is below the
   roof where it first crosses the footprint) → SHADE.
```

The heavy lifting lives in a **pure-Kotlin `:core` module** with no Android
dependencies, so it's fast, portable, and testable on any JVM.

## Project layout

| Module / dir | What |
|---|---|
| `core/` | Pure-Kotlin engine: solar position, geometry, `ShadowEngine`, `SpotRanker`, GeoJSON parsing. Unit-tested. |
| `app/` | Android app: Jetpack Compose UI + MapLibre map, offline/roaming data sources, local saved spots. |
| `app/src/main/assets/data/` | Bundled `spots.json` (curated Berlin spots) + building GeoJSON. |
| `data-pipeline/` | Python scripts to generate the bundled OSM data (run locally). |
| `.github/workflows/` | CI that runs the tests and builds the APK. |

## Build & run

Requires JDK 17+ and the Android SDK (`ANDROID_HOME` set, or use Android Studio).

```bash
# 1. Generate the bundled sample buildings (no network needed)
python3 data-pipeline/make_sample.py

# 2. (Optional) Replace with real Berlin OSM data — needs network
python3 -m pip install -r data-pipeline/requirements.txt
python3 data-pipeline/fetch_osm.py

# 3. Build the debug APK
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Open the project in Android Studio and hit ▶ to run on a device/emulator.

### Run just the engine tests (no Android SDK required)

```bash
SHADEY_CORE_ONLY=true ./gradlew :core:test
```

The `SHADEY_CORE_ONLY` flag excludes the Android module so the pure-Kotlin core
compiles and tests with only a JDK + Maven Central.

### CI

`.github/workflows/android.yml` installs the Android SDK, runs the core tests,
builds the debug APK and uploads it as a build artifact on every push/PR.

## Privacy model

| Where you are | Network used? | Data source |
|---|---|---|
| Berlin (bundled region) | **Never** | Building data baked into the APK |
| Elsewhere, roaming **off** | No | Shows "outside Berlin" |
| Elsewhere, roaming **on** (opt-in) | Yes, on demand | OpenStreetMap Overpass, cached locally |

The only Android permission requested is `INTERNET`, used solely for the opt-in
roaming fetch. There is **no location permission** — Shadey never reads your GPS.

## Adding spots & cities

- **In-app:** tap the map → "Save" to store a spot locally.
- **Curated list:** edit `app/src/main/assets/data/spots.json`.
- **New city:** run `fetch_osm.py --bbox …` for the area and register it in
  `BuildingRepository`. See `data-pipeline/README.md`.

## Roadmap ideas

- Bundled street/water context tiles for a richer offline basemap
- Downloadable region packs (keep everything offline beyond Berlin)
- Tree canopy & terrain shading
- "Sunny near me" using on-device location (opt-in)

## License & attribution

- Code: **MIT** (see `LICENSE`).
- Map & building data: **© OpenStreetMap contributors, ODbL**
  (https://www.openstreetmap.org/copyright).
- Maps rendered with **MapLibre GL** (BSD-2-Clause).
