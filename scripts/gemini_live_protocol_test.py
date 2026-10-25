#!/usr/bin/env python3
"""
Real-world end-to-end test of the Mobile Agent <-> Gemini Live API protocol.

Speaks the EXACT wire protocol the app speaks (network/GeminiLiveWebSocket.kt):
  1. wss connect with ?key=API_KEY
  2. setup message: model + system_instruction + generation_config (AUDIO/Puck,
     temperature 0.6) + tools (the app's full functionDeclarations + google_search)
  3. client_content turn (same JSON shape as sendClientContent)
  4. expects setupComplete -> model audio turn -> toolCall -> tool_response -> completion

Phases:
  A. Model availability probe: tries each model the app ships (GeminiConstants.SUPPORTED_MODELS)
  B. Full agent loop on the first live model: text in -> AUDIO out bytes -> tool call
     round trip (open_app) -> turn completion.

Usage:
  GEMINI_API_KEY=AIza... python3 scripts/gemini_live_protocol_test.py
  (CI: the live-protocol-test job injects the secret; missing key -> graceful skip)

Exit codes: 0 pass, 1 fail, 2 skipped (no key).
"""
import base64
import json
import os
import struct
import sys
import time
import urllib.request

try:
    from websocket import create_connection  # websocket-client
except ImportError:
    print("FAIL: pip install websocket-client")
    sys.exit(1)

WS_BASE = ("wss://generativelanguage.googleapis.com/ws/"
           "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")

# GeminiConstants.SUPPORTED_MODELS (keep in sync with the app)
APP_MODELS = [
    "models/gemini-3.1-flash-live-preview",
    "models/gemini-3.8-live",
    "models/gemini-3.1-flash-agent",
]
DEFAULT_VOICE = "Puck"

PASS = []
FAIL = []


def check(name, ok, detail=""):
    (PASS if ok else FAIL).append((name, detail))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" - {detail}" if detail else ""))


def rest_model_list(api_key):
    """Mirror of ApiConnectionActivity.testConnection: v1beta/models REST probe."""
    url = f"https://generativelanguage.googleapis.com/v1beta/models?key={api_key}&pageSize=200"
    try:
        with urllib.request.urlopen(url, timeout=20) as r:
            names = [m["name"] for m in json.load(r).get("models", [])]
        return names
    except urllib.error.HTTPError as e:
        print(f"  REST model list failed: HTTP {e.code} (key validity problem)")
        return []
    except Exception as e:
        print(f"  REST model list failed: {e}")
        return []


def build_setup(model: str) -> dict:
    """Byte-faithful reproduction of GeminiLiveWebSocket.sendSetupMessage()."""
    system_instruction = (
        "You are Mobile Agent: an autonomous, on-device Android AI agent running on the "
        "Gemini Live API. You are professional, accurate, to the point. "
        "How you address the user is dynamic and comes from memory only - never default "
        "to an honorific on your own. Execute tools honestly; never fake completion."
    )
    return {"setup": {
        "model": model,
        "system_instruction": {"parts": [{"text": system_instruction}]},
        "generation_config": {
            "response_modalities": ["AUDIO"],
            "speech_config": {"voice_config": {"prebuilt_voice_config": {"voice_name": DEFAULT_VOICE}}},
            "temperature": 0.6,
        },
        "tools": [
            {"functionDeclarations": [{
                "name": "open_app",
                "description": "Launch an app by name (e.g. WhatsApp, Spotify, Instagram).",
                "parameters": {"type": "OBJECT", "properties": {
                    "app": {"type": "STRING", "description": "app name or package name"}},
                    "required": ["app"]}},
                {"name": "task_completed",
                 "description": "Finish the current task with an honest report.",
                 "parameters": {"type": "OBJECT", "properties": {
                     "summary": {"type": "STRING"},
                     "success": {"type": "BOOLEAN"}}}},
            ]},
            {"google_search": {}},
        ],
    }}


def recv_json(ws, timeout_s: float):
    ws.settimeout(timeout_s)
    raw = ws.recv()
    if isinstance(raw, (bytes, bytearray)):
        raw = raw.decode("utf-8", "replace")
    return json.loads(raw)


def collect_audio_bytes(payload: dict) -> int:
    n = 0
    sc = payload.get("serverContent") or {}
    for turn_key in ("modelTurn", "turnOutput", "audio"):
        node = sc.get(turn_key)
        parts = node.get("parts") if isinstance(node, dict) else None
        for part in (parts or []):
            data = (part.get("inlineData") or {}).get("data")
            if data:
                n += len(base64.b64decode(data))
    return n


def probe_model(api_key: str, model: str) -> bool:
    """Phase A: does the API accept a full setup for this model?"""
    try:
        ws = create_connection(f"{WS_BASE}?key={api_key}", timeout=25,
                               suppress_origin=True)
        try:
            ws.send(json.dumps(build_setup(model)))
            deadline = time.time() + 30
            while time.time() < deadline:
                msg = recv_json(ws, 30)
                if "setupComplete" in msg:
                    return True
                if "error" in msg:
                    print(f"    server error: {msg['error'].get('code')} {str(msg['error'].get('message'))[:160]}")
                    return False
            return False
        finally:
            ws.close()
    except Exception as e:
        print(f"    transport error: {type(e).__name__}: {str(e)[:160]}")
        return False


def full_agent_loop(api_key: str, model: str) -> None:
    """Phase B: text in -> audio out -> tool call -> tool response -> completion."""
    ws = create_connection(f"{WS_BASE}?key={api_key}", timeout=25, suppress_origin=True)
    try:
        t0 = time.time()
        ws.send(json.dumps(build_setup(model)))
        while True:
            msg = recv_json(ws, 30)
            if "setupComplete" in msg:
                break
            if "error" in msg:
                raise RuntimeError(f"setup rejected: {msg['error']}")
        check("setup accepted (schema + grounding valid)", True, f"{time.time()-t0:.1f}s")

        # Text turn, identical shape to GeminiLiveWebSocket.sendClientContent()
        ws.send(json.dumps({"client_content": {"turns": [{"role": "user", "parts": [
            {"text": "Open Spotify for me using the open_app tool right now. "
                     "Do not ask anything, just call the tool."}
        ]}], "turn_complete": True}}))

        tool_calls, audio_bytes, text_out = [], 0, ""
        deadline = time.time() + 90
        while time.time() < deadline and not tool_calls:
            msg = recv_json(ws, 90)
            audio_bytes += collect_audio_bytes(msg)
            sc = msg.get("serverContent") or {}
            for part in ((sc.get("modelTurn") or {}).get("parts") or []):
                text_out += part.get("text", "")
            tc = msg.get("toolCall") or sc.get("toolCall")
            if tc:
                tool_calls = tc.get("functionCalls") or []

        check("model produced an AUDIO turn (voice stack input path)", audio_bytes > 0,
              f"{audio_bytes} bytes of PCM")
        check("model invoked a tool from the schema", bool(tool_calls),
              text_out[:80] if text_out and not tool_calls else
              (", ".join(c.get("name", "?") for c in tool_calls)))

        if tool_calls:
            # tool_response shape identical to sendToolResponse()
            responses = []
            for c in tool_calls:
                if c.get("name") == "open_app":
                    responses.append({"id": c["id"], "name": "open_app",
                                      "response": {"success": True, "current_app": "Spotify"}})
                else:
                    responses.append({"id": c.get("id", ""), "name": c.get("name", ""),
                                      "response": {"success": True}})
            ws.send(json.dumps({"tool_response": {"function_responses": responses}}))

            done = False
            deadline = time.time() + 60
            while time.time() < deadline:
                msg = recv_json(ws, 60)
                audio_bytes += collect_audio_bytes(msg)
                sc = msg.get("serverContent") or {}
                if sc.get("turnComplete"):
                    done = True
                    break
                for part in ((sc.get("modelTurn") or {}).get("parts") or []):
                    text_out += part.get("text", "")
                if msg.get("toolCall") or (sc.get("toolCall")):
                    done = True  # follow-up tool call also proves the loop continued
                    break
            check("agent loop completed after tool_response (no hang)", done, text_out[:100])
    finally:
        try:
            ws.close()
        except Exception:
            pass


def main():
    api_key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not api_key:
        print("SKIP: GEMINI_API_KEY not set (live protocol test not run)")
        return 2

    print("== Phase 0: API key validity (REST, mirrors app's Test Connection button) ==")
    models = rest_model_list(api_key)
    check("API key authenticates against generativelanguage.googleapis.com", bool(models),
          f"{len(models)} models visible")
    if not models:
        print("FAIL: key cannot list models - nothing further can run.")
        return 1

    print("== Phase A: model availability (the 3 models the app ships) ==")
    live_model = None
    for model in APP_MODELS:
        ok = probe_model(api_key, model)
        check(f"model accepts BidiGenerateContent setup: {model}", ok)
        if ok and not live_model:
            live_model = model
    if not live_model:
        print("FAIL: none of the app's models accepted a setup.")
        return 1
    print(f"  -> running full agent loop on {live_model}")

    print("== Phase B: full agent loop (text -> audio -> toolCall -> toolResponse -> done) ==")
    full_agent_loop(api_key, live_model)

    print(f"\n===== RESULT: {len(PASS)} passed, {len(FAIL)} failed =====")
    return 0 if not FAIL else 1


if __name__ == "__main__":
    sys.exit(main())
