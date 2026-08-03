#!/usr/bin/env python3
"""Convert recordings to the 16kHz mono 16-bit WAV the models require.

    python tools/prepare_audio.py --src "C:\\Users\\me\\Recordings"
    python tools/prepare_audio.py --src recordings --push

Phone recorders produce .m4a/.opus/.amr at 44.1kHz stereo. Whisper wants 16kHz
mono PCM16, and WavIo.kt deliberately refuses anything else rather than silently
misreading it — a resampling bug that halves your pitch is far easier to catch at
conversion time than as mysteriously bad WER.

Filenames must match the prompt ids (en_001, hi_004, mix_012); everything
downstream keys off that convention.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

from _adb import ADB

ROOT = Path(__file__).resolve().parent.parent
PROMPTS = ROOT / "eval" / "prompts.jsonl"
OUT = ROOT / "eval" / "audio"

APP_ID = "dev.privatevoice.app"
DEVICE_EVAL = f"/sdcard/Android/data/{APP_ID}/files/eval"

AUDIO_EXT = {".m4a", ".mp3", ".wav", ".opus", ".ogg", ".amr", ".aac", ".flac", ".3gp", ".mp4"}


def require_ffmpeg() -> str:
    exe = shutil.which("ffmpeg")
    if not exe:
        raise SystemExit(
            "ffmpeg not found on PATH.\n\n"
            "Install it with:\n"
            "    winget install Gyan.FFmpeg\n\n"
            "Then open a new terminal so PATH refreshes."
        )
    return exe


def known_ids() -> set[str]:
    if not PROMPTS.exists():
        return set()
    return {
        json.loads(l)["id"]
        for l in PROMPTS.read_text(encoding="utf-8").splitlines()
        if l.strip()
    }


def convert(ffmpeg: str, src: Path, dest: Path) -> bool:
    # -ac 1 mono, -ar 16000, -sample_fmt s16, and strip any metadata so the WAV
    # is a plain RIFF the Kotlin reader will accept without chunk surprises.
    cmd = [
        ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
        "-i", str(src),
        "-ac", "1", "-ar", "16000", "-sample_fmt", "s16",
        "-map_metadata", "-1",
        str(dest),
    ]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        print(f"  {src.name}  FAILED: {r.stderr.strip().splitlines()[-1] if r.stderr else '?'}")
        return False
    return True


def push() -> None:
    subprocess.run([ADB, "shell", "mkdir", "-p", DEVICE_EVAL], capture_output=True)
    files = sorted(OUT.glob("*.wav"))
    for f in files:
        r = subprocess.run([ADB, "push", str(f), f"{DEVICE_EVAL}/{f.name}"],
                           capture_output=True, text=True)
        if r.returncode != 0:
            print(f"  push {f.name} FAILED: {r.stderr.strip()}")
    print(f"Pushed {len(files)} file(s) to {DEVICE_EVAL}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--src", type=Path, required=True, help="folder of recordings")
    ap.add_argument("--push", action="store_true", help="adb push the converted WAVs")
    args = ap.parse_args()

    if not args.src.is_dir():
        raise SystemExit(f"Not a directory: {args.src}")

    ffmpeg = require_ffmpeg()
    OUT.mkdir(parents=True, exist_ok=True)
    ids = known_ids()

    sources = [p for p in sorted(args.src.iterdir())
               if p.is_file() and p.suffix.lower() in AUDIO_EXT]
    if not sources:
        raise SystemExit(f"No audio files in {args.src}")

    converted, unknown = 0, []
    for src in sources:
        stem = src.stem
        if ids and stem not in ids:
            unknown.append(stem)
            continue
        dest = OUT / f"{stem}.wav"
        if convert(ffmpeg, src, dest):
            size_kb = dest.stat().st_size / 1024
            print(f"  {stem}.wav  {size_kb:.0f} KB")
            converted += 1

    print(f"\nConverted {converted} file(s) -> {OUT.relative_to(ROOT)}")

    if unknown:
        print(f"\nSkipped {len(unknown)} file(s) whose names match no prompt id:")
        for u in unknown[:10]:
            print(f"  {u}")
        print("Rename them to the prompt ids (en_001, hi_004, mix_012, ...).")

    if ids:
        missing = sorted(ids - {p.stem for p in OUT.glob("*.wav")})
        if missing:
            print(f"\nStill to record ({len(missing)}): {', '.join(missing[:12])}"
                  + (" ..." if len(missing) > 12 else ""))
        else:
            print("\nAll prompts recorded.")

    if args.push:
        print()
        push()
    return 0


if __name__ == "__main__":
    sys.exit(main())
