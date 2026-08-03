#!/usr/bin/env python3
"""Transcribe the eval corpus with whisper.cpp's desktop CLI.

Purpose: settle model-size and quantization choices on a laptop, before
spending build-and-flash cycles on the phone. whisper.cpp's decoder is the
same code that will run on-device, so transcripts here match what the Android
backend will produce — only the timings differ. Latency still has to be
measured on the A35 itself (see tools/bench_device.py).

    python tools/wcpp_desktop_eval.py --model .tmp_whispercpp/ggml-small-q5_1.bin
    python tools/eval_wer.py --hyp eval/wcpp_small-q5_1.jsonl --refs eval/refs
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
AUDIO = ROOT / "eval" / "audio"
CLI_GLOB = ".tmp_whispercpp/bin/**/whisper-cli.exe"


def find_cli(explicit: str | None) -> Path:
    if explicit:
        p = Path(explicit)
        if not p.exists():
            raise SystemExit(f"whisper-cli not found at {p}")
        return p
    hits = sorted(ROOT.glob(CLI_GLOB))
    if not hits:
        raise SystemExit(
            "whisper-cli.exe not found. Download the prebuilt binary:\n"
            "  https://github.com/ggml-org/whisper.cpp/releases  (whisper-bin-x64.zip)\n"
            "and extract into .tmp_whispercpp/bin/"
        )
    return hits[0]


def language_for(stem: str) -> str:
    # Matches BenchmarkRunner.languageForFile on the Android side: Whisper has
    # no code-switched tag, so Hinglish uses "hi" — its Hindi decoder tolerates
    # embedded English far better than the reverse.
    return "hi" if stem.startswith(("hi_", "mix_")) else "en"


def transcribe(cli: Path, model: Path, wav: Path, lang: str, threads: int) -> str:
    r = subprocess.run(
        [str(cli), "-m", str(model), "-f", str(wav), "-l", lang,
         "-nt", "-np", "-t", str(threads)],
        capture_output=True,
    )
    if r.returncode != 0:
        raise SystemExit(f"whisper-cli failed on {wav.name}:\n{r.stderr.decode(errors='replace')}")
    # -nt/-np suppress timestamps and progress, but the CLI still prints a
    # leading space and may emit blank lines; collapse to a single line.
    text = r.stdout.decode("utf-8", errors="replace")
    return re.sub(r"\s+", " ", text).strip()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", required=True, help="path to a ggml-*.bin")
    ap.add_argument("--cli", help="path to whisper-cli.exe (auto-detected if omitted)")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--out", type=Path, help="output .jsonl (defaults to eval/wcpp_<model>.jsonl)")
    args = ap.parse_args()

    cli = find_cli(args.cli)
    model = Path(args.model)
    if not model.exists():
        raise SystemExit(f"model not found: {model}")

    tag = model.stem.replace("ggml-", "")
    out = args.out or ROOT / "eval" / f"wcpp_{tag}.jsonl"

    wavs = sorted(AUDIO.glob("*.wav"))
    if not wavs:
        raise SystemExit(f"No WAVs in {AUDIO}")

    print(f"model : {model.name}")
    print(f"cli   : {cli}")
    print(f"files : {len(wavs)}\n")

    with out.open("w", encoding="utf-8") as f:
        for i, wav in enumerate(wavs, 1):
            lang = language_for(wav.stem)
            text = transcribe(cli, model, wav, lang, args.threads)
            f.write(json.dumps({"file": wav.name, "text": text}, ensure_ascii=False) + "\n")
            print(f"[{i:>2}/{len(wavs)}] {wav.stem:<10} {text[:62]}")

    print(f"\nWrote {out}")
    print(f"Score it:  python tools/eval_wer.py --hyp {out.relative_to(ROOT)} --refs eval/refs")
    return 0


if __name__ == "__main__":
    sys.exit(main())
