# Decisions

Every non-obvious decision, with the reason. Newest last.

| # | Date | Decision | Reason |
|---|---|---|---|
| 1 | 2026-10-22 | Repo name `agent-mobile` under account `CoworkLab` | Explicitly requested by the human. |
| 2 | 2026-10-22 | Repo is public | Required by the in-app updater design: it must read GitHub Releases without shipping a token in the APK. |
| 3 | 2026-10-22 | Toolchain: Gradle 8.14.6 + AGP 8.13.2 + Kotlin 2.2.21 | AGP 9.x is current but brings breaking DSL and Variant API changes plus a mid-2026 migration cliff. AGP 8.13.2 is the newest 8.x, supports API 36, and pairs with Kotlin 2.2.x, whose KSP releases are stable. Lowest-risk path to a green build. |
| 4 | 2026-10-22 | `compileSdk` 36, `targetSdk` 35, `minSdk` 30 | 36 is the newest stable platform installed; 35 is the Play requirement for new apps; 30 is the floor for `takeScreenshot` plus a large device base. |
| 5 | 2026-10-22 | Compose BOM 2026.04.01 (Compose 1.11.0), not the newest 2026.09.00 | Verified from the BOM POMs: 2026.09.00 maps to Compose 1.12.1, which requires `compileSdk` 37 and AGP 9.1.1+, forcing the AGP 9 migration. 2026.04.01 (Compose 1.11.0) is stable and works with AGP 8.13.2 and `compileSdk` 36. Revisit when the AGP 9 migration is done deliberately. |
| 6 | 2026-10-22 | Kotlin 2.2.21 rather than 2.4.x | AGP 8.13.2 is validated against Kotlin 2.2.x (its POM depends on `kotlin-stdlib` 2.2.0), and KSP has a matching `2.2.21-2.0.5` release. Kotlin 2.4.x is not validated against this AGP. Prioritise a reproducible green build. |
| 7 | 2026-10-22 | Model names are user-configurable, defaults seeded from the verified list | Model names change often (proved immediately: `gemini-2.5-flash` was retired for new keys during this session). Never hardcode. |
| 8 | 2026-10-22 | Default provider preset: Gemini OpenAI-compatible base URL `https://generativelanguage.googleapis.com/v1beta/openai/` | Verified working with vision in this session. |
| 9 | 2026-10-22 | Signing keystore: generated once, stored as GitHub Secrets + an encrypted copy in a private repo | Required so every future APK installs over the previous one without uninstalling. |
| 10 | 2026-10-22 | JVM unit and simulation tests run in `ci.yml`; emulator tests in a separate `e2e-emulator.yml` | Emulator jobs are slow and expensive; the fast gate must stay fast. |
| 11 | 2026-10-22 | Android dependencies hidden behind interfaces (`PerceptionSource`, `Actuator`, `LlmProvider`, `Clock`, `Store`) | Lets the whole agent loop be tested on a plain JVM with fakes, without an emulator. |
| 12 | 2026-10-22 | Use `specialUse` foreground service type, started only from a user-visible action | Android 14+ requires a declared type; Android 15 restricts starting FGS from the background and forbids it from `BOOT_COMPLETED` for restricted types. |
