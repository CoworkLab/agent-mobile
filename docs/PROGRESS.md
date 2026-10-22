# Progress

Updated: 2026-10-22
Session: M0 (bootstrap).

## Status

| Milestone | Status | Notes |
|---|---|---|
| M0 Credentials, repo, docs, research | DONE | Keys verified, repo created, toolchain installed, research recorded. |
| M1 Skeleton + Logger + LogViewer + ci.yml | IN PROGRESS | Next up. |
| M2 Accessibility service, onboarding, FGS, overlay+STOP | TODO | |
| M3 Perception | TODO | |
| M4 Tools (launch_intent, batch_actions) | TODO | |
| M5 Brain gateway | TODO | |
| M6 Orchestrator | TODO | |
| M7 Safety | TODO | |
| M8 Memory + skills | TODO | |
| M9 Voice, UX, settings, self-test, updater | TODO | |
| M10 Testbed + 23 scenarios + fixtures | TODO | |
| M11 LLM-in-the-loop tuning + hardening | TODO | |
| M12 Signing, release, v1.0.0 | TODO | |

## Done

- GitHub token verified (account `CoworkLab`, scopes include `repo` + `workflow`).
- Gemini API key verified live.
- Public repo created: https://github.com/CoworkLab/agent-mobile
- Sandbox toolchain installed: JDK 21, Android SDK (platform 36, build-tools 36.0.0,
  platform-tools), Gradle 8.14.6.
- Research pass done; toolchain versions pinned in `docs/RESEARCH.md`.
- **Key finding:** `gemini-2.5-flash` is retired for new API keys. Vision verified
  working on `gemini-flash-latest` and `gemini-3.5-flash` via the OpenAI-compatible
  endpoint. Model names stay user-configurable, never hardcoded.

## Next

1. M1: Gradle project skeleton (`app` module), Logger + Log Viewer, crash handler,
   `ci.yml` green with a debug APK artifact.
2. Then M2 onward in order.

## Blockers

- None.

## Open questions for the human

- None. Credentials for `GITHUB_TOKEN` and `GEMINI_API_KEY` received; repo name chosen as
  `agent-mobile` per instruction.
