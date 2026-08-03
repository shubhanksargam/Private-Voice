#!/usr/bin/env python3
"""Drive the M0 benchmark on a connected device and tabulate the result.

    python tools/bench_device.py            # build, install, run, pull, tabulate
    python tools/bench_device.py --no-build # reuse the installed APK
    python tools/bench_device.py --report   # just re-tabulate the last pulled JSON

The gate this feeds: pick the largest model whose median stays under the latency
budget. If nothing passes, that is a real finding about the A35 and it should
change the plan rather than be optimised around quietly.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

from _adb import ADB

APP_ID = "dev.privatevoice.app"
ACTIVITY = f"{APP_ID}/.BenchmarkActivity"
# Internal storage (files/... relative to run-as's cwd), not external. This
# device's FUSE-mediated external/shared storage silently hides files written
# by a different UID from the app's own File.listFiles() — confirmed by
# writing there and finding the app simply couldn't see them. Internal storage
# is a plain Linux directory, so `run-as` reads/writes it exactly as the app
# would. See docs/SETUP.md.
DEVICE_JSON = "files/benchmark.json"

ROOT = Path(__file__).resolve().parent.parent
LOCAL_JSON = ROOT / "eval" / "benchmark.json"
GRADLEW = ROOT / "android" / ("gradlew.bat" if sys.platform == "win32" else "gradlew")

# The Android Studio JBR is used rather than relying on system JAVA_HOME, which
# may point at an unrelated JDK (or none). See docs/SETUP.md.
STUDIO_JBR = Path(r"C:\Program Files\Android\Android Studio\jbr")

TARGET_MS = 2500


def adb(*args: str, check: bool = False) -> subprocess.CompletedProcess:
    r = subprocess.run([ADB, *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        raise SystemExit(f"adb {' '.join(args)} failed:\n{r.stderr}")
    return r


def build_and_install() -> None:
    if not GRADLEW.exists():
        raise SystemExit(
            f"{GRADLEW} not found.\n"
            "Open android/ in Android Studio once to generate the Gradle wrapper, "
            "or run 'gradle wrapper' there if you have Gradle installed."
        )
    print("Building + installing ...")
    env = dict(os.environ)
    if STUDIO_JBR.exists():
        env["JAVA_HOME"] = str(STUDIO_JBR)
    r = subprocess.run([str(GRADLEW), ":app:installDebug"], cwd=ROOT / "android", env=env)
    if r.returncode != 0:
        raise SystemExit("Build failed.")


def run_benchmark(timeout_s: int) -> None:
    adb("shell", "am", "force-stop", APP_ID)
    adb("shell", "run-as", APP_ID, "rm", "-f", DEVICE_JSON)
    adb("logcat", "-c")

    # Doze/App Standby can throttle a backgrounded CPU-heavy coroutine to a
    # dead stop the moment the screen locks — cost over an hour of stalls
    # before this was in place. Both are required; screen-on alone isn't
    # enough if the device later re-enters Doze for another reason.
    adb("shell", "dumpsys", "deviceidle", "whitelist", f"+{APP_ID}")
    adb("shell", "svc", "power", "stayon", "true")

    print("Starting benchmark on device ...")
    adb("shell", "am", "start", "-n", ACTIVITY, "--ez", "autorun", "true", check=True)

    # The activity logs a completion marker; poll for it rather than guessing a
    # duration, because a small-model sweep can legitimately take many minutes.
    deadline = time.time() + timeout_s
    last_len = 0
    while time.time() < deadline:
        time.sleep(3)
        log = adb("logcat", "-d", "-s", "M0Benchmark:I").stdout
        if len(log) > last_len:
            for line in log[last_len:].splitlines():
                if "M0Benchmark" in line:
                    print("  " + line.split("M0Benchmark", 1)[-1].lstrip(": "))
            last_len = len(log)
        if "BENCHMARK_COMPLETE" in log:
            print("Benchmark finished.")
            return
    raise SystemExit(f"Timed out after {timeout_s}s. Check: adb logcat -s M0Benchmark:I")


def pull() -> dict:
    # Plain `adb pull` can't read internal storage without root; `exec-out
    # run-as` reads the file as the app's own UID and streams the bytes back
    # over adb's binary-safe channel (plain `adb shell` mangles line endings).
    LOCAL_JSON.parent.mkdir(parents=True, exist_ok=True)
    r = subprocess.run([ADB, "exec-out", "run-as", APP_ID, "cat", DEVICE_JSON], capture_output=True)
    if r.returncode != 0 or not r.stdout:
        raise SystemExit(f"Could not read {DEVICE_JSON} via run-as:\n{r.stderr.decode(errors='replace')}")
    LOCAL_JSON.write_bytes(r.stdout)
    return json.loads(r.stdout.decode("utf-8"))


def tabulate(report: dict) -> None:
    dev = report.get("device", {})
    print()
    print(f"Device : {dev.get('model')}  soc={dev.get('soc')}  cores={dev.get('cpus')}")
    print(f"Budget : {report.get('targetMillis', TARGET_MS)} ms median per utterance")
    print()

    rows = report.get("results", [])
    if not rows:
        print("No results. Were models and WAVs pushed?")
        return

    hdr = f"{'model':<28} {'thr':>3} {'MB':>5} {'load':>6} {'median':>7} {'pss':>6}  verdict"
    print(hdr)
    print("-" * len(hdr))

    passing = []
    for r in sorted(rows, key=lambda x: (x.get("medianMillis") or 1 << 30)):
        if "error" in r:
            print(f"{r['id']:<28} {'':>3} {'':>5} {'':>6} {'':>7} {'':>6}  ERROR: {r['error']}")
            continue
        median = r.get("medianMillis", -1)
        verdict = r.get("verdict", "?")
        print(f"{r['id']:<28} {r.get('numThreads',0):>3} {r.get('sizeMB',0):>5} "
              f"{r.get('loadMillis',0):>5}ms {median:>6}ms {r.get('peakPssMB',0):>5}M  {verdict}")
        if verdict == "PASS":
            passing.append(r)

    print()
    if not passing:
        print("GATE: FAIL — nothing met the latency budget.")
        print()
        print("This is a real finding, not a tuning problem. Options, in order:")
        print("  1. Try the mel-truncation experiment (slice encoder positional")
        print("     embeddings to actual audio length) — big win, some accuracy cost.")
        print("  2. Drop to whisper-tiny and measure whether accuracy is still useful.")
        print("  3. Reconsider the streaming Zipformer fallback, accepting the loss")
        print("     of punctuation and the need for a Devanagari punctuation model.")
        return

    # "Largest" by on-disk size is the right proxy here: within the Whisper family
    # more weights means better Hindi, and that is the axis we are buying.
    best = max(passing, key=lambda r: r.get("sizeMB", 0))
    print(f"GATE: PASS — largest model within budget is {best['id']}")
    print(f"       {best['medianMillis']}ms median, {best['sizeMB']}MB, "
          f"{best['numThreads']} threads, {best.get('peakPssMB')}MB peak PSS")
    print()
    print("Next: sanity-check its transcripts in the JSON before committing to it.")
    print("Speed with garbage output is not a pass — read eval/benchmark.json.")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--no-build", action="store_true", help="skip gradle build/install")
    ap.add_argument("--report", action="store_true", help="re-tabulate eval/benchmark.json, no device needed")
    ap.add_argument("--timeout", type=int, default=1800, help="seconds to wait (default 1800)")
    args = ap.parse_args()

    if args.report:
        if not LOCAL_JSON.exists():
            raise SystemExit(f"{LOCAL_JSON} not found — run a benchmark first.")
        tabulate(json.loads(LOCAL_JSON.read_text(encoding="utf-8-sig")))
        return 0

    if not args.no_build:
        build_and_install()
    run_benchmark(args.timeout)
    tabulate(pull())
    return 0


if __name__ == "__main__":
    sys.exit(main())
