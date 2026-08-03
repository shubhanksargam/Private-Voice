#!/usr/bin/env python3
"""Download GGML Whisper weights and push them to the device.

GGML (whisper.cpp) rather than ONNX (sherpa-onnx), because sherpa-onnx's
detokenizer drops partial-UTF-8 tokens and destroys Devanagari output — see
docs/M0_RESULTS.md.

One file per model here, versus ONNX's encoder/decoder/tokens triple. GGML is
also markedly smaller for the same weights, because it quantizes the
token-embedding matrix that the sherpa-onnx export left in fp32:

    base    153MB (ONNX int8)  ->   57MB (GGML q5_1)
    small   358MB (ONNX int8)  ->  181MB (GGML q5_1)

    python tools/fetch_ggml_models.py                 # download all
    python tools/fetch_ggml_models.py --push          # download + push
    python tools/fetch_ggml_models.py --models small  # just one
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

from _adb import ADB, push_dir_to_app_storage

APP_ID = "dev.privatevoice.app"
HF_REPO = "ggerganov/whisper.cpp"

# q5_1 is the accuracy/size sweet spot for on-device Whisper and what the
# desktop evaluation used. q8_0 is listed as a higher-accuracy fallback if
# latency headroom turns out to allow it.
QUANTS = {
    "q5_1": "ggml-{n}-q5_1.bin",
    "q8_0": "ggml-{n}-q8_0.bin",
}
MODELS = ["tiny", "base", "small"]

ROOT = Path(__file__).resolve().parent.parent
GGML_DIR = ROOT / "models_ggml"


def download(remote: str, dest: Path) -> bool:
    if dest.exists() and dest.stat().st_size > 0:
        print(f"    {dest.name}  (cached)")
        return True

    url = f"https://huggingface.co/{HF_REPO}/resolve/main/{remote}"
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    interactive = sys.stdout.isatty()
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "privatevoice-fetch"})
        with urllib.request.urlopen(req, timeout=600) as resp, tmp.open("wb") as out:
            total = int(resp.headers.get("Content-Length") or 0)
            done = 0
            while chunk := resp.read(1 << 20):
                out.write(chunk)
                done += len(chunk)
                if total and interactive:
                    print(f"\r    {dest.name}  {done/1e6:6.1f}/{total/1e6:.1f} MB", end="", flush=True)
        # Only move into place once complete, so an interrupted run doesn't
        # leave a truncated .bin that fails cryptically on device.
        tmp.replace(dest)
        prefix = "\r" if interactive else ""
        print(f"{prefix}    {dest.name}  {dest.stat().st_size/1e6:.1f} MB")
        return True
    except (urllib.error.HTTPError, urllib.error.URLError) as e:
        tmp.unlink(missing_ok=True)
        print(f"\r    {dest.name}  FAILED: {e}")
        return False


def ensure_device() -> None:
    try:
        probe = subprocess.run([ADB, "devices"], capture_output=True, text=True)
    except FileNotFoundError:
        raise SystemExit(f"adb not found at {ADB!r}. See docs/SETUP.md.") from None
    attached = [l for l in probe.stdout.splitlines()[1:] if l.strip().endswith("device")]
    if not attached:
        raise SystemExit(
            "No device in 'device' state.\n"
            "Enable USB debugging on the A35 and accept the RSA prompt.\n"
            f"adb devices said:\n{probe.stdout}"
        )


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--models", nargs="+", choices=MODELS, default=MODELS)
    ap.add_argument("--quant", nargs="+", choices=sorted(QUANTS), default=["q5_1"])
    ap.add_argument("--push", action="store_true", help="push to the device after downloading")
    args = ap.parse_args()

    ok = True
    for name in args.models:
        print(f"\n{name}")
        for q in args.quant:
            remote = QUANTS[q].format(n=name)
            ok &= download(remote, GGML_DIR / remote)

    if not ok:
        print("\nSome downloads failed; re-run to retry (completed files are cached).")
        return 1

    total = sum(f.stat().st_size for f in GGML_DIR.glob("*.bin"))
    print(f"\n{GGML_DIR}  ({total/1e6:.0f} MB total)")

    if args.push:
        print()
        ensure_device()
        push_dir_to_app_storage(APP_ID, GGML_DIR, "ggml")
        print(f"Pushed to {APP_ID}'s internal files/ggml/")
        print("\nNext: python tools/bench_device.py")
    return 0


if __name__ == "__main__":
    sys.exit(main())
