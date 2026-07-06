# Notes for Claude

## Release process

Every release follows these steps, in order:

1. Bump `versionCode`/`versionName` in `app/build.gradle.kts` and retitle the
   CHANGELOG's "Unreleased" section to the new version (the tag workflow uses
   that section as the release notes). Push and wait for CI green.
2. Tag the release. Pushing tags from a sandboxed session is usually blocked
   (the git proxy 403s non-branch refs), so ask the user to publish the
   release on GitHub with a new tag (plain `1.x.y`, no `v` prefix — matches
   existing tags) targeting the release commit. CI then attaches
   `shadey-<version>.apk` and fills the body from the CHANGELOG.
3. Verify: tag-triggered CI run green, APK asset attached, notes correct.
4. **After every release**: merge the development branch into `main`, push
   `main`, and start a new `claude/dev-<next-version>` branch from `main` for
   further development. Never keep stacking post-release work on the released
   branch.

## Dev builds

- Every CI run triggered by a push to a `claude/**` branch or `main` (see
  `.github/workflows/android.yml`, "Publish dev build" step) rebuilds the debug
  APK and republishes it to a single standing GitHub Release tagged
  `dev-build`: https://github.com/phrag/shadey/releases/tag/dev-build
  That release is always overwritten with the latest build — no need to dig
  through per-run workflow artifacts. After confirming CI is green for a
  change, point the user at that link to install the latest dev build.
- The dev-build release carries two assets: `shadey-dev.apk` (debug — the
  default for testing, readable logcat) and `shadey-dev-minified.apk` (the R8
  release build, for smoke-testing the minification config before a release).
  Tagged releases ship the minified build; its `mapping.txt` is uploaded as an
  `r8-mapping` artifact on each run for deobfuscating crash traces.
