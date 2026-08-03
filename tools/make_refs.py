#!/usr/bin/env python3
"""Expand eval/prompts.jsonl into reference transcripts, and print a recording script.

    python tools/make_refs.py              # write refs, print what to read aloud
    python tools/make_refs.py --sheet      # only print the recording sheet

Two reference sets are produced, because for code-switched Hinglish the "correct"
output script is genuinely ambiguous and we should measure rather than assume:

    eval/refs/       Devanagari for hi/mix  — what Whisper emits with language=hi
    eval/refs_latn/  Latin for hi/mix       — how people actually type Hinglish

Score against both and compare:

    python tools/eval_wer.py --refs eval/refs
    python tools/eval_wer.py --refs eval/refs_latn

If the model wins on Devanagari but you want Latin output, that is a
transliteration post-processing problem, not an ASR accuracy problem — and the
two runs are what tell those apart.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROMPTS = ROOT / "eval" / "prompts.jsonl"
REFS = ROOT / "eval" / "refs"
REFS_LATN = ROOT / "eval" / "refs_latn"

SPLIT_LABEL = {
    "en": "Indian English",
    "hi": "Hindi",
    "mix": "Hinglish (code-switched)",
}


def load() -> list[dict]:
    if not PROMPTS.exists():
        raise SystemExit(f"Missing {PROMPTS}")
    rows = []
    for n, line in enumerate(PROMPTS.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            rec = json.loads(line)
        except json.JSONDecodeError as e:
            raise SystemExit(f"{PROMPTS}:{n}: {e}") from e
        for field in ("id", "prompt", "ref"):
            if field not in rec:
                raise SystemExit(f"{PROMPTS}:{n}: missing '{field}'")
        rows.append(rec)
    return rows


def write_refs(rows: list[dict]) -> None:
    REFS.mkdir(parents=True, exist_ok=True)
    REFS_LATN.mkdir(parents=True, exist_ok=True)
    n_alt = 0
    for rec in rows:
        (REFS / f"{rec['id']}.txt").write_text(rec["ref"] + "\n", encoding="utf-8")
        # en has no romanised variant — it is already Latin, so reuse it and keep
        # both reference sets complete enough to score as a whole corpus.
        alt = rec.get("ref_latn") or (rec["ref"] if rec["id"].startswith("en_") else None)
        if alt:
            (REFS_LATN / f"{rec['id']}.txt").write_text(alt + "\n", encoding="utf-8")
            n_alt += 1
    print(f"Wrote {len(rows)} refs -> {REFS.relative_to(ROOT)}")
    print(f"Wrote {n_alt} refs -> {REFS_LATN.relative_to(ROOT)}")


def print_sheet(rows: list[dict]) -> None:
    by_split: dict[str, list[dict]] = {}
    for rec in rows:
        by_split.setdefault(rec["id"].split("_", 1)[0], []).append(rec)

    print()
    print("=" * 72)
    print("RECORDING SHEET")
    print("=" * 72)
    print()
    print("Record one file per line, named <id>.wav (or .m4a — prepare_audio.py")
    print("converts). Speak the way you would actually dictate a message:")
    print()
    print("  - Normal pace. Do not over-enunciate; that flatters the model and")
    print("    gives you a benchmark that lies about real use.")
    print("  - Do NOT say punctuation out loud. We are testing whether the model")
    print("    infers it, which is the whole reason we chose Whisper.")
    print("  - Record somewhere ordinary — a room with some ambience beats a")
    print("    silent booth, because that is where you will use this.")
    print("  - A couple of seconds of silence at each end is fine; VAD trims it.")
    print()

    for split in ("en", "hi", "mix"):
        if split not in by_split:
            continue
        print("-" * 72)
        print(f"{SPLIT_LABEL[split]}  ({len(by_split[split])} utterances)")
        print("-" * 72)
        for rec in by_split[split]:
            print(f"  {rec['id']}   {rec['prompt']}")
        print()

    print("=" * 72)
    print("Then:")
    print("  python tools/prepare_audio.py --src <folder-with-recordings>")
    print("  python tools/fetch_models.py --push")
    print("  python tools/bench_device.py")
    print("=" * 72)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--sheet", action="store_true", help="print the sheet only, write nothing")
    args = ap.parse_args()

    rows = load()
    if not args.sheet:
        write_refs(rows)
    print_sheet(rows)
    return 0


if __name__ == "__main__":
    sys.exit(main())
