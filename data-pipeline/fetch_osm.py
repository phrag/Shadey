#!/usr/bin/env python3
"""Fetch real building footprints + heights from OpenStreetMap (Overpass) and
write the GeoJSON that Shadey bundles for offline, fully on-device use.

Run this where you HAVE network access (the app itself never needs it for the
bundled region). Output goes to app/src/main/assets/data/berlin_buildings.geojson
which the app prefers over the synthetic sample when present.

    python3 fetch_osm.py                      # central Berlin default bbox
    python3 fetch_osm.py --bbox S W N E        # custom bounding box
    python3 fetch_osm.py --out path.geojson

Data (c) OpenStreetMap contributors, ODbL.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import requests  # pip install requests

OVERPASS_ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
]

# Default: a slice of central/east Berlin (Mitte, Friedrichshain, Kreuzberg, P'Berg).
DEFAULT_BBOX = (52.485, 13.380, 52.545, 13.470)  # south, west, north, east

DEFAULT_HEIGHT_M = 9.0
METERS_PER_LEVEL = 3.2


def overpass_query(bbox):
    s, w, n, e = bbox
    return f"""
    [out:json][timeout:180];
    (
      way["building"]({s},{w},{n},{e});
      relation["building"]["type"="multipolygon"]({s},{w},{n},{e});
    );
    out body geom;
    """


def fetch(bbox):
    query = overpass_query(bbox)
    last_err = None
    for url in OVERPASS_ENDPOINTS:
        try:
            print(f"Querying {url} ...", file=sys.stderr)
            r = requests.post(url, data={"data": query}, timeout=300,
                              headers={"User-Agent": "Shadey/1.0 (+https://github.com/phrag/shadey)"})
            if r.status_code == 200:
                return r.json()
            print(f"  HTTP {r.status_code}", file=sys.stderr)
            last_err = RuntimeError(f"HTTP {r.status_code}")
        except Exception as exc:  # noqa: BLE001
            print(f"  failed: {exc}", file=sys.stderr)
            last_err = exc
        time.sleep(2)
    raise SystemExit(f"All Overpass endpoints failed: {last_err}")


def parse_height(tags):
    for key in ("height", "building:height"):
        v = tags.get(key)
        if v:
            num = "".join(ch for ch in v if (ch.isdigit() or ch == "."))
            try:
                h = float(num)
                if h > 0:
                    return round(h, 1)
            except ValueError:
                pass
    levels = tags.get("building:levels")
    if levels:
        try:
            return round(float(str(levels).split(";")[0]) * METERS_PER_LEVEL, 1)
        except ValueError:
            pass
    return DEFAULT_HEIGHT_M


def ring_from_geometry(geometry):
    ring = [[round(p["lon"], 7), round(p["lat"], 7)] for p in geometry if "lat" in p and "lon" in p]
    return ring if len(ring) >= 4 else None


def to_features(data):
    features = []
    for el in data.get("elements", []):
        tags = el.get("tags", {})
        height = parse_height(tags)
        min_h = 0.0
        if tags.get("min_height"):
            try:
                min_h = float("".join(c for c in tags["min_height"] if c.isdigit() or c == "."))
            except ValueError:
                min_h = 0.0
        props = {"height": height, "min_height": min_h, "osm_id": f"{el['type']}/{el['id']}"}

        if el["type"] == "way" and "geometry" in el:
            ring = ring_from_geometry(el["geometry"])
            if ring:
                features.append({"type": "Feature", "id": props["osm_id"], "properties": props,
                                 "geometry": {"type": "Polygon", "coordinates": [ring]}})
        elif el["type"] == "relation":
            outers = [m for m in el.get("members", []) if m.get("role") == "outer" and "geometry" in m]
            polys = []
            for m in outers:
                ring = ring_from_geometry(m["geometry"])
                if ring:
                    polys.append([ring])
            if polys:
                features.append({"type": "Feature", "id": props["osm_id"], "properties": props,
                                 "geometry": {"type": "MultiPolygon", "coordinates": polys}})
    return features


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--bbox", nargs=4, type=float, metavar=("S", "W", "N", "E"), default=DEFAULT_BBOX)
    default_out = Path(__file__).resolve().parents[1] / "app/src/main/assets/data/berlin_buildings.geojson"
    ap.add_argument("--out", type=Path, default=default_out)
    args = ap.parse_args()

    data = fetch(tuple(args.bbox))
    features = to_features(data)
    if not features:
        raise SystemExit("No buildings returned; check the bbox.")

    fc = {"type": "FeatureCollection",
          "attribution": "(c) OpenStreetMap contributors, ODbL",
          "features": features}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(fc, separators=(",", ":")))
    print(f"Wrote {len(features)} buildings to {args.out}")


if __name__ == "__main__":
    main()
