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

### Added (M8)
- `SensitiveDataFilter`: removes passwords, one-time codes, PINs and card numbers, with a Luhn
  check so order numbers are not mistaken for cards.
- `MemoryStore` with the profile, episodic and procedural layers, filtering on every write.
- `Skill`, `SkillParser`, `SkillLibrary` and `BundledSkills`: markdown skills with front
  matter, an index-only prompt, and a draft status that cannot be loaded.
- Five bundled skills as assets, plus a test that none of them contains a coordinate.
- `LoadSkillTool` and `SkillDraftPolicy`.
- 107 new unit tests (631 total).

### Added (M7)
- `AuditEvent`, the Room table and DAO, and `RoomAuditLog` plus `InMemoryAuditLog`.
- `AuditFormatter` and `AuditPolicy`: plain-language wording, retention, pruning and search,
  all as pure functions.
- `AndroidConfirmationGate` and `PendingConfirmation`: timeout is a denial, the pending call
  is remembered, and the context is the screen the question was asked about.
- `StepEngine.runApproved`, so an approval resumes the exact call that was shown.
- `AuditDetail`, the one place a detail string is masked and truncated.
- 62 new unit tests (524 total).

### Added (M6)
- `LoopBudget` with the four limits, a sliding-window action rate limit, and a plain-language
  reason for every stop.
- `StuckDetector` with an escalating set of hints, replan after three stuck events, and a
  hand-off to the user after five.
- `HistoryCompactor` keeping at most two screenshots and one-line summaries for older steps.
- `AgentPlan` parsing, `PromptBuilder`, and `DecisionParser`.
- `StepEngine` running decide, guard, act and verify, with the safety refusals in one place.
- `AgentOrchestrator`, the real loop, including the up-front plan.
- `assets/prompts/system.md`, the shipped system prompt.
- 117 new unit tests (462 total).

### Added (M5)
- `LlmProvider` and `OpenAiCompatibleProvider` for the OpenAI `/chat/completions` shape,
  covering Gemini, OpenRouter and Groq. Pure request builder, tested JSON.
- `JsonToolCallParser`: fence stripping, prose trimming, trailing-comma repair,
  double-encoding unwrap, and brace counting that respects string literals.
- `LlmErrorClassifier` with `RateLimited`, `Auth`, `BadRequest`, `Server` and `Network`, and
  provider-supplied retry delays.
- `ProviderRouter` with ordered failover, per-provider cooldown, vision-aware image
  stripping, and token counters.
- `KeystoreSecretCipher`: API keys encrypted at rest with a hardware-backed AES-GCM key.
- `ProviderRepository` and the provider presets, with the model name intentionally empty.
- `ConnectionTester` for the "Test connection" button.
- 130 new unit tests (345 total).

### Added (M4)
- 24 tools across touch, text, navigation, info and wait, plus the required `launch_intent`
  and `batch_actions`.
- `ToolRegistry`: per-call timeouts, exception safety, and function schemas for the model.
- `assets/intent_policy.json` and `IntentValidator`: default-deny intent policy, `upi:` and
  `file:` blocked, resolved-package denylist check, and an audit row for every call.
- `BatchValidator` and `layoutHash`: whole-batch pre-validation, live re-guarding, target
  identity checks, and a layout hash that ignores typed text.
- `RiskClassifier` with English, Hindi and Hinglish keywords, `PackageDenylist`, and
  `InjectionDetector`.
- 78 new unit tests (215 total) and instrumented tests over the real assets and actuator.

### Added (M10)
- The testbed app (`testbed/`, `com.phoneagent.testbed`): chat, form, custom-drawn editor,
  `FLAG_SECURE` screen, fake bank, popup, permission demo, keyboard, slow load, stuck button,
  injection page, Hindi field and long list. Debug-only, no INTERNET permission.
- Automated tests for all 23 required scenarios, run against the shipped assets.
- `ScenarioHarness` and `ScenarioTools`: the real orchestrator and the real tools, driven by a
  scripted model, screen and actuator on the JVM.
- Record/replay fixtures (`RecordedRun`, `ReplayFixtureTest`) that re-run a real trajectory
  with no model and no network, plus the first fixture `open-settings-toggle.json`.
- `TestbedInstrumentedTest`: the device-level scenarios (custom-drawn canvas, secure window,
  injection page, Devanagari, a dead button, a deep link that arrives pre-filled).
- `e2e-emulator.yml` now installs `:testbed:installDebug`.

### Fixed (M10)
- `WaitForTool` could spin forever when a sleeper returned without advancing the clock; its
  poll loop is now bounded by a count as well as the clock.
- The self-test ran optional checks before essential ones, so an essential failure could be
  hidden; essentials now run first.
- Injected on-screen text was adopted as the user's goal instead of being treated as data.
- `ScenarioHarness` defaulted to an empty screen, which made every step return early and the
  tests assert nothing; it now starts from a readable screen.
- `ScriptedToolContext.refreshScreen()` bypassed the capture hook, so a tool that re-read the
  screen never saw a swapped screen and a class of test could not fail.

### Added (M3)
- Perception: `ScreenSnapshot`, `UiNode`, `Bounds`, `TreeShaper`, `SnapshotSerializer`,
  `MarkRenderer`, `AndroidPerceptionSource`.
- Set-of-marks screenshots downscaled to 1080 and encoded at JPEG 70.
- Screen hashing for stuck detection, and an image hash for screenshots.
- Failure classification for secure screens, blank captures, rate limits and missing windows.
- 32 new unit tests (56 total) and instrumented tests that emit the marked screenshot and
  the snapshot JSON as workflow artifacts.

### Fixed
- Push protection rejected the M1 commit because a live API key had been used as a test
  fixture. The fixture is now synthetic, generated at runtime, and the key is not present
  anywhere in git history.
- CI failed at "Set up Android SDK": the setup action's interactive license prompt exits 1
  on this runner image. Replaced with a direct, non-interactive `sdkmanager` call.
