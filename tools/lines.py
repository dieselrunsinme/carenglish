#!/usr/bin/env python3
"""Checks every episode and lists its lines.

    python3 tools/lines.py            # check episodes, print block lengths
    python3 tools/lines.py --csv      # every fixed line with the file name a recording should have

A recording dropped into app/src/main/assets/voice/<file>.(mp3|m4a|ogg|wav) is played instead of synthesis.
The file name is <en|ko>_<first 10 hex of SHA-1 of the line>, the same rule as ClipVoice.key in the app.
Lines with {name}, {ko} or {call} change per child and are always synthesised.
"""
import glob
import hashlib
import json
import os
import sys

ROOT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "episodes")
TYPES = {"HELLO", "GREETING", "REVIEW", "EXPRESSION", "CHANT", "ROLEPLAY", "STORY", "QUIZ", "SURPRISE", "WRAPUP"}
KINDS = {"en", "ko", "echo", "ask", "card", "pause", "sfx"}
SOUNDS = {"ding", "chime", "yay", "pop", "beat"}


def speech(text, lang):
    """Same as Estimate.speech in the app."""
    if lang == "en":
        return 0.6 + len([w for w in text.replace("\n", " ").split(" ") if w.strip()]) * 0.45
    return 0.6 + sum(1 for c in text if c.isalnum()) * 0.19


def step_sec(s):
    if "en" in s:
        return speech(s["en"], "en")
    if "ko" in s:
        return speech(s["ko"], "ko")
    if "echo" in s:
        return speech(s["echo"], "en") * 2 + 0.8
    if "ask" in s:
        return s.get("wait", 5.0) + 3.0
    if "pause" in s:
        return s["pause"]
    if "sfx" in s:
        return 0.6
    return 0.0


def key(text, lang):
    return f"{lang}_" + hashlib.sha1(text.strip().encode("utf-8")).hexdigest()[:10]


def check(path):
    problems = []
    e = json.load(open(path, encoding="utf-8"))
    words = {w["id"] for w in e["words"]}
    types = [b["type"] for b in e["blocks"]]
    for need in ("EXPRESSION", "WRAPUP"):
        if need not in types:
            problems.append(f"{need} block missing")
    for b in e["blocks"]:
        if b["type"] not in TYPES:
            problems.append(f"unknown block {b['type']}")
        for s in b["steps"]:
            kinds = KINDS & set(s)
            if len(kinds) != 1:
                problems.append(f"step needs exactly one kind: {s}")
            if "card" in s and s["card"] not in words:
                problems.append(f"card for unknown word {s['card']}")
            if "word" in s and s["word"] not in words:
                problems.append(f"ask for unknown word {s['word']}")
            if "sfx" in s and s["sfx"] not in SOUNDS:
                problems.append(f"unknown sound {s['sfx']}")
            if "ask" in s and not s.get("accept"):
                problems.append(f"ask without accept: {s['ask']}")
    return e, problems


def main():
    files = sorted(glob.glob(os.path.join(ROOT, "*.json")))
    if "--csv" in sys.argv:
        print("file,lang,text")
        for f in files:
            e = json.load(open(f, encoding="utf-8"))
            for b in e["blocks"]:
                for s in b["steps"]:
                    for kind, lang in (("en", "en"), ("ko", "ko"), ("echo", "en")):
                        if kind in s and not any(t in s[kind] for t in ("{name}", "{ko}", "{call}")):
                            text = s[kind].replace('"', '""')
                            print(f'{key(s[kind], lang)},{lang},"{text}"')
        return
    bad = 0
    for f in files:
        e, problems = check(f)
        total = sum(step_sec(s) for b in e["blocks"] if not b.get("extra") for s in b["steps"])
        print(f"{e['id']} {e['title']} (Level {e['level']}) ~{total / 60:.1f} min without extras")
        for b in e["blocks"]:
            sec = sum(step_sec(s) for s in b["steps"])
            tag = " extra" if b.get("extra") else (f" drop {b['drop']}" if b.get("drop") else "")
            print(f"   {b['type']:<10} {sec:6.0f}s{tag}")
        for p in problems:
            print("   ! " + p)
        bad += len(problems)
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
