# Progress

Updated: 2026-10-22
Session: M10 (testbed, the 23 required scenarios, and record/replay fixtures).

## Status

| Milestone | Status | Notes |
|---|---|---|
| M0 Credentials, repo, docs, research | DONE | Keys verified, repo created, toolchain installed, research recorded. |
| M1 Skeleton + Logger + LogViewer + ci.yml | DONE | Green build: debug + release APK (R8), unit tests, lint clean. |
| M2 Accessibility service, onboarding, FGS, overlay+STOP | DONE | Builds green. Emulator verification runs in `e2e-emulator.yml`. |
| M3 Perception | DONE | Pure perception logic + live capture, unit tests, emulator artifacts. |
| M4 Tools (launch_intent, batch_actions) | DONE | 24 tools, intent policy + batch validator, unit tests. |
| M5 Brain gateway | DONE | Providers, router with failover, keystore-encrypted keys, connection test. |
| M6 Orchestrator | DONE | Step engine, planning, confirmation, ask_user, stuck recovery, STOP. |
| M7 Safety | DONE | Risk classifier, denylist, injection detector, intent policy, confirmation gate. |
| M8 Memory + skills | DONE | Memory store and the draft-skill flow. |
| M9 Voice, UX, settings, self-test, updater | DONE | Settings, voice, history, memory, skills, self-test screens; version check and APK verification. |
| M10 Testbed + 23 scenarios + fixtures | DONE | Testbed app, 23 scenarios automated, record/replay fixtures, and the device suite green on API 30 and 34: `OK (30 tests)`, nothing skipped. |
| M11 LLM-in-the-loop tuning + hardening | TODO | |
| M12 Signing, release, v1.0.0 | TODO | |

## Done (M10)

- The emulator suite runs `OK (30 tests)` on API 30 and API 34 with no skips. Getting there
  took four separate faults, all of which presented identically — the accessibility service
  enabled in settings, unbound, and the tests that need it failing or quietly skipping. They
  are written up under "Making the accessibility service survive an instrumented run" in
  `AGENTS.md`; read that before touching the device setup or the test runner.
- Two scenarios were failing for reasons that were bugs in the app rather than the test. A
  `FLAG_SECURE` window is redacted rather than refused: `takeScreenshot` returns a black frame
  with no error, and the blank check that exists for that case was running after the marks were
  drawn, so the black had red boxes painted over it and the snapshot claimed success. The check
  now runs on the raw capture. The testbed's buttons also keep their authored case, because the
  platform's uppercase transform reaches the accessibility tree and made "Target item 47"
  unfindable.
- The eval job read `secrets.EVAL_API_KEY`, which had never been set, so the key arrived empty
  and the test skipped while the job reported green. It reads `secrets.GEMINI_API_KEY`.

- `testbed/` app (`com.phoneagent.testbed`, debug-only, no INTERNET permission) with the
  screens the brief asks for: chat with a Send button, a three-field form, a custom-drawn
  editor with no per-handle nodes, a `FLAG_SECURE` screen, a fake bank, a popup, a permission
  demo, a keyboard screen, a slow-load screen, a stuck button, an injection page, a Hindi
  field, and a long list. `EditorResultProvider` exposes the editor's trim values so a test
  can assert on them rather than reading numbers off a screen.
- The 23 required scenarios from the brief now each have an automated test in
  `RequiredScenariosTest`. Every one runs against the shipped assets (the real
  `safety_keywords.json` and `intent_policy.json`), not a policy the test invents.
- `ScenarioHarness`: a scripted model, screen and actuator that drive the real orchestrator
  and the real tools on the JVM. `ScenarioTools.standard(...)` assembles the shipped tool
  set without a `Context`, so the scenarios exercise `batch_actions` and `launch_intent`
  rather than stand-ins.
- Record/replay: `RecordedRun` plus `ReplayFixtureTest` replay a successful trajectory
  through the same orchestrator with no model and no network, so a finding from the LLM
  evaluation cannot be silently undone later, and costs no quota. First fixture:
  `open-settings-toggle.json`.
- `TestbedInstrumentedTest`: the part that cannot be faked on the JVM. A custom-drawn canvas
  that exposes no handles, a `FLAG_SECURE` window, an injected page, Devanagari in a field,
  a button that does nothing, and a deep link that arrives with the message already typed.
- `e2e-emulator.yml` installs `:testbed:installDebug` so the instrumented tests can drive it.

## What the scenario work actually found

Five real defects, none of which a passing test would have shown:

1. `WaitForTool` coerced a requested timeout up to a 1 s floor and bounded its poll loop only
   by the clock, so a sleeper that returned without advancing time spun forever (this is what
   produced the "Java heap space" failure). It is now bounded by a poll count as well.
2. The self-test listed `MODEL_REACHABLE` after the optional checks. A failure in an essential
   check could therefore be hidden behind an optional one; essentials now run first.
3. Injected on-screen text was being adopted as the user's own goal instead of being presented
   as data to be judged. Scenario 12 pins the corrected behaviour.
4. `ScenarioHarness` shipped an empty default screen, which is "unusable" by design, so every
   step returned early and the tests silently asserted nothing. It now starts from a readable
   screen.
5. `ScriptedToolContext.refreshScreen()` read the perception field directly instead of going
   through `capture()`, so a tool that refreshes the screen (the batch validator does) never
   saw a screen the scenario swapped in. A whole class of "the screen changed under the tool"
   test could not fail. It now reads through `capture()`.

## Next

1. M11: LLM-in-the-loop tuning. Run `eval.yml`, record the pass rate in
   `docs/EVAL_REPORT.md`, iterate on the system prompt and the loop, then harden the parts
   that only show up with a real model (keyboard, orientation, slow load, cost control).
2. M12: signing, the release pipeline, and the v1.0.0 release.

## Blockers

- None.

## Open questions for the human

- None. Credentials for `GITHUB_TOKEN` and `GEMINI_API_KEY` received; repo name chosen as
  `agent-mobile` per instruction.

## Notes for the next session

- **Push protection caught a real leak in M1**: the live Gemini key had been pasted into
  `SecretMaskerTest.kt`. It is now a synthetic value built at runtime. Never paste a real
  credential into a test; the `SecretMasker` tests use obvious placeholders on purpose.
- `android-actions/setup-android@v3` fails on this runner image: it runs
  `sdkmanager --licenses`, which prompts `y/N` with no stdin and exits 1. `ci.yml` now
  calls `sdkmanager` directly with the licenses piped in.
- `res/mipmap-anydpi-v26` must keep the `-v26` qualifier for the adaptive icon. Lint's
  `ObsoleteSdkInt` warning there is a false positive and is left alone.
- Do not bump core-ktx to 1.19.x, lifecycle to 2.11.x, navigation to 2.10.x or OkHttp to
  5.5.x: each needs `compileSdk` 37 and AGP 9.1+.
- `e2e-emulator.yml` must install the APK *before* writing
  `enabled_accessibility_services`. Android silently ignores that setting for a package that
  is not installed yet, which makes every perception test skip rather than fail.
- Compose `assertIsDisplayed()` fails for anything below the fold. The onboarding list is
  taller than a phone screen, so its test scrolls each row into view first.
- A test's screen and the tool's screen are not the same thing. `batch_actions` reads the
  screen several times, so "the screen changed" needs a count, not a flag: see
  `ScriptedPerception.swapScreenAfter(n)`.
- The JVM harness cannot model an intent that carries data into a screen, and the shipped
  policy refuses any URI scheme it does not know (correctly). Deep-link arrival is therefore
  a device test, not a JVM one.
