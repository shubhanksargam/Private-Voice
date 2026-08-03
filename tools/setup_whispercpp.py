#!/usr/bin/env python3
"""Vendor whisper.cpp sources into the Android engine module.

Why whisper.cpp at all, when sherpa-onnx was already integrated: sherpa-onnx
converts each Whisper token to a string *individually* and concatenates the
results. Whisper uses byte-level BPE, so a token holds bytes, not necessarily
whole characters — Devanagari is 3 bytes/char and BPE splits it freely. Any
token carrying a partial UTF-8 sequence decodes to an empty string and is
silently dropped, destroying ~60% of Hindi output while leaving ASCII intact.
See docs/M0_RESULTS.md. whisper.cpp accumulates bytes across tokens and gets
this right.

Unlike sherpa-onnx (prebuilt .so), whisper.cpp is compiled from source by
Gradle/CMake via the NDK, so this script vendors the source tree rather than
binaries. Everything it writes is gitignored; re-run to upgrade by bumping
--version.

    python tools/setup_whispercpp.py
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

DEFAULT_VERSION = "v1.9.1"
REPO = "https://github.com/ggml-org/whisper.cpp"

ROOT = Path(__file__).resolve().parent.parent
CPP_DIR = ROOT / "android" / "engine" / "src" / "main" / "cpp" / "whispercpp"

# Only what the CMake build actually needs. The full repo is ~200MB with
# history, bindings, examples and test fixtures we don't compile.
SUBTREES = ["src", "include", "ggml"]
FILES = ["LICENSE"]


def run(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        raise SystemExit(f"{' '.join(cmd[:3])}... failed:\n{r.stderr}")
    return r


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--version", default=DEFAULT_VERSION, help=f"git tag (default {DEFAULT_VERSION})")
    ap.add_argument("--clean", action="store_true", help="remove vendored sources first")
    args = ap.parse_args()

    if args.clean and CPP_DIR.exists():
        print(f"  removing {CPP_DIR.relative_to(ROOT)}")
        shutil.rmtree(CPP_DIR)

    if not shutil.which("git"):
        raise SystemExit("git not found on PATH.")

    with tempfile.TemporaryDirectory() as tmp:
        clone = Path(tmp) / "whisper.cpp"
        print(f"Cloning whisper.cpp {args.version} (shallow)...")
        run(["git", "clone", "--depth", "1", "--branch", args.version, REPO, str(clone)])

        CPP_DIR.mkdir(parents=True, exist_ok=True)
        for name in SUBTREES:
            src, dst = clone / name, CPP_DIR / name
            if dst.exists():
                shutil.rmtree(dst)
            # Skip the vendored test suites and any stray .git metadata; they
            # roughly triple the payload and none of it is compiled.
            shutil.copytree(src, dst, ignore=shutil.ignore_patterns(
                ".git", "*.gguf", "*.bin", "tests", "test-*"))
            size = sum(f.stat().st_size for f in dst.rglob("*") if f.is_file())
            print(f"    {name:<10} {size / 1e6:6.1f} MB")

        for name in FILES:
            if (clone / name).exists():
                shutil.copy2(clone / name, CPP_DIR / name)

        (CPP_DIR / "VERSION.txt").write_text(f"{args.version}\n", encoding="utf-8")

    print(f"\nVendored to {CPP_DIR.relative_to(ROOT)}")
    print("The JNI bridge and CMakeLists live in the repo (engine/src/main/cpp/),")
    print("not here — they are ours, not upstream's.")
    print("\nNext:")
    print("  python tools/fetch_ggml_models.py --push")
    return 0


if __name__ == "__main__":
    sys.exit(main())
