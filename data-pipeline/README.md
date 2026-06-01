# Shadey data pipeline

Shadey is **offline-first**: the bundled region (Berlin) ships inside the APK and
the app makes **no network calls** for it. These scripts produce that bundled data.
They run on your machine, not on any server.

## Files the app reads (in `app/src/main/assets/data/`)

| File | Produced by | Committed? |
|------|-------------|------------|
| `spots.json` | hand-curated (edit directly) | yes |
| `sample_buildings.geojson` | `make_sample.py` (synthetic) | yes |
| `berlin_buildings.geojson` | `fetch_osm.py` (real OSM) | no (git-ignored, large) |

The app loads `berlin_buildings.geojson` if present, otherwise falls back to the
synthetic `sample_buildings.geojson` so it always runs.

## 1. Synthetic sample (no network)

```bash
python3 make_sample.py
```

Generates a plausible grid of Berlin-style blocks around Boxhagener Platz so the
sun/shade engine and 3D map have something to work with out of the box.

## 2. Real Berlin buildings from OpenStreetMap

Needs network access to an Overpass endpoint.

```bash
python3 -m pip install -r requirements.txt
python3 fetch_osm.py                          # default central-Berlin bbox
python3 fetch_osm.py --bbox 52.49 13.38 52.54 13.46   # custom S W N E
```

Heights come from OSM `height` / `building:levels` tags (levels × 3.2 m, default
9 m when untagged). Output is GeoJSON in `lng,lat` order with a `height` property.

Rebuild the APK afterwards to bundle the new data.

## Adding another city

1. Pick a bounding box and run `fetch_osm.py --bbox … --out app/src/main/assets/data/<city>_buildings.geojson`.
2. Add the file to `BuildingRepository`'s bundled-region list and add curated `spots.json` entries.
3. Outside any bundled region the app can fetch from OSM on demand (opt-in in Settings).

## Licensing

Building/map data is © OpenStreetMap contributors, licensed **ODbL**
(https://www.openstreetmap.org/copyright). Keep that attribution with any
distributed data.
