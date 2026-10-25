# Mobile Agent

An autonomous on-device Android AI agent built with Kotlin and the Gemini Live API.
It speaks, listens, sees the screen, and controls the phone end-to-end: apps, gestures,
messages, files, web, and scheduling.

> Formerly based on an open-source Part-7 codebase; fully rebranded and re-architected
> as Mobile Agent with a 100-tool agent framework. No third-party attribution is
> embedded anywhere in the code.

## App identity

- Application ID: `com.mobileagent.app`
- Display name: Mobile Agent (rename freely — no hardcoded identity anywhere)
- Min Android: 8.0 (API 26) · Compile/Target SDK: 34 · Kotlin/JVM 17
- Version: 2.1.0 (version code 201)

## Agent capabilities (100 tools + native Google Search grounding)

| Group | Tools |
|---|---|
| Perception | get_ui_tree, find_element, screenshot, read_screen_text, get_current_app, wait_for_element, wait_for_idle, read_notifications, notification_action, get_clipboard, set_clipboard |
| Touch & Gestures | tap, double_tap, long_press, swipe, fling, scroll, scroll_until_found, drag_and_drop, pinch, custom_gesture |
| Node Actions | click_node, long_click_node, set_text, type_text, focus_node, select_text, text_action, scroll_node, node_action, press_enter |
| Global Actions | back, home, recents, global_action |
| App & System | open_app, list_installed_apps, open_url, send_intent, open_settings, set_volume, set_brightness, toggle_flashlight, set_dnd, wake_screen, install_apk, uninstall_app, get_battery, get_network_status, get_device_info, get_location |
| Communication | send_sms, read_sms, make_call, end_call, read_contacts, read_call_log, read_calendar, create_event, set_alarm, set_timer, share_content, send_email, send_whatsapp_message, whatsapp_call |
| Files & Media | read_file, write_file, list_dir, delete_file, download_file, take_photo, record_audio, tts_speak, stt_listen |
| Web | http_request, web_search, fetch_page (+ native google_search) |
| Agent Core | batch_actions, wait, ask_user, memory_save, memory_recall, memory_forget, update_plan, schedule_task, list_scheduled_tasks, cancel_scheduled_task, done |
| Device Discovery | get_capabilities, check_permission, request_permission, find_intent_handlers, get_app_info |
| Domain (retained) | search_and_play_youtube, search_youtube, youtube_accessibility_action, media_playback_control, press_key, inspect_screen, go_to_background |

Framework source: `app/src/main/java/com/mobileagent/app/tools/`

## Architecture in one minute

- `background/AgentConversationService` — foreground service; three states:
  ACTIVE (live 20 ms PCM to Gemini), STANDBY (on-device wake-word listening), WAKE.
- `network/GeminiLiveWebSocket` — Gemini Live session (setup, tools, audio, vision frames).
- `tools/ToolRegistry` — declares all tools to the model and dispatches executions.
- `automation/accessibility/AgentAccessibilityService` — accessibility tree, gestures, screenshots.
- `vision/` — screen capture, UI grounding, autonomous visual task orchestration.
- `data/memory/` — Room-backed long-term memory (names, preferences, routines).
- `schedule/` — AlarmManager-backed scheduled tasks, reboot-safe.
- `notification/` — notification access for read/act on notifications.

## Dynamic identity (no hardcoding)

- The agent never hardcodes an honorific or a fake persona. It addresses you how you
  taught it (stored in memory via `memory_save`, key `preferred_address`).
- Teach it once: *"call me sir"* / *"mere naam se bulao"* — it saves and complies forever.
- Wake phrases are built in ("agent", "hey agent", "mobile agent", ...) plus any extras
  you add to the `key_wake_words` preference (comma separated).

## Build

1. Open the project root in Android Studio and let Gradle sync.
2. Set your SDK path in a local `local.properties` (machine-specific, git-ignored).
3. Debug build: `./gradlew :app:assembleDebug`

Release builds are gated on explicit signing inputs (never commit keystores):

```bash
AGENT_RELEASE_STORE_FILE=/secure/agent-release.jks \
AGENT_RELEASE_STORE_PASSWORD='...' \
AGENT_RELEASE_KEY_ALIAS='agent-release' \
AGENT_RELEASE_KEY_PASSWORD='...' \
AGENT_RELEASE_CERT_SHA256='<64 hex chars from keytool -list -v>' \
AGENT_DEVELOPER_NAME='Your Name' \
AGENT_COPYRIGHT_OWNER='Your Name' \
./gradlew :app:assembleRelease
```

`packageRelease` refuses to run unless these are set. Windows helper: `scripts/build-release.ps1`.

## Permissions the agent may request at runtime

Microphone, contacts, phone, SMS, call log, calendar, location, camera, notifications
(post + listener access), accessibility, display over other apps, screen capture,
exact alarms, storage. Every tool that needs a missing permission returns a structured
message telling the agent to ask you first.

## Project layout

```text
app/src/main/java/com/mobileagent/app/
├── tools/            100-tool agent framework (registry + 10 tool groups)
├── background/       Live conversation service (voice loop, autonomous engine)
├── network/          Gemini Live WebSocket
├── automation/       Accessibility service, commands, WhatsApp/YouTube/phone/contacts
├── vision/           Screen capture, UI grounding, visual task orchestration
├── data/             Room memory DB, preferences, chat repository
├── audio/            Recorder/player, Vosk wake-word, speaker ID, TTS/STT helpers
├── notification/     Notification listener
├── schedule/         Scheduled tasks (AlarmManager)
├── security/         Cert pinning + identity verification scaffolding
├── ui/               Chat, settings, memory, enrollment, overlays
└── util/             Prompt generator, launchers, helpers
```

## Documentation

- [Voice architecture research](docs/architecture/voice-architecture-research.md)
- [Diagnostics](docs/diagnostics/voice-videos/README.md)
