# PhoneAgent

An Android app that runs entirely on your phone and controls it like a human, driven by a
vision-capable LLM over an API.

The app is the **body**: eyes (screenshots plus the accessibility tree), hands (taps,
swipes, typing, navigation), safety and memory. The LLM is the **brain**, reached through
an OpenAI-compatible provider interface with automatic fallback.

You state a goal in plain language. The agent looks at the screen, decides one action at a
time, acts, checks the result, and recovers from mistakes. Nothing is hardcoded per app.

Runs on-device only: no PC, no ADB, no root at runtime.

## Status

Early development. The project is being built and tested milestone by milestone; see
`docs/PROGRESS.md` for the current state. There is no installable release yet.

## Install (once a release exists)

1. Download `PhoneAgent-vX.Y.Z.apk` from the latest GitHub Release.
2. On the phone, allow installing apps from this source when prompted.
3. Install, open the app, and follow onboarding.

The app is signed with a stable key, so a newer version installs straight over an older
one. No uninstalling.

## Setup on the phone

1. Open the app and enable the accessibility service.
2. On Android 13 and newer, also allow "restricted settings" for the app.
3. Open Settings in the app and paste an API key for a vision-capable model.

## Update

Open the app, go to Settings, and tap "Check for update". The app shows the changelog in a
few lines, downloads the new APK, verifies its SHA-256, and hands it to the system
installer. You only tap Install.

## Known limits

These are real and are not hidden.

- Banking and payment apps block screenshots and accessibility (`FLAG_SECURE`), so the
  agent refuses to work in them.
- Fine dragging on custom-drawn timelines, such as video editors, can fail.
- Every step takes seconds and uses API quota.
- The lock screen cannot be opened by the agent.
- Some phone makers aggressively kill background services; those phones need a battery
  exemption.

## For developers

- `AGENTS.md` is the working brief.
- `docs/RESEARCH.md` holds verified versions and API facts.
- `docs/ARCHITECTURE.md` describes the modules and the agent loop.
- `docs/TEST_STRATEGY.md` describes the eight test layers and the 23 required scenarios.
- `docs/UPDATING.md` describes how future changes and releases are done.

Build:

```bash
./gradlew testDebugUnitTest lint assembleDebug
```

Secrets are never committed. Copy `.env.example` to `.env` and fill it in locally.
