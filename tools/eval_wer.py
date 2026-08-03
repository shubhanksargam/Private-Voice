#!/usr/bin/env python3
"""Devanagari-aware WER/CER scoring.

Off-the-shelf WER tools mangle Hindi. They treat the danda as a word, score the
precomposed and decomposed forms of nukta consonants as different characters, and
count Devanagari digits as distinct from ASCII ones — so identical-sounding
transcripts get penalised for orthographic choices no user cares about. This
normalises those away before scoring, and reports punctuation separately rather
than baking it into the headline number.

    python tools/eval_wer.py --hyp eval/benchmark.json
    python tools/eval_wer.py --hyp runs/whisper-small.jsonl --refs eval/refs

Naming convention drives the per-language breakdown: eval/audio/<split>_<id>.wav
with split in {en, hi, mix}, and a matching eval/refs/<split>_<id>.txt.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# U+0964 danda, U+0965 double danda, plus the usual Latin set.
PUNCT = re.compile(r"[।॥.,!?;:\"'`´‘’“”()\[\]{}<>«»…\-–—/\\|@#$%^&*_+=~]")
ZERO_WIDTH = re.compile(r"[​‌‍﻿]")
NUKTA = "़"
DEV_DIGITS = str.maketrans("०१२३४५६७८९", "0123456789")

# Anusvara vs chandrabindu is inconsistently transcribed by humans and models
# alike; folding is optional because it is a real distinction in some words.
ANUSVARA = "ं"
CHANDRABINDU = "ँ"


def normalise(text: str, keep_punct: bool = False, fold_anusvara: bool = False) -> str:
    text = unicodedata.normalize("NFC", text)
    text = ZERO_WIDTH.sub("", text)
    text = text.translate(DEV_DIGITS)

    # Decompose so precomposed nukta forms (क़, ज़, ड़ ...) and the explicit
    # base+nukta sequences collapse to one representation, then drop the nukta.
    text = unicodedata.normalize("NFD", text).replace(NUKTA, "")
    text = unicodedata.normalize("NFC", text)

    if fold_anusvara:
        text = text.replace(CHANDRABINDU, ANUSVARA)
    if not keep_punct:
        text = PUNCT.sub(" ", text)

    text = text.lower()
    return " ".join(text.split())


def levenshtein(a: list, b: list) -> int:
    if not a:
        return len(b)
    if not b:
        return len(a)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def load_hyps(path: Path, model_id: str | None = None) -> dict[str, str]:
    """Accept either a benchmark.json or a {"file","text"} JSONL."""
    # utf-8-sig transparently strips a BOM if present and is a no-op otherwise —
    # `adb exec-out`/PowerShell redirects routinely add one on Windows.
    if path.suffix == ".jsonl":
        out = {}
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            if line.strip():
                rec = json.loads(line)
                out[Path(rec["file"]).stem] = rec["text"]
        return out

    data = json.loads(path.read_text(encoding="utf-8-sig"))
    results = data.get("results", [])
    if not results:
        raise SystemExit(f"No results in {path}")

    if model_id:
        matches = [r for r in results if r.get("id") == model_id]
        if not matches:
            available = ", ".join(r.get("id", "?") for r in results)
            raise SystemExit(f"No config '{model_id}' in {path}. Available: {available}")
        chosen = matches[0]
    else:
        # A benchmark sweep holds many models. Default to the fastest passing
        # one — but say which, so the number is never ambiguous, and pass
        # --model to score a specific config (e.g. the gate winner, which
        # isn't always the fastest).
        passing = [r for r in results if r.get("verdict") == "PASS"] or results
        chosen = min(passing, key=lambda r: r.get("medianMillis") or 1 << 30)
    print(f"Scoring model: {chosen.get('id')}\n")
    return {Path(u["file"]).stem: u.get("text", "") for u in chosen.get("utterances", [])}


def load_refs(refs_dir: Path) -> dict[str, str]:
    if not refs_dir.is_dir():
        raise SystemExit(f"No reference directory at {refs_dir}")
    return {
        p.stem: p.read_text(encoding="utf-8").strip()
        for p in sorted(refs_dir.glob("*.txt"))
    }


def split_of(name: str) -> str:
    head = name.split("_", 1)[0].lower()
    return head if head in {"en", "hi", "mix"} else "other"


def score(hyps: dict[str, str], refs: dict[str, str], fold_anusvara: bool) -> None:
    shared = sorted(set(hyps) & set(refs))
    if not shared:
        raise SystemExit(
            "No overlap between hypotheses and references.\n"
            f"  hyp keys: {sorted(hyps)[:5]}\n"
            f"  ref keys: {sorted(refs)[:5]}"
        )

    missing = sorted(set(refs) - set(hyps))
    if missing:
        print(f"warning: {len(missing)} reference(s) with no hypothesis: {missing[:5]}\n")

    agg = defaultdict(lambda: {"we": 0, "wn": 0, "ce": 0, "cn": 0, "pe": 0, "pn": 0, "n": 0})

    for key in shared:
        hyp, ref = hyps[key], refs[key]
        sp = split_of(key)

        hw = normalise(hyp, fold_anusvara=fold_anusvara).split()
        rw = normalise(ref, fold_anusvara=fold_anusvara).split()
        hc = list(normalise(hyp, fold_anusvara=fold_anusvara).replace(" ", ""))
        rc = list(normalise(ref, fold_anusvara=fold_anusvara).replace(" ", ""))
        hp = normalise(hyp, keep_punct=True, fold_anusvara=fold_anusvara).split()
        rp = normalise(ref, keep_punct=True, fold_anusvara=fold_anusvara).split()

        for bucket in (agg[sp], agg["ALL"]):
            bucket["we"] += levenshtein(hw, rw); bucket["wn"] += len(rw)
            bucket["ce"] += levenshtein(hc, rc); bucket["cn"] += len(rc)
            bucket["pe"] += levenshtein(hp, rp); bucket["pn"] += len(rp)
            bucket["n"] += 1

    hdr = f"{'split':<8} {'n':>4} {'WER':>8} {'CER':>8} {'WER+punct':>10}"
    print(hdr)
    print("-" * len(hdr))
    for sp in [s for s in ("en", "hi", "mix", "other") if s in agg] + ["ALL"]:
        b = agg[sp]
        wer = 100 * b["we"] / b["wn"] if b["wn"] else float("nan")
        cer = 100 * b["ce"] / b["cn"] if b["cn"] else float("nan")
        pwer = 100 * b["pe"] / b["pn"] if b["pn"] else float("nan")
        print(f"{sp:<8} {b['n']:>4} {wer:>7.2f}% {cer:>7.2f}% {pwer:>9.2f}%")

    print()
    print("WER/CER ignore punctuation and casing. WER+punct includes them, so the")
    print("gap between the two columns is the cost of punctuation errors alone.")
    if "mix" in agg:
        print()
        print("The 'mix' row is the one that decides this project. Published")
        print("code-switch WER across models spans 27-70%; compare against your own")
        print("Google Voice Typing baseline on the same audio, not against papers.")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--hyp", type=Path, default=ROOT / "eval" / "benchmark.json")
    ap.add_argument("--refs", type=Path, default=ROOT / "eval" / "refs")
    ap.add_argument("--model", help="score this config id instead of the fastest PASS (e.g. base-int8-t4)")
    ap.add_argument("--fold-anusvara", action="store_true",
                    help="treat chandrabindu and anusvara as equivalent")
    args = ap.parse_args()

    if not args.hyp.exists():
        raise SystemExit(f"No hypotheses at {args.hyp}")
    score(load_hyps(args.hyp, args.model), load_refs(args.refs), args.fold_anusvara)
    return 0


if __name__ == "__main__":
    sys.exit(main())
