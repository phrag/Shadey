#!/usr/bin/env python3
"""Fetch real building footprints + heights and street trees from OpenStreetMap
(Overpass) and write the GeoJSON files that Shadey bundles for offline use.

Run this where you HAVE network access (the app itself never needs it for the
bundled region). Outputs:
  app/src/main/assets/data/berlin_buildings.geojson
  app/src/main/assets/data/berlin_trees.geojson

    python3 fetch_osm.py                      # central Berlin default bbox
    python3 fetch_osm.py --bbox S W N E        # custom bounding box
    python3 fetch_osm.py --out-buildings path.geojson --out-trees path.geojson

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
DEFAULT_CROWN_RADIUS_M = 3.0
DEFAULT_TREE_HEIGHT_M = 12.0


# ---------------------------------------------------------------------------
# Overpass queries
# ---------------------------------------------------------------------------

def building_query(bbox):
    s, w, n, e = bbox
    return f"""
    [out:json][timeout:180];
    (
      way["building"]({s},{w},{n},{e});
      relation["building"]["type"="multipolygon"]({s},{w},{n},{e});
    );
    out body geom;
    """


def tree_query(bbox):
    s, w, n, e = bbox
    return f"""
    [out:json][timeout:180];
    (
      node["natural"="tree"]({s},{w},{n},{e});
    );
    out body;
    """


# ---------------------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------------------

def fetch(query, attempts_per_endpoint=3):
    """Try each endpoint several times with exponential backoff."""
    last_err = None
    for url in OVERPASS_ENDPOINTS:
        for attempt in range(1, attempts_per_endpoint + 1):
            try:
                print(f"Querying {url} (attempt {attempt}/{attempts_per_endpoint}) ...",
                      file=sys.stderr)
                r = requests.post(
                    url, data={"data": query}, timeout=300,
                    headers={"User-Agent": "Shadey/1.0 (+https://github.com/phrag/shadey)"},
                )
                if r.status_code == 200:
                    return r.json()
                print(f"  HTTP {r.status_code}", file=sys.stderr)
                last_err = RuntimeError(f"HTTP {r.status_code}")
                if r.status_code in (400, 403, 429):
                    break
            except Exception as exc:  # noqa: BLE001
                print(f"  failed: {exc}", file=sys.stderr)
                last_err = exc
            time.sleep(2 ** attempt)  # 2s, 4s, 8s
    raise SystemExit(f"All Overpass endpoints failed: {last_err}")


# ---------------------------------------------------------------------------
# Building parsing
# ---------------------------------------------------------------------------

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
    ring = [[round(p["lon"], 7), round(p["lat"], 7)]
            for p in geometry if "lat" in p and "lon" in p]
    return ring if len(ring) >= 4 else None


def to_building_features(data):
    features = []
    for el in data.get("elements", []):
        tags = el.get("tags", {})
        height = parse_height(tags)
        min_h = 0.0
        if tags.get("min_height"):
            try:
                min_h = float("".join(c for c in tags["min_height"]
                                      if c.isdigit() or c == "."))
            except ValueError:
                min_h = 0.0
        props = {"height": height, "min_height": min_h,
                 "osm_id": f"{el['type']}/{el['id']}"}

        if el["type"] == "way" and "geometry" in el:
            ring = ring_from_geometry(el["geometry"])
            if ring:
                features.append({
                    "type": "Feature", "id": props["osm_id"],
                    "properties": props,
                    "geometry": {"type": "Polygon", "coordinates": [ring]},
                })
        elif el["type"] == "relation":
            outers = [m for m in el.get("members", [])
                      if m.get("role") == "outer" and "geometry" in m]
            polys = []
            for m in outers:
                ring = ring_from_geometry(m["geometry"])
                if ring:
                    polys.append([ring])
            if polys:
                features.append({
                    "type": "Feature", "id": props["osm_id"],
                    "properties": props,
                    "geometry": {"type": "MultiPolygon", "coordinates": polys},
                })
    return features


# ---------------------------------------------------------------------------
# Tree parsing
# ---------------------------------------------------------------------------

def parse_crown_radius(tags):
    """Return crown radius in metres from OSM crown_spread or crown_diameter tags."""
    for key in ("crown_spread", "crown_diameter"):
        v = tags.get(key)
        if v:
            try:
                diameter = float("".join(ch for ch in v if (ch.isdigit() or ch == ".")))
                if diameter > 0:
                    return round(diameter / 2.0, 1)
            except ValueError:
                pass
    return DEFAULT_CROWN_RADIUS_M


def parse_tree_height(tags):
    v = tags.get("height")
    if v:
        try:
            h = float("".join(ch for ch in v if (ch.isdigit() or ch == ".")))
            if h > 0:
                return round(h, 1)
        except ValueError:
            pass
    return DEFAULT_TREE_HEIGHT_M


def parse_deciduous(tags):
    """True for deciduous/semi-deciduous; false for evergreen. Defaults to true."""
    cycle = tags.get("leaf_cycle", "").lower()
    if "evergreen" in cycle:
        return False
    return True


def to_tree_features(data):
    features = []
    for el in data.get("elements", []):
        if el.get("type") != "node":
            continue
        lat = el.get("lat")
        lon = el.get("lon")
        if lat is None or lon is None:
            continue
        tags = el.get("tags", {})
        props = {
            "kind": "tree",
            "osm_id": f"node/{el['id']}",
            "height": parse_tree_height(tags),
            "crown_radius": parse_crown_radius(tags),
            "deciduous": str(parse_deciduous(tags)).lower(),
        }
        features.append({
            "type": "Feature",
            "id": props["osm_id"],
            "properties": props,
            "geometry": {"type": "Point", "coordinates": [round(lon, 7), round(lat, 7)]},
        })
    return features


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bbox", nargs=4, type=float, metavar=("S", "W", "N", "E"),
                    default=DEFAULT_BBOX)
    root = Path(__file__).resolve().parents[1] / "app/src/main/assets/data"
    ap.add_argument("--out-buildings", type=Path, default=root / "berlin_buildings.geojson")
    ap.add_argument("--out-trees", type=Path, default=root / "berlin_trees.geojson")
    args = ap.parse_args()
    bbox = tuple(args.bbox)

    print("--- Buildings ---", file=sys.stderr)
    building_data = fetch(building_query(bbox))
    building_features = to_building_features(building_data)
    if not building_features:
        raise SystemExit("No buildings returned; check the bbox.")
    args.out_buildings.parent.mkdir(parents=True, exist_ok=True)
    args.out_buildings.write_text(
        json.dumps({"type": "FeatureCollection",
                    "attribution": "(c) OpenStreetMap contributors, ODbL",
                    "features": building_features},
                   separators=(",", ":")))
    print(f"Wrote {len(building_features)} buildings to {args.out_buildings}")

    print("--- Trees ---", file=sys.stderr)
    tree_data = fetch(tree_query(bbox))
    tree_features = to_tree_features(tree_data)
    if not tree_features:
        print("Warning: no trees returned — berlin_trees.geojson will be empty.",
              file=sys.stderr)
    args.out_trees.parent.mkdir(parents=True, exist_ok=True)
    args.out_trees.write_text(
        json.dumps({"type": "FeatureCollection",
                    "attribution": "(c) OpenStreetMap contributors, ODbL",
                    "features": tree_features},
                   separators=(",", ":")))
    print(f"Wrote {len(tree_features)} trees to {args.out_trees}")


if __name__ == "__main__":
    main()
