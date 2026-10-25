#!/usr/bin/env python3
"""
Real-world diagnostic matrix for the Mobile Agent <-> Gemini Live API.

Purpose: empirically identify WHY the agent stops responding, by replaying the
EXACT wire payloads different versions of the app sent, against the REAL API:

  V1  Part 7 provider, model gemini-3.1-flash-live-preview (the "known working" combo)
  V2  Part 7 provider, model gemini-3.8-live
  V3  Current app setup (ToolRegistry-style functionDeclarations + google_search)
  V4  functionDeclarations WITHOUT google_search (isolate the grounding combo)
  V5  google_search ALONE (no functionDeclarations)
  V6  Part 7 setup + transcription request fields (inputAudioTranscription etc.)

Each variant: connect -> setup -> expect setupComplete (or capture the server
error) -> send one text turn -> count audio bytes / tool calls / transcript.

Usage:
  GEMINI_API_KEY=AIza... python3 scripts/gemini_live_protocol_test.py
Exit codes: 0 = all critical variants passed, 1 = failure(s), 2 = skipped.
"""
import base64
import json
import os
import sys
import time
import urllib.request

try:
    import websocket
    from websocket import create_connection  # websocket-client
except ImportError:
    print("FAIL: pip install websocket-client")
    sys.exit(1)

if os.environ.get("DEBUG_WS") == "1":
    websocket.enableTrace(True)

WS_BASE = ("wss://generativelanguage.googleapis.com/ws/"
           "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")

MODEL_31 = "models/gemini-3.1-flash-live-preview"
MODEL_38 = "models/gemini-3.8-live"

PROMPT = (
    "You are Mobile Agent: an autonomous, on-device Android AI agent running on the "
    "Gemini Live API. You are professional, accurate, to the point. "
    "How you address the user is dynamic and comes from memory only - never default "
    "to an honorific on your own. Execute tools honestly; never fake completion."
)

# Part 7's exact 21 tool names for the classic declaration set (subset used here:
# the wire behaviour with 2 simple declarations is equivalent for setup validation;
# the FULL 21/100-declaration payloads are validated by the ToolRegistry JVM tests).
def decl(name, desc, props=None, required=None):
    p = {"type": "OBJECT", "properties": props or {}}
    if required:
        p["required"] = required
    return {"name": name, "description": desc, "parameters": p}

PART7_TOOLS = [
    decl("open_app", "Opens an app installed on the user's phone.",
         {"app_name": {"type": "STRING", "description": "The app to open."},
          "app_number": {"type": "INTEGER", "description": "Optional 1-based index."}},
         ["app_name"]),
    decl("task_completed", "Call this tool only after the user's requested task is visibly confirmed complete.",
         {"summary": {"type": "STRING", "description": "Brief summary."}},
         ["summary"]),
]

CURRENT_TOOLS = [
    decl("open_app", "Launch an app by name (e.g. WhatsApp, Spotify, Instagram).",
         {"app": {"type": "STRING", "description": "app name or package name"}},
         ["app"]),
    decl("task_completed", "Finish the current task with an honest report.",
         {"summary": {"type": "STRING"}, "success": {"type": "BOOLEAN"}},
         ["summary"]),
]


def build_setup(model, tools, include_google=False, snake_case=True,
                include_transcription=False, temperature=0.6):
    def sc(key_camel, key_snake):
        return key_snake if snake_case else key_camel

    setup = {"setup": {
        "model": model,
        sc("systemInstruction", "system_instruction"): {
            "parts": [{"text": PROMPT}]},
        sc("generationConfig", "generation_config"): {
            sc("responseModalities", "response_modalities"): ["AUDIO"],
            sc("speechConfig", "speech_config"): {
                sc("voiceConfig", "voice_config"): {
                    sc("prebuiltVoiceConfig", "prebuilt_voice_config"): {
                        sc("voiceName", "voice_name"): "Puck"}}},
            "temperature": temperature,
        },
    }}
    tool_list = list(tools)
    if include_google:
        tool_list.append({"google_search": {}})
    if tool_list:
        setup["setup"]["tools"] = tool_list
    if include_transcription:
        setup["setup"][sc("outputAudioTranscription", "outputAudioTranscription")] = {}
        setup["setup"][sc("inputAudioTranscription", "inputAudioTranscription")] = {}
    return setup


def recv_json(ws, timeout_s):
    ws.settimeout(timeout_s)
    raw = ws.recv()
    if isinstance(raw, (bytes, bytearray)):
        raw = raw.decode("utf-8", "replace")
    if raw == "":
        # websocket-client returns "" when the peer sends a CLOSE frame.
        info = ""
        try:
            info = f"close_status={ws.close_status_code} reason={ws.close_reason!r}"
        except Exception:
            try:
                info = f"handshake={ws.handshake_response.status}"
            except Exception:
                info = "close frame (status unavailable)"
        raise RuntimeError(f"server closed the connection immediately [{info}]")
    return json.loads(raw)


def collect_audio_bytes(payload):
    n = 0
    sc = payload.get("serverContent") or {}
    node = sc.get("modelTurn")
    parts = node.get("parts") if isinstance(node, dict) else None
    for part in (parts or []):
        data = (part.get("inlineData") or {}).get("data")
        if data:
            try:
                n += len(base64.b64decode(data))
            except Exception:
                pass
    return n


def run_variant(tag, api_key, setup, send_text=True, t_setup=30, t_turn=45, conn_kwargs=None):
    """Returns dict with outcome details."""
    out = {"tag": tag, "setup_ok": False, "error": None, "audio": 0,
           "tool_calls": [], "text": "", "transcript_user": "", "transcript_out": "",
           "turn_complete": False}
    ws = None
    try:
        kw = {"timeout": 25, "suppress_origin": True}
        kw.update(conn_kwargs or {})
        ws = create_connection(f"{WS_BASE}?key={api_key}", **kw)
        ws.send(json.dumps(setup))
        deadline = time.time() + t_setup
        while time.time() < deadline:
            msg = recv_json(ws, t_setup)
            if "setupComplete" in msg:
                out["setup_ok"] = True
                break
            if "error" in msg:
                e = msg["error"]
                out["error"] = f"{e.get('code')} {str(e.get('message'))[:220]}"
                return out
        if not out["setup_ok"]:
            out["error"] = "timeout waiting for setupComplete"
            return out

        if send_text:
            ws.send(json.dumps({"client_content": {"turns": [
                {"role": "user", "parts": [{"text": "Say exactly: PONG"}]}
            ], "turn_complete": True}}))
            deadline = time.time() + t_turn
            while time.time() < deadline and not out["turn_complete"]:
                try:
                    msg = recv_json(ws, 15)
                except Exception:
                    break
                out["audio"] += collect_audio_bytes(msg)
                sce = msg.get("serverContent") or {}
                for part in ((sce.get("modelTurn") or {}).get("parts") or []):
                    if part.get("text"):
                        out["text"] += part["text"]
                    data = (part.get("inlineData") or {}).get("data")
                    if data:
                        try:
                            out["audio"] += len(base64.b64decode(data))
                        except Exception:
                            pass
                it = sce.get("inputTranscription")
                if isinstance(it, dict) and it.get("text"):
                    out["transcript_user"] += it["text"]
                ot = sce.get("outputTranscription")
                if isinstance(ot, dict) and ot.get("text"):
                    out["transcript_out"] += ot["text"]
                tc = msg.get("toolCall") or sce.get("toolCall")
                if tc:
                    out["tool_calls"] += [c.get("name", "?") for c in (tc.get("functionCalls") or [])]
                if sce.get("turnComplete") or sce.get("generationComplete"):
                    out["turn_complete"] = True
        return out
    except Exception as e:
        out["error"] = f"{type(e).__name__}: {str(e)[:200]}"
        return out
    finally:
        try:
            if ws is not None:
                ws.close()
        except Exception:
            pass


def main():
    api_key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not api_key:
        print("SKIP: GEMINI_API_KEY not set")
        sys.exit(2)

    # REST sanity: key validity + geo check
    try:
        req = urllib.request.Request(
            f"https://generativelanguage.googleapis.com/v1beta/models?key={api_key}&pageSize=5")
        with urllib.request.urlopen(req, timeout=20) as r:
            print(f"REST probe: HTTP {r.status} (key valid, region OK)")
    except urllib.error.HTTPError as e:
        body = e.read().decode()[:200]
        print(f"REST probe FAILED: HTTP {e.code}: {body}")
        if "location" in body.lower():
            print("REGION BLOCK on this runner -> all variants would fail for network reasons.")
        sys.exit(1)

    # V0 handshake diagnostics: does a plain Part 7 setup get past the handshake?
    print("\n=== V0 handshake diagnostics (Part 7 setup, first-frame probe) ===")
    for label, kw in [
        ("suppress_origin (like OkHttp/Android: no Origin header)", None),
        ("origin=https://ai.google.dev (browser-like)", {"origin": "https://ai.google.dev"}),
        ("origin=https://consent.google.com", {"origin": "https://consent.google.com"}),
    ]:
        r = run_variant(f"V0 {label}", api_key, build_setup(MODEL_31, PART7_TOOLS),
                        send_text=False, t_setup=20, conn_kwargs=kw)
        if r["setup_ok"]:
            print(f"  [{label}] setupComplete RECEIVED -> handshake fine")
        else:
            print(f"  [{label}] -> {r['error']}")
        time.sleep(1)

    matrix = [
        ("V1 part7-provider + 3.1-flash-live-preview (the known-working combo)",
         build_setup(MODEL_31, PART7_TOOLS)),
        ("V2 part7-provider + 3.8-live",
         build_setup(MODEL_38, PART7_TOOLS)),
        ("V3 current-app tools + google_search + 3.1-flash-live-preview",
         build_setup(MODEL_31, CURRENT_TOOLS, include_google=True)),
        ("V4 current-app tools WITHOUT google_search + 3.1-flash-live-preview",
         build_setup(MODEL_31, CURRENT_TOOLS)),
        ("V5 google_search alone + 3.1-flash-live-preview",
         build_setup(MODEL_31, [], include_google=True)),
        ("V6 part7 + transcription fields + 3.1-flash-live-preview",
         build_setup(MODEL_31, PART7_TOOLS, include_transcription=True)),
    ]

    results = []
    for tag, setup in matrix:
        print(f"\n=== {tag} ===")
        r = run_variant(tag, api_key, setup)
        results.append(r)
        if r["error"]:
            print(f"  setup/turn ERROR: {r['error']}")
        else:
            print(f"  setupComplete: YES | audio={r['audio']}B toolCalls={r['tool_calls'] or '-'} "
                  f"turnComplete={r['turn_complete']}")
            if r["text"]:
                print(f"  model text: {r['text'][:120]!r}")
            if r["transcript_out"]:
                print(f"  outputTranscription: {r['transcript_out'][:120]!r}")
        time.sleep(1)

    print("\n" + "=" * 74)
    print("VERDICT SUMMARY (setupComplete / response received)")
    print("=" * 74)
    for r in results:
        verdict = "OK" if (r["setup_ok"] and (r["audio"] or r["tool_calls"] or r["turn_complete"])) \
            else ("SETUP-REJECTED" if not r["setup_ok"] else "NO-RESPONSE")
        print(f"  [{verdict:15s}] {r['tag']}")
        if r["error"]:
            print(f"{'':19s}-> {r['error'][:160]}")

    critical_fail = [r for r in results[:2] if not r["setup_ok"]]
    sys.exit(1 if critical_fail else 0)


if __name__ == "__main__":
    main()
