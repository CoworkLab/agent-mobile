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
- `AccessibilityService` with a conservative event config, capability reporting and
  active-window access for later perception and action work.
- `NotificationListenerService` for reading notifications as untrusted input.
- `AgentForegroundService` hosting the agent loop with a persistent notification that
  always carries a STOP action.
- `AgentController` state machine with an injectable step runner and cooperative
  cancellation, so STOP takes effect within one step.
- Draggable always-visible STOP overlay bubble.
- Onboarding screen with live permission checks, settings deep links and restricted-settings
  guidance for sideloaded installs.
- Task / Setup / Logs navigation.
- `e2e-emulator.yml` running instrumented tests on API 34 and API 30 with the service and
  permissions enabled over `adb`.
- 24 JVM unit tests, 6 instrumented UI tests.

### Fixed
- Push protection rejected the M1 commit because a live API key had been used as a test
  fixture. The fixture is now synthetic, generated at runtime, and the key is not present
  anywhere in git history.
- CI failed at "Set up Android SDK": the setup action's interactive license prompt exits 1
  on this runner image. Replaced with a direct, non-interactive `sdkmanager` call.
