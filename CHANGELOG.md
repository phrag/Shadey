# Changelog

All notable changes to Shadey are documented here. Releases are published at
https://github.com/phrag/shadey/releases.

## Unreleased

**Spots**
- Long-press anywhere on the map to drop a pin and save it as a spot — a
  plain tap no longer does this, so panning and tapping spots/the dropped-pin
  card stay unaffected.
- The dropped-pin and selected-spot cards now show the next upcoming sunny
  spell today (e.g. "Sunny 14:30–17:15 today") when the spot is currently
  shaded or dark, not just the next single change.

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

**Performance**
- Building lookups now go through a shared spatial grid index instead of
  rescanning the whole loaded set (tens of thousands of buildings) on every
  query. Route planning, spot ranking, and gathering the in-view buildings for
  shadows previously each walked the full list — once per ~25 m route sample,
  once per spot, and once per recompute — recomputing every building's centroid
  each time. The index buckets buildings once, caches their centroids, and is
  rebuilt only when a new city loads, so those lookups now touch just the
  buildings in nearby cells. This removes the allocation churn that was driving
  heavy garbage collection and dropped frames, most visibly while planning a
  route.

**Fixes**
- Fixed the map jumping to a previously-downloaded city out of nowhere while browsing
  somewhere else entirely (e.g. mid-pan around Berlin suddenly landing in Palermo). The
  app silently restores your last-used downloaded city on launch — and Android can quietly
  recreate the app (and re-run that restore) when it reclaims a backgrounded app's memory,
  so returning to what looked like the same session would teleport the camera to that city.
  The launch-time restore now only re-loads the city's building data and leaves the camera
  where it is; only an explicit city switch (search-and-download, or picking a saved city)
  moves the view.
- Switching to a downloaded city also no longer permanently discards the bundled Berlin
  data from memory — panning back to Berlin after visiting a downloaded city now
  correctly restores the full bundled dataset instead of falling back to a sparser
  tile-harvested approximation for the rest of the session.
- Curated and saved spots from far away (e.g. a different city, or wherever the
  map was last open) no longer show up in the spots panel — they used to be
  ranked last but still listed, so one could outrank everything once you'd
  travelled far enough that nothing nearby existed yet.
- Shade now correctly disappears (instead of showing a stale, misleadingly
  large building count) when panning outside the bundled city or downloaded
  city's region, or far enough since the last successful tile-based building
  load — previously the title pill kept reporting the old building set while
  the map showed none of them and rendered no shade at all.
- The search, settings, cities, and route buttons in the top-right column now
  tint their icons against the surface colour explicitly. Their background
  Surfaces used a partly-transparent colour, which kept Compose from picking a
  contrasting icon colour automatically, leaving the icons unreadable.
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
