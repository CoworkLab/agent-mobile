#!/usr/bin/env python3
"""
Verify every model the app offers in its Gemini Provider settings, against the
REAL Gemini Live API, from a non-geo-blocked runner:

  1. REST: list every model the key can see that supports bidiGenerateContent
  2. WS  : for each app model (gemini-3.1-flash-live-preview, gemini-3.8-live,
           gemini-3.1-flash-agent) run a full live turn (setup -> text ->
           audio/transcript) and report PASS/FAIL.

Usage:
  GEMINI_API_KEY=AIza... python3 scripts/verify_app_models.py
Exit 0 = default model passed (critical); 1 = default model failed.
Non-default models are reported but do not fail the build (app auto-falls-back).
"""
import json
import os
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gemini_live_protocol_test import (  # noqa: E402
    build_setup, run_variant, decl,
)

APP_MODELS = [
    ("models/gemini-3.1-flash-live-preview", True),   # Part 7 default, CRITICAL
    ("models/gemini-3.8-live", False),                # fallback
    ("models/gemini-3.8-live-extended-thinking", False),  # third radio option
    ("models/gemini-3.1-flash-agent", None),          # legacy dead ID: must FAIL (kept as tombstone check)
]

SIMPLE_TOOLS = [
    decl("open_app", "Opens an app installed on the user's phone.",
         {"app_name": {"type": "STRING", "description": "The app to open."}},
         ["app_name"]),
    decl("task_completed", "Call after the user's task is confirmed complete.",
         {"summary": {"type": "STRING", "description": "Brief summary."}},
         ["summary"]),
]


def main():
    api_key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not api_key:
        print("SKIP: GEMINI_API_KEY not set")
        sys.exit(2)

    # ---------- 1. REST model census ----------
    print("=== REST: models supporting bidiGenerateContent (Live API) ===")
    bidi = []
    try:
        req = urllib.request.Request(
            f"https://generativelanguage.googleapis.com/v1beta/models?key={api_key}&pageSize=200")
        with urllib.request.urlopen(req, timeout=25) as r:
            for m in json.load(r).get("models", []):
                if "bidiGenerateContent" in m.get("supportedGenerationMethods", []):
                    bidi.append(m.get("name", ""))
    except Exception as e:
        print(f"REST FAILED: {e}")
        sys.exit(1)

    for name in bidi:
        print(f"  LIVE-CAPABLE: {name}")
    if not bidi:
        print("  (none listed!)")

    # ---------- 2. WS live-turn verification per app model ----------
    # Free-tier Live API quota is tight: a session can be closed or silently
    # stalled for RPM reasons that have nothing to do with the model. So a
    # non-default model that fails WITHOUT a JSON error gets ONE retry after
    # a longer cooldown before we call it dead.
    def ws_turn(model, critical):
        print(f"\n=== WS turn test: {model} {'(CRITICAL default)' if critical else ''} ===")
        r = run_variant(model, api_key, build_setup(model, SIMPLE_TOOLS),
                        t_setup=30, t_turn=45)
        if critical:
            return r
        quota_signature = (not r["setup_ok"]) or (r["error"] is None)
        if quota_signature:
            print(f"  ambiguous failure ({r['error'] or 'connected but no response'}) -> quota? retrying once in 60s")
            time.sleep(60)
            r = run_variant(model, api_key, build_setup(model, SIMPLE_TOOLS),
                            t_setup=30, t_turn=45)
        return r

    results = {}
    for model, critical in APP_MODELS:
        r = ws_turn(model, critical)
        ok = r["setup_ok"] and (r["audio"] > 0 or r["turn_complete"])
        results[model] = (ok, critical, r)
        if ok:
            print(f"  PASS: audio={r['audio']}B turnComplete={r['turn_complete']} "
                  f"out={r['transcript_out'][:80]!r}")
        else:
            print(f"  FAIL: {r['error']}")
        time.sleep(40)  # respect free-tier Live RPM

    print("\n" + "=" * 70)
    print("APP MODEL VERDICTS")
    print("=" * 70)
    default_ok = None
    for model, (ok, critical, r) in results.items():
        verdict = "PASS" if ok else "FAIL"
        print(f"  [{verdict}] {model}"
              + ("  <- app default" if critical else ""))
        if not ok:
            print(f"          -> {r['error']}")
        if critical:
            default_ok = ok

    sys.exit(0 if default_ok else 1)


if __name__ == "__main__":
    main()
