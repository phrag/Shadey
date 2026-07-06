# Notes for Claude

- Every CI run triggered by a push to a `claude/**` branch or `main` (see
  `.github/workflows/android.yml`, "Publish dev build" step) rebuilds the debug
  APK and republishes it to a single standing GitHub Release tagged
  `dev-build`: https://github.com/phrag/shadey/releases/tag/dev-build
  That release is always overwritten with the latest build — no need to dig
  through per-run workflow artifacts. After confirming CI is green for a
  change, point the user at that link to install the latest dev build.
