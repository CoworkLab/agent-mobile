# Research

Verified facts only. Each entry has a source and the date checked.
Anything not yet verified is listed under "Pending verification" and must not be
relied on in code until confirmed.

Checked: 2026-10-22.

## 1. Build toolchain

| Component | Version chosen | How verified | Source |
|---|---|---|---|
| Gradle | 8.14.6 | listed in Gradle's official version service | https://services.gradle.org/versions/all |
| Android Gradle Plugin | 8.13.2 | present in Google Maven `com.android.tools.build:gradle` metadata; newest 8.x | https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml |
| AGP (newest overall) | 9.4.1 | same metadata (not used, see DECISIONS #3) | same |
| Kotlin | 2.2.21 | present in Maven Central `kotlin-gradle-plugin` metadata | https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml |
| KSP | 2.2.21-2.0.5 | KSP1 line matching Kotlin 2.2.21 exactly (KSP must match the compiler version) | https://repo1.maven.org/maven2/com/google/devtools/ksp/symbol-processing-gradle-plugin/maven-metadata.xml |
| compileSdk | 36 | platform installed via `sdkmanager "platforms;android-36"` | local SDK |
| build-tools | 36.0.0 | installed via `sdkmanager` | local SDK |
| JDK | 17 (CI), 21 (sandbox) | AGP 8.x requires JDK 17+ | https://developer.android.com/build/releases/agp-8-12-0-release-notes |

Note: the AGP 8.13.2 POM declares a runtime dependency on `kotlin-stdlib` 2.2.0, which is
consistent with the Kotlin 2.2.x choice.

## 2. AndroidX and libraries

| Library | Version | Source |
|---|---|---|
| Compose BOM | 2026.04.01 (Compose 1.11.0) | https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/maven-metadata.xml — BOM 2026.09.00 maps to Compose 1.12.1 which needs `compileSdk` 37 + AGP 9.1.1, so 2026.04.01 is the newest BOM usable on the AGP 8.13.2 path |
| Room | 2.8.5 | https://dl.google.com/dl/android/maven2/androidx/room/room-runtime/maven-metadata.xml |
| DataStore (preferences) | 1.3.0 | https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-preferences/maven-metadata.xml |
| core-ktx | 1.18.0 | https://dl.google.com/dl/android/maven2/androidx/core/core-ktx/maven-metadata.xml — 1.19.x is the first release requiring `compileSdk` 37 (read from `aar-metadata.properties`: 1.18.0 → 36, 1.19.0 → 37) |
| activity-compose | 1.13.0 | `aar-metadata.properties` says `minCompileSdk=36`, so this newest release is usable |
| lifecycle | 2.10.0 | `lifecycle-runtime-compose-android` 2.11.0 needs `compileSdk` 37; 2.10.0 needs 35 |
| navigation-compose | 2.9.5 | `navigation-compose-android` 2.10.x needs `compileSdk` 37; 2.9.5 needs 35 |
| OkHttp | 5.4.0 | https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml — `okhttp-android` 5.5.1 needs `compileSdk` 37; 5.4.0 needs 36 |
| MockWebServer | 5.4.0 | same release train as OkHttp |
| kotlinx.serialization JSON | 1.9.0 | https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json/maven-metadata.xml |

All versions above were read directly from Maven repository metadata, not from blog posts.

### Why the newest version of each library is not always used

Verified by unzipping `META-INF/com/android/build/gradle/aar-metadata.properties` from each
AAR. Several July–October 2026 AndroidX releases (`core` 1.19.x, `lifecycle` 2.11.x,
`navigation` 2.10.x, `okhttp-android` 5.5.1) declare `minCompileSdk=37` and
`minAgpVersion=9.1.0`. Taking any one of them forces the whole project onto AGP 9.1+ and
`compileSdk` 37.

The table above is the newest mutually consistent set that builds on AGP 8.13.2 with
`compileSdk` 36. This was confirmed by a real `assembleDebug`/`assembleRelease` run, not by
inspection alone. Upgrading past this point is a deliberate, separate migration (see
DECISIONS #3 and #5).

## 3. Android platform APIs

### Screenshots from an AccessibilityService

- `AccessibilityService.takeScreenshot(displayId, executor, callback)` exists from API 30.
- It is rate limited. A request inside the interval fails with
  `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`.
- The interval constant is
  `AccessibilityService.ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS`.
  AOSP CTS proves the behaviour: a second request at half the interval fails, one at
  interval + 1 ms succeeds.
- Source: AOSP CTS `AccessibilityTakeScreenshotTest.java`,
  https://android.googlesource.com/platform/cts/+/master/tests/accessibilityservice/src/android/accessibilityservice/cts/AccessibilityTakeScreenshotTest.java
- Consequence: rate limit capture to at least ~1 s and treat
  `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` as retryable, not fatal.

### Gesture injection

- `AccessibilityService.dispatchGesture(GestureDescription, callback, handler)` exists from
  API 24.
- A gesture is built from one or more `GestureDescription.StrokeDescription`, each with a
  `Path` and a duration. The system injects them as real touch events; receiving apps
  cannot distinguish them from human touches.
- `dispatchGesture` returns `false` if it could not be dispatched; completion arrives via
  the callback, so it must be awaited (`suspendCancellableCoroutine`).
- Source: AOSP `AccessibilityService.java`,
  https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/accessibilityservice/AccessibilityService.java
- Useful flags: `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`, `FLAG_REPORT_VIEW_IDS`.

### Foreground service types (Android 14 / 15)

- Apps targeting API 34+ must declare a foreground service type.
- `specialUse` (`FOREGROUND_SERVICE_TYPE_SPECIAL_USE`) covers valid use cases not covered
  by other types, and should be documented with a `<property>` of
  `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`.
  Source: https://developer.android.com/about/versions/14/changes/fgs-types-required
- Android 15 (API 35) behaviour changes that matter here:
  - `dataSync` and the new `mediaProcessing` types are limited to 6 hours per day.
  - Starting a foreground service from the background without an exemption throws
    `ForegroundServiceStartNotAllowedException`.
  Source: https://developer.android.com/about/versions/15/behavior-changes-15
- Consequence: use `specialUse`, start the service from a user-visible action (the app UI
  or the overlay STOP control), never from `BOOT_COMPLETED`.

## 4. LLM providers (verified live in this session)

### Gemini

- OpenAI-compatible endpoint: `https://generativelanguage.googleapis.com/v1beta/openai/`,
  `POST /chat/completions`, `Authorization: Bearer <key>`.
  Source: https://ai.google.dev/gemini-api/docs/openai
- **Verified live, 2026-10-22:** image input via
  `{"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}` returns correct
  answers. Tested with a synthetic solid-colour PNG; the model answered "blue".
- **Verified live, 2026-10-22:** `gemini-2.5-flash` now returns HTTP 404
  "no longer available to new users" and directs callers to `gemini-3.8-flash`.
- Models that answered correctly with vision in the live test: `gemini-flash-latest`,
  `gemini-3.5-flash`, `gemini-3.7-flash`.
- `gemini-3.8-flash` returned HTTP 503 (transient high demand) at test time.
- Consequence: the default preset should use an alias such as `gemini-flash-latest` or
  `gemini-3.5-flash`, and the router must handle 503/429 with retry and fallback.
- Free-tier limits are indicative only and change; assume roughly 10 requests/minute and
  ~1500 requests/day, no billing fallback, HTTP 429 on overrun.

### OpenRouter / Groq

- OpenRouter base URL: `https://openrouter.ai/api/v1` (per the brief's Appendix A; not yet
  re-verified live because no key was supplied).
- Groq base URL: `https://api.groq.com/openai/v1` (per the brief's Appendix A; not yet
  re-verified live because no key was supplied).
- Both are optional.

## 5. Emulator on GitHub Actions

- Recommended action: `reactivecircus/android-emulator-runner@v2` (latest release
  v2.38.0, 2026-07-05).
  Source: https://github.com/ReactiveCircus/android-emulator-runner
- Linux runners need KVM enabled before the action runs:

  ```
  echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
  sudo udevadm control --reload-rules
  sudo udevadm trigger --name-match=kvm
  ```

- `ubuntu-latest` runners are hardware accelerated and 2-3x faster than macOS.
- Typical inputs: `api-level`, `arch: x86_64`, `target: google_apis`,
  `emulator-options: -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim`,
  `disable-animations: true`, `script:`.
- AVD snapshot caching with `actions/cache` on `~/.android/avd` shortens startup.
- Chosen matrix: API 34 and API 30, `x86_64`, `google_apis`.

## 6. Pending verification (do not rely on these yet)

- The exact value of `ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS` (read it
  from the SDK at build time; do not hardcode a guess).
- The exact `adb` / `settings` commands to enable the accessibility service and grant
  overlay, notification-listener and `POST_NOTIFICATIONS` permissions on API 30 vs API 34.
- Real package IDs for the banking / UPI / password-manager denylist. The brief's Appendix
  A seeds are unverified and must be checked before shipping.
- Whether `<queries>` alone is enough for `resolveActivity` on a sideloaded app, or whether
  `QUERY_ALL_PACKAGES` is required.
- `FLAG_SECURE` behaviour with `takeScreenshot` on API 30 vs 34 (expect a failure or a black
  frame; must be measured on the emulator).
- OpenRouter and Groq live behaviour (no keys supplied).
- Background-activity-launch rules on Android 10+ for the `launch_intent` path.
