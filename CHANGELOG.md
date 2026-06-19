# Changelog

All notable changes to Shadey are documented here. Releases are published at
https://github.com/phrag/shadey/releases.

## Unreleased

**Spots**
- Long-press anywhere on the map to drop a pin and save it as a spot — a
  plain tap no longer does this, so panning and tapping spots/the dropped-pin
  card stay unaffected.

**Interface**
- A small chip in the title pill now shows live cloud cover and UV index for
  the map centre (via Open-Meteo), so a sunny-by-geometry spot that's
  actually overcast doesn't look misleadingly bright. Annotation only — it
  never changes the sun/shade verdict. Gated by the existing "Use network
  for search & downloads" setting.
- A small arrow in the title pill now points at the current sun direction,
  hidden after dark.

**Routes**
- New shady-route planner: tap the walking-person button, tap a start and a
  destination, and Shadey scores every walking alternative from OSRM by how
  much of it is in shade right now, picks the shadiest, and draws it on the
  map colour-coded by sun/shade. Cycle through alternatives with "Try
  another route". Gated by the same network setting.

**Fixes**
- Fixed the route planner not responding to map taps after the first one —
  the start/destination picker listened for taps using a snapshot of the
  app state frozen at map creation, so it never noticed route mode turning
  on.

## 1.1.0

**Stability**
- Fixed out-of-memory crashes when downloading or viewing large cities — the
  Overpass download and GeoJSON parsing now stream end-to-end, so peak memory
  stays flat regardless of city size.
- Eliminated the continuous GC churn and stutter during rapid panning; shadow
  frames are only precomputed once the view settles.
- City downloads show live progress and can be cancelled mid-download.

**Spots**
- Spots are now ranked by proximity to the current map view first, then by
  sun/shade quality — a sunnier spot clear across town can no longer outrank
  the nearby ones you're actually looking at.

**Interface**
- The status pill shows "Computing shade…" with a spinner while shadows
  recompute, so a slow recompute no longer looks frozen.
- Opt-in update checks: Shadey can check GitHub for new releases and show a
  banner when one is available. Off until you choose; toggle in Settings.

## 1.0.1

**Shadow rendering**
- Real-time ground shadow polygons from OpenStreetMap building heights and a
  NOAA solar position model.
- Shadows update instantly when scrubbing the time slider (precomputed day
  frames).
- Per-building shadow cache persists across pans — returning to a seen area is
  instant.
- Buildings just off-screen included so shadows don't disappear at the edges.

**Map**
- Flat MapLibre GL map with OpenFreeMap vector tiles.
- Smart building deduplication across tile boundaries — no more patchy shadow
  gaps.

**Worldwide city support**
- Search any city and download its buildings once — works fully offline after
  that.
- Streaming download prevents out-of-memory crashes on large cities.
- Berlin bundled; app prompts you to pick a city on first launch.

**Place search**
- Search bar with live results biased toward your current map view.
- Results show place name and location context so similarly-named places are
  easy to tell apart.

**Spots**
- Curated Berlin spots ranked by sun/shade at the selected time.
- Shows when each spot next flips between sun and shade.
- Add your own spots at the current map centre.
