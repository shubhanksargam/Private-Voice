#!/usr/bin/env python3
"""Vendor sherpa-onnx into the Android project.

sherpa-onnx publishes no official AAR or Maven artifact. Integration is manual:
prebuilt JNI shared objects plus their Kotlin API sources copied into your source
tree. There *is* a third-party repackage on Maven Central (com.bihe0832.android),
but taking an unaudited third-party binary into an app whose entire premise is
"your audio cannot leave the device" would be the wrong trade. So we pull the
official release artifacts and vendor them explicitly.

Run once after cloning:

    python tools/setup_sherpa.py

Everything it writes is gitignored; re-run to upgrade by bumping --version.
"""

from __future__ import annotations

import argparse
import io
import json
import shutil
import sys
import tarfile
import urllib.error
import urllib.request
from pathlib import Path

DEFAULT_VERSION = "1.13.4"
ABI = "arm64-v8a"

REPO = "k2-fsa/sherpa-onnx"
RELEASE_URL = "https://github.com/{repo}/releases/download/v{v}/sherpa-onnx-v{v}-android.tar.bz2"
CONTENTS_API = "https://api.github.com/repos/{repo}/contents/sherpa-onnx/kotlin-api?ref=v{v}"

ROOT = Path(__file__).resolve().parent.parent
ENGINE = ROOT / "android" / "engine" / "src" / "main"
JNI_DIR = ENGINE / "jniLibs" / ABI
VENDOR_ROOT = ENGINE / "vendor"
VENDOR_DIR = VENDOR_ROOT / "com" / "k2fsa" / "sherpa" / "onnx"


def fetch(url: str, desc: str) -> bytes:
    print(f"  fetching {desc} ...", end=" ", flush=True)
    req = urllib.request.Request(url, headers={"User-Agent": "privatevoice-setup"})
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            data = resp.read()
    except urllib.error.HTTPError as e:
        print("FAILED")
        raise SystemExit(f"HTTP {e.code} for {url}\n{e.reason}") from e
    except urllib.error.URLError as e:
        print("FAILED")
        raise SystemExit(f"Network error for {url}: {e.reason}") from e
    print(f"{len(data) / 1e6:.1f} MB")
    return data


def install_native_libs(version: str) -> None:
    """Extract libsherpa-onnx-jni.so and libonnxruntime.so for our ABI."""
    url = RELEASE_URL.format(repo=REPO, v=version)
    blob = fetch(url, f"sherpa-onnx v{version} android libs")

    JNI_DIR.mkdir(parents=True, exist_ok=True)
    found = []
    with tarfile.open(fileobj=io.BytesIO(blob), mode="r:bz2") as tar:
        for member in tar.getmembers():
            if not member.isfile() or not member.name.endswith(".so"):
                continue
            # Archive layout is jniLibs/<abi>/<lib>.so; take only our ABI.
            if f"/{ABI}/" not in member.name:
                continue
            src = tar.extractfile(member)
            if src is None:
                continue
            dest = JNI_DIR / Path(member.name).name
            dest.write_bytes(src.read())
            found.append(dest.name)

    if not found:
        raise SystemExit(
            f"No {ABI} .so files found in the release archive. "
            f"The layout may have changed — inspect {url} by hand."
        )
    for name in sorted(found):
        size = (JNI_DIR / name).stat().st_size / 1e6
        print(f"    {name}  {size:.1f} MB")


def install_kotlin_api(version: str) -> None:
    """Copy the official Kotlin bindings into engine/src/main/vendor.

    We take the whole kotlin-api directory rather than cherry-picking files: the
    sources reference each other freely, and a partial copy fails to compile in
    ways that are tedious to chase.
    """
    listing_url = CONTENTS_API.format(repo=REPO, v=version)
    listing = json.loads(fetch(listing_url, "kotlin-api file listing"))

    VENDOR_DIR.mkdir(parents=True, exist_ok=True)
    count = 0
    for entry in listing:
        if entry.get("type") != "file" or not entry["name"].endswith(".kt"):
            continue
        data = fetch(entry["download_url"], entry["name"])
        (VENDOR_DIR / entry["name"]).write_bytes(data)
        count += 1

    if count == 0:
        raise SystemExit("No .kt files found in kotlin-api — check the tag exists.")
    print(f"    {count} Kotlin sources -> {VENDOR_DIR.relative_to(ROOT)}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--version", default=DEFAULT_VERSION, help=f"sherpa-onnx release (default {DEFAULT_VERSION})")
    ap.add_argument("--clean", action="store_true", help="remove vendored files before installing")
    args = ap.parse_args()

    if args.clean:
        for d in (JNI_DIR.parent, VENDOR_ROOT):
            if d.exists():
                print(f"  removing {d.relative_to(ROOT)}")
                shutil.rmtree(d)

    print(f"Vendoring sherpa-onnx v{args.version} ({ABI})")
    install_native_libs(args.version)
    install_kotlin_api(args.version)

    print("\nDone. Next:")
    print("  python tools/fetch_models.py --push")
    return 0


if __name__ == "__main__":
    sys.exit(main())
