# Updating PhoneAgent

This document exists so any future session can continue the project even with no memory
of previous ones.

## Starting a session

1. Read `AGENTS.md`.
2. Read `docs/PROGRESS.md` and resume from its "Next" section.
3. Read `docs/ARCHITECTURE.md` and `docs/CHANGELOG.md` before changing behaviour.

## When the human asks for a change

They will say something like "ye feature add karo" or "ye bug theek karo".

1. Re-read the four files above.
2. Tell the human in 2 short Hinglish lines what will change.
3. Write or adjust the tests **first** for the change, then implement.
4. Run every gate in `docs/TEST_STRATEGY.md`. No regressions allowed: if an old scenario
   breaks, fix it before releasing.
5. Bump the version, update `docs/CHANGELOG.md`, tag `vX.Y.Z`, release.
6. Tell the human in 3 lines: what changed, and
   "App kholo -> Settings -> Check for update -> Install."
7. Keep this file accurate.

## Release mechanics

- `versionCode` comes from the GitHub Actions run number; `versionName` is semantic.
- Every release is signed with the same keystore so the new APK installs over the old one.
- Tag `vX.Y.Z` triggers `release.yml`, which builds the signed APK, runs the update-in-place
  test, and publishes a GitHub Release with the APK, its SHA-256 checksum and the changelog.
- If the keystore is ever lost, the human must uninstall once. Tell them only if it happens.

## Adding a provider

Model names change often. Never hardcode them in source. Add a preset in
`brain/ProviderPresets.kt` and keep the model name editable in Settings.
