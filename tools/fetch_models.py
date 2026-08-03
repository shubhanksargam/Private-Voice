#!/usr/bin/env python3
"""Download pre-exported Whisper ONNX models and push them to the device.

These come from the sherpa-onnx author's Hugging Face repos, already exported to
the encoder/decoder/tokens layout sherpa-onnx expects, in both full and int8
precision. Downloading them here is fine — this is a desktop dev tool. The *app*
never downloads anything; it holds no INTERNET permission.

    python tools/fetch_models.py                # download only
    python tools/fetch_models.py --push         # download + adb push
    python tools/fetch_models.py --models base  # just one

For M0 the sweep is tiny/base/small. Anything larger than small is not worth
downloading until the benchmark says small itself is fast enough on the A35.
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
# Internal storage, relative to the app's own files/ dir. See
# _adb.push_dir_to_app_storage for why not external storage.

# Hugging Face repos, keyed by the local directory name we give them.
MODELS = {
    "tiny": "csukuangfj/sherpa-onnx-whisper-tiny",
    "base": "csukuangfj/sherpa-onnx-whisper-base",
    "small": "csukuangfj/sherpa-onnx-whisper-small",
}

# Per model, the four weight files plus tokens. int8 first: it is the variant we
# actually expect to ship, so a partial download still leaves something runnable.
FILES = [
    "{n}-encoder.int8.onnx",
    "{n}-decoder.int8.onnx",
    "{n}-encoder.onnx",
    "{n}-decoder.onnx",
    "{n}-tokens.txt",
]

ROOT = Path(__file__).resolve().parent.parent
MODELS_DIR = ROOT / "models"


def download(repo: str, remote: str, dest: Path) -> bool:
    if dest.exists() and dest.stat().st_size > 0:
        print(f"    {dest.name}  (cached)")
        return True

    url = f"https://huggingface.co/{repo}/resolve/main/{remote}"
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    # Carriage-return progress is unreadable when piped to a log or a tool, so
    # only animate on a real terminal.
    interactive = sys.stdout.isatty()
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "privatevoice-fetch"})
        with urllib.request.urlopen(req, timeout=300) as resp, tmp.open("wb") as out:
            total = int(resp.headers.get("Content-Length") or 0)
            done = 0
            while chunk := resp.read(1 << 20):
                out.write(chunk)
                done += len(chunk)
                if total and interactive:
                    pct = 100 * done / total
                    print(f"\r    {dest.name}  {done/1e6:6.1f}/{total/1e6:.1f} MB ({pct:3.0f}%)",
                          end="", flush=True)
        # Only move into place once complete, so an interrupted run does not
        # leave a truncated .onnx that fails cryptically on device.
        tmp.replace(dest)
        prefix = "\r" if interactive else ""
        print(f"{prefix}    {dest.name}  {dest.stat().st_size/1e6:.1f} MB")
        return True
    except (urllib.error.HTTPError, urllib.error.URLError) as e:
        tmp.unlink(missing_ok=True)
        print(f"\r    {dest.name}  FAILED: {e}")
        return False


def adb(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run([ADB, *args], capture_output=True, text=True)


def ensure_device() -> None:
    try:
        probe = adb("devices")
    except FileNotFoundError:
        raise SystemExit(f"adb not found at {ADB!r}. See docs/SETUP.md.") from None
    if probe.returncode != 0:
        raise SystemExit(f"adb devices failed:\n{probe.stderr}")
    lines = [l for l in probe.stdout.splitlines()[1:] if l.strip()]
    attached = [l for l in lines if l.endswith("device")]
    if not attached:
        raise SystemExit(
            "No device in 'device' state.\n"
            "Enable USB debugging on the A35 and accept the RSA prompt.\n"
            f"adb devices said:\n{probe.stdout}"
        )
    if len(attached) > 1:
        raise SystemExit(f"Multiple devices attached; disconnect all but one:\n{probe.stdout}")


def push(names: list[str]) -> None:
    ensure_device()

    for name in names:
        local = MODELS_DIR / name
        if not local.is_dir():
            print(f"  skip {name}: not downloaded")
            continue
        print(f"  staging {name} ({sum(f.stat().st_size for f in local.iterdir())/1e6:.0f} MB)...")
        push_dir_to_app_storage(APP_ID, local, f"models/{name}")

    print(f"\nModels are on device at {APP_ID}'s internal files/models/")
    print("Push eval audio too: python tools/prepare_audio.py --src <folder> --push")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--models", nargs="+", choices=sorted(MODELS), default=sorted(MODELS))
    ap.add_argument("--push", action="store_true", help="adb push after downloading")
    ap.add_argument("--int8-only", action="store_true",
                    help="skip full-precision weights (roughly a third of the download)")
    args = ap.parse_args()

    wanted = [f for f in FILES if not (args.int8_only and ".int8." not in f and f.endswith(".onnx"))]

    ok = True
    for name in args.models:
        repo = MODELS[name]
        print(f"\n{name}  <- {repo}")
        for pattern in wanted:
            remote = pattern.format(n=name)
            ok &= download(repo, remote, MODELS_DIR / name / remote)

    if not ok:
        print("\nSome downloads failed; re-run to retry (completed files are cached).")
        return 1

    print(f"\nModels in {MODELS_DIR}")
    if args.push:
        print()
        push(args.models)
    return 0


if __name__ == "__main__":
    sys.exit(main())
