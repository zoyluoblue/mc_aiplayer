#!/usr/bin/env python3
"""Generate a natural MALE English voiceover for the AIBot promo using Kokoro (open-source TTS).

One WAV per narration line -> promo/tiktok/public/vo/lineN.wav.
The Remotion project plays each clip at its caption's start frame (see src/Video.tsx),
so the spoken audio stays in sync with the on-screen English captions.

Run (inside the kokoro venv):
    /tmp/ttsenv/bin/python promo/tiktok/tts/gen_voiceover.py
"""
import os
import soundfile as sf
from kokoro import KPipeline

# Voice: am_michael = natural US male. Alternatives: am_adam, am_onyx, bm_george (UK).
VOICE = "am_michael"
SPEED = 1.06  # natural pace, trimmed copy fits ~30s continuous
SAMPLE_RATE = 24000

# Lines mirror src/captions.ts (English only). Keep each within its scene window.
LINES = [
    ("line1", "Most A.I. in games is just a chat box. It talks."),
    ("line2", "Bob doesn't talk. Bob plays."),
    ("line3", "You just talk to it, in plain words."),
    ("line4", "A language model plans. A state machine executes."),
    ("line5", "It back-chains everything: wood, stone, ore, furnace."),
    ("line6", "Then it dives to the diamond layer, on real terrain."),
    ("line7", "It seals lava, walls off mobs, recovers its gear."),
    ("line8", "You say it. It does it. And it keeps getting sharper."),
    ("line9", "Made by zuuzii."),
]

OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "public", "vo")
os.makedirs(OUT_DIR, exist_ok=True)


def main() -> None:
    pipeline = KPipeline(lang_code="a")  # 'a' = American English
    for name, text in LINES:
        audio_chunks = []
        for _, _, audio in pipeline(text, voice=VOICE, speed=SPEED):
            audio_chunks.append(audio)
        if not audio_chunks:
            print(f"!! {name}: no audio produced")
            continue
        import numpy as np
        wav = np.concatenate([c for c in audio_chunks])
        path = os.path.join(OUT_DIR, f"{name}.wav")
        sf.write(path, wav, SAMPLE_RATE)
        dur = len(wav) / SAMPLE_RATE
        print(f"ok {name}: {dur:.2f}s -> {path}")


if __name__ == "__main__":
    main()
