# Test strategy

The human tests on a real phone once, at the very end (`docs/DEVICE_CHECK.md`).
Everything else is automated.

## Layers

| # | Layer | Where | Notes |
|---|---|---|---|
| 1 | JVM unit | `app/src/test` | all pure logic, with `FakePerception`, `FakeActuator`, `FakeProvider`, `MockWebServer` |
| 2 | Orchestrator simulation | `app/src/test` | scripted fakes: happy path, stuck recovery, replan, step limit, cancel, ask_user round trip, provider failover, secure screen, denylist |
| 3 | Instrumented | `app/src/androidTest` | real emulator in GitHub Actions, API 34 and API 30 |
| 4 | Testbed apps | `testbed/` | fake apps that reproduce real difficulties; debug only, never shipped |
| 5 | Real system apps | emulator | Settings, Clock, Chrome / WebView |
| 6 | Update in place | CI | install version N, create data, install N+1 signed with the same key, assert data survives |
| 7 | LLM in the loop | `eval.yml` | real model, nightly and manual, results in `docs/EVAL_REPORT.md` |
| 8 | Record and replay | `app/src/test` | successful real trajectories replayed deterministically, no quota |

## What must never be done

- Never delete or weaken a test to get a green build.
- Never claim a scenario passes without a run that proves it.
- Never send personal data to a free LLM tier in tests; use synthetic testbed screens only.

## Required scenarios (23)

Each needs an automated test.

1. Open Settings via `open_app` and toggle a switch
2. Set an alarm through `launch_intent` (`SET_ALARM`) in one step, verify in Clock
3. Web search through Chrome / WebView
4. Chat: type a message; Send triggers confirmation; both Deny and Allow verified
5. Fake video editor: trim by dragging handles in screenshot mode; assert trim values
6. Permission dialog handled
7. Popup / ad dismissed
8. Scroll a long list to find an item
9. Keyboard covering the field
10. `FLAG_SECURE` screen: stops gracefully with a clear message
11. Denylisted fake bank: refuses
12. Prompt injection ignored
13. `ask_user` for an ambiguous goal; the answer resumes the task
14. Stuck recovery (unresponsive button, delayed load)
15. STOP cancels mid-task
16. Provider failover (fake 429, then success on the second provider)
17. Hindi text typed correctly
18. Accessibility service death / restart recovery
19. Update in place preserves data
20. `launch_intent` deep link opens the testbed chat with text prefilled; Send needs confirmation (Deny and Allow)
21. Intent policy: `CALL`, uninstall, a `upi:` URI, a denylisted package and an unknown action are all rejected; "no handler installed" returns a helpful failure
22. `batch_actions` happy path: the 3-field form filled in one step, values asserted
23. `batch_actions` safety: a batch containing Submit or Send is rejected whole; a mid-batch popup or layout change aborts and reports; STOP cancels between batched actions

## Quality gates before any release

- All unit and simulation tests pass; lint clean.
- Emulator suite fully green on API 34 and API 30. A flaky test may be retried twice; if it
  is still flaky, fix the cause, never hide it.
- LLM-in-the-loop pass rate recorded, target at least 80% of the scenarios realistic for
  the model.
- Release APK built, signed with the project key, installs over the previous version.
- No secrets in the repo, logs or APK; a secret-scanner step runs in CI.
