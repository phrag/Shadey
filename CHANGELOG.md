# Changelog

All notable changes to Shadey are documented here. Releases are published at
https://github.com/phrag/shadey/releases.

## Unreleased

**Routes**
- Walking routes are now actually walking routes. The public OSRM demo server the
  app used only serves car routing no matter which profile the URL asks for, so
  "walking" routes followed arterial roads with driving durations (e.g. 3.9 km in
  9 min) — and scored terribly for shade, since car routes hug wide open roads.
  Routing now uses the FOSSGIS OSRM instance's real pedestrian profile
  (routing.openstreetmap.de), giving footpath-aware routes and honest durations.
- Fixed routes planned in a freshly-opened city showing a bogus "0% shade". The
  shade score was computed once, at plan time — but in a new city the building
  data arrives from the map tiles a few seconds after the camera does, so the
  route was scored against an empty or partial building set and never corrected
  itself. Routes are now re-scored (keeping your selected alternative) whenever
  newly-harvested building data lands.
- The route line is now blue over a white casing so it stands out on the map —
  the previous sun-orange blended into the basemap's orange roads, and the shade
  grey into the shadow overlay. Sunny stretches draw in light sky-blue, shaded
  ones in dark blue, keeping the sun/shade split visible at a glance.

**Performance**
- Release builds are now minified with R8 (code shrinking + resource shrinking),
  making the APK significantly smaller and faster than the debug builds previous
  releases shipped. Signed with the same keystore as before, so it installs over
  any existing install without an uninstall.

## 1.2.0

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
- The system back gesture now plays Android's predictive-back animation
  (opted in via the manifest, as required since Android 13).
- Tap the My-location button to drop a live "you are here" marker — an upright
  ツ smiley face with a translucent cone that swings beneath it to show which
  way you're facing. While you're walking the facing direction comes from your
  GPS travel direction (steadier and more accurate than the compass); when you
  stop, it falls back to the orientation sensors, corrected to true north. The
  marker tracks your position and heading live while the app is open, and the
  map follows you as you move; panning the map yourself stops the follow (the
  marker stays), and tapping My-location again re-centres and resumes it. The
  sensors and location updates only run while the app is foregrounded, so
  there's no background battery drain. If the compass is uncalibrated (the
  usual cause of a wildly-wrong heading), a hint asks you to wave the phone in
  a figure-8.

**Routes**
- New shady-route planner: tap the walking-person button, tap a start and a
  destination, and Shadey scores every walking alternative from OSRM by how
  much of it is in shade right now, picks the shadiest, and draws it on the
  map colour-coded by sun/shade. Cycle through alternatives with "Try
  another route". Gated by the same network setting.
- The route planner can now use your current location as the start point —
  tap "Use my location" instead of tapping a start on the map, then just tap
  your destination. Falls back to a clear message (tap the map instead) if
  the location permission is denied or no fix is available yet.

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
- Fixed the live "you are here" marker's heading jumping around and reading slightly wrong when
  the phone is held upright (the normal way to look at the map while walking) — the sensor axis
  remap was tuned for a phone lying flat, which put the orientation calculation right at gimbal
  lock for an upright phone. Also stopped the marker's position from hopping around while you stand
  still: GPS and network fixes are now weighed against each other (preferring whichever is more
  accurate and recent) instead of rendering whichever provider reported last — network fixes can be
  100+ m off from a concurrent GPS fix — and the shown position is low-pass smoothed so the few
  metres of jitter a stationary phone's GPS produces every second no longer drag the marker and the
  follow-camera back and forth. The cone's tiny magnetometer shimmer is likewise damped with a
  small turn threshold, and the ツ face now stays upright while only the cone rotates, so the face
  is always readable.
- Fixed the marker (and the whole map) still stuttering and jumping while walking with follow-me
  on. Two things compounded: outside a bundled/downloaded city the map re-harvests building
  footprints from the rendered tiles on every frame, and the follow camera re-renders on every GPS
  fix (about once a second) — so the app was re-parsing buildings and recomputing all shade several
  times a second (visible as constant dropped frames and heavy garbage collection in device logs).
  On top of that, the camera chased every fix, so a stationary phone's GPS wander slid the entire
  map around under you. Now the camera only re-centres once you've actually moved a real distance,
  the tile harvest and shade recompute are likewise gated on movement, and the marker itself still
  updates every fix so it stays live — the map just holds still when you do.
- Rebuilt how the live heading is read from the phone's sensors so it's accurate at any angle. The
  previous approach picked a fixed sensor-axis remap based on screen rotation, which sat near a
  mathematical singularity ("gimbal lock") when the phone was held upright to read the map — the
  exact posture you walk with — making the direction wrong and jumpy. The facing is now derived
  directly from the phone's orientation by projecting both the top edge and the back of the device
  onto the ground and combining them, so whether the phone is flat or upright the cone points the
  right way, smoothly, with no singularity. (Travel direction from GPS still takes over while you're
  actually walking; the sensor reading drives the cone whenever you slow or stop.)
- Fixed the map still stuttering while walking with follow-me on, even after the above throttling.
  The previous fix only gated what happened with a tile-building query's *result* — the costly
  native query itself still ran on every single camera move (i.e. on every follow-camera re-centre,
  and even while standing inside Berlin or a downloaded city, where the result was thrown away
  immediately). The map now asks the view model first, and skips the query outright unless it could
  actually change anything — outside a bundled/downloaded city and only after real movement.
- Reworked the "you are here" marker to stop it hopping around, following how Organic Maps handles
  the same problem (two layers: filter the fixes, then animate the dot). On the filter side, a fix
  that lands inside the accuracy circle of the position already shown — and isn't itself markedly
  more accurate — is treated as GPS wander and ignored, so the anchor point doesn't move and the dot
  sits still while you stand; a genuine step beyond that circle, or a much sharper fix, passes
  through. A coarse network fix (often tens of metres off) is also no longer accepted over a good
  GPS one. On the render side, the marker now eases smoothly toward each new fix instead of
  teleporting to it — a once-a-second GPS update no longer looks like a hop, and the easing absorbs
  the small residual jitter while walking. The marker stops requesting frames once it has arrived,
  so a stationary dot costs nothing.
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
