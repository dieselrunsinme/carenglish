#!/usr/bin/env python3
"""Records every English sentence of the app with Piper, into app/src/main/assets/voice.

The Pleos Connect emulator has no English text-to-speech, so English is shipped as recordings.

    pip install piper-tts            # plus ffmpeg on PATH
    python3 tools/make_voice.py SENTENCES.txt MODEL.onnx SPEAKER_ID

SENTENCES.txt is one sentence per line (Inventory.english in the app lists them). The voice is Piper's
en-us-libritts-high model, trained on LibriTTS (CC BY 4.0, http://www.openslr.org/60/).
File names follow ClipVoice.key: en_<first 10 hex of SHA-1 of the sentence>.m4a
"""
import hashlib
import os
import subprocess
import sys
import tempfile
import wave

from piper import PiperVoice
from piper.config import SynthesisConfig

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "voice")


def key(text: str) -> str:
    return "en_" + hashlib.sha1(text.strip().encode("utf-8")).hexdigest()[:10]


def main() -> None:
    sentences_path, model, speaker = sys.argv[1], sys.argv[2], int(sys.argv[3])
    voice = PiperVoice.load(model, config_path=model + ".json")
    # a little slower than adult speech, a little less random than the default
    config = SynthesisConfig(speaker_id=speaker, length_scale=1.12, noise_scale=0.6, noise_w_scale=0.7)
    os.makedirs(OUT, exist_ok=True)
    sentences = [s.strip() for s in open(sentences_path, encoding="utf-8") if s.strip()]
    with tempfile.TemporaryDirectory() as tmp:
        for n, text in enumerate(sentences, 1):
            wav = os.path.join(tmp, "s.wav")
            with wave.open(wav, "wb") as w:
                voice.synthesize_wav(text, w, syn_config=config)
            out = os.path.join(OUT, key(text) + ".m4a")
            # trim leading/trailing silence, mono AAC
            subprocess.run(
                ["ffmpeg", "-loglevel", "error", "-y", "-i", wav,
                 "-af", "silenceremove=start_periods=1:start_threshold=-45dB,areverse,"
                        "silenceremove=start_periods=1:start_threshold=-45dB,areverse,apad=pad_dur=0.08",
                 "-ac", "1", "-c:a", "aac", "-b:a", "48k", out],
                check=True,
            )
            if n % 50 == 0:
                print(f"{n}/{len(sentences)}", flush=True)
    print(f"done: {len(sentences)} recordings in {os.path.normpath(OUT)}")


if __name__ == "__main__":
    main()
