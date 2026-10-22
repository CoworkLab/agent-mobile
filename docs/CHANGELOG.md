# Changelog

All notable changes. Format loosely follows Keep a Changelog; versions follow semantic
versioning.

## [Unreleased]

### Added
- Repository bootstrap: `AGENTS.md`, `README.md`, `docs/` skeleton, `.gitignore`,
  `.env.example`.
- Research pass recorded in `docs/RESEARCH.md`, including a live check that
  `gemini-2.5-flash` is retired for new API keys and that vision works on
  `gemini-flash-latest` and `gemini-3.5-flash`.
- Decision log in `docs/DECISIONS.md`.
- Gradle project: `app` module, version catalog, wrapper 8.14.6, AGP 8.13.2,
  Kotlin 2.2.21, `compileSdk` 36 / `minSdk` 30 / `targetSdk` 35.
- `Logger`: in-memory ring buffer, level filter, rotating file sink, copyable export.
- `SecretMasker`: strips Google/GitHub/OpenAI-style keys and bearer tokens from every log
  line before it is stored, exported, or shown.
- `CrashHandler`: writes the last crash trace to disk and surfaces it in the Log Viewer on
  the next launch.
- Compose Log Viewer: level filter chips, copy-all, clear, crash card, empty state.
- `ci.yml`: lint, unit tests, debug + release assemble on every push and pull request,
  with test reports and the debug APK uploaded as artifacts.
- Tests: 11 JVM unit tests, 3 instrumented Compose UI tests.
- Verified locally: `assembleDebug`, `assembleRelease`, `testDebugUnitTest`, `lintDebug`
  all pass.
