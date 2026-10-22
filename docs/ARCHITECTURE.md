# Architecture

## Shape

One Gradle module `app` (plus `testbed` for fake apps), package `com.phoneagent`.
Everything that touches Android sits behind an interface so the agent loop is testable
on a plain JVM.

```
ui/  -->  agent/AgentController  -->  agent/AgentLoop (state machine)
                |                        |
                |                        +--> perception/PerceptionSource  (interface)
                |                        +--> brain/LlmProvider            (interface)
                |                        +--> actions/ToolRegistry --> Actuator (interface)
                |                        +--> safety/Guard
                |                        +--> memory/Store                (interface)
                |
service/  -->  AccessibilityService, ForegroundService, NotificationListener, Overlay
update/   -->  in-app updater
util/     -->  Logger, result types
```

## Core loop

```
IDLE -> PLANNING -> OBSERVING -> DECIDING -> GUARDING -> ACTING -> VERIFYING -> ...
                                                    +-> WAITING_USER (ask_user)
                                                    +-> DONE | FAILED | CANCELLED
```

Each cycle:

1. **OBSERVE** builds a `ScreenSnapshot`: tree, optional screenshot, set-of-marks, hashes.
2. **DECIDE** sends the prompt to the LLM; it returns exactly one tool call.
3. **GUARD** runs the risk classifier, denylist, limits, confirmation and injection check.
4. **ACT** executes the tool through `ToolExecutor` (timeout, exception safety, logging).
5. **VERIFY** compares the tree hash and perceptual screenshot hash. Unchanged twice means
   stuck: inject a hint. Three stuck events trigger a replan. Five ask the user or fail.

## Perception modes

| Mode | When | Cost |
|---|---|---|
| `TREE_ONLY` | tree is rich (most native apps) | cheapest, no image tokens |
| `TREE_PLUS_SCREENSHOT` | tree is thin or ambiguous | medium |
| `SCREENSHOT_ONLY` | custom-drawn UI: editors, games, Flutter, canvas | highest, most capable |

Auto-escalate to `SCREENSHOT_ONLY` when the tree is poor. Prefer `TREE_ONLY` first to save
quota.

## Set-of-marks

Numbered boxes are drawn on clickable, editable and scrollable nodes. A `mark -> nodeId`
map is kept per snapshot. Tools may target a mark instead of raw coordinates, which is far
more reliable than guessing pixels and is what lets the agent work on custom-drawn UI in
screenshot mode.

## Tools

`Tool { name, description, jsonSchema, suspend execute(args): ToolResult }`.
The registry exports OpenAI function-calling definitions directly.

Groups: touch, text, navigation, intents (`launch_intent` is the general fast path), info,
wait, batch (`batch_actions`), control (`ask_user`, `finish`, `fail`), memory and skills,
voice.

Rules: prefer `ACTION_CLICK` on a node and fall back to a coordinate gesture. Await
`dispatchGesture` completion. Settle briefly after each action. Every tool runs through
`ToolExecutor` with a timeout and exception safety, and is logged.

## Safety

- The risk classifier labels each proposed action LOW / MEDIUM / HIGH.
- HIGH (send, pay, delete, uninstall, confirm, and their Hinglish equivalents) requires
  explicit user confirmation.
- A denylist of banking, UPI, password-manager and authenticator packages, editable in
  Settings. On a denylisted package or a `FLAG_SECURE` screen the agent stops and explains.
- Limits: 40 steps, 10 minutes, 4 consecutive tool errors, 30 actions/minute (configurable).
- Screen, notification and web text is untrusted data. The injection detector refuses to
  treat on-screen instructions as commands.

## Memory

A Room database with three tables: profile facts, episodes (what worked, what failed) and
skills (drafted, then user-approved). History compaction keeps at most the last 2
screenshots; older steps collapse to one-line text summaries and the prompt is capped.

## Testing seams

`PerceptionSource`, `Actuator`, `LlmProvider`, `Clock` and `Store` are interfaces.
Production implementations live in `service/`; test fakes live in `app/src/test`. That is
what allows the whole orchestrator to be exercised deterministically without an emulator,
and lets recorded real-LLM trajectories be replayed as fixtures.

See `docs/TEST_STRATEGY.md`.
