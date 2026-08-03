# Session status — resume point

Last updated: 2026-08-04. M0 is done — see `docs/M0_RESULTS.md` for the full
writeup. This file is the short version plus what's next.

## One-line state

**M0's latency results stand; its Hindi accuracy results were invalid and have
been corrected.** sherpa-onnx has a byte-level-BPE detokenization bug that
silently drops partial-UTF-8 tokens, destroying ~60% of Devanagari characters
while leaving ASCII untouched. Re-measured with correct decoding, Hindi WER is
**50.65%** (not 90.91%) and Hindi CER is **21.03%** (not 62.36%). Full writeup
in `docs/M0_RESULTS.md`'s CORRECTION section.

The "ship English-only" decision was made on the bad numbers and is **open
again** — it was justified by "Hindi is unusable," which isn't what the data
shows.

## Do this next — swap the ASR backend, then re-run M0

sherpa-onnx can't ship as the runtime for Hindi in its current state. The
original plan anticipated this: `AsrEngine` names `WhisperCppEngine` as
"backend B, if needed," and this is that case — whisper.cpp accumulates bytes
across tokens and handles byte-level BPE correctly.

1. **Add a whisper.cpp backend** behind the existing `AsrEngine` interface
   (GGML/GGUF weights instead of ONNX; JNI via the official `whisper.android`
   bindings). Nothing above the interface should need to change.
2. **Re-run the M0 sweep** on it — `tiny`/`base`/`small`, int8-equivalent
   quantization, 2/4 threads. The tooling and the 48-utterance corpus are all
   in place and the harness now writes incrementally, so this is cheap to
   repeat.
3. **Re-decide `base` vs `small`.** Their previous ranking is untrustworthy:
   `base` emitted romanised Latin for Hindi (dodging the bug), `small` emitted
   Devanagari (taking the full hit). Latency still says `base` fits and
   `small` doesn't — that part is unaffected and remains the binding
   constraint.
4. **Then** revisit English-only vs. shipping Hindi, on real numbers.

M1 (the production engine layer: `AudioSource` → `VadGate` → transcribe) is
still the next *feature* milestone, but doing it against a backend that's
about to be replaced would be wasted work — do the swap first.

## What's actually been verified (not just written)

- **M0 benchmark completed and scored.** Full results, both timing and WER/CER
  against both Devanagari and Latin references, in `docs/M0_RESULTS.md`.
  `eval/benchmark.json` (gitignored — contains your transcribed speech) is the
  raw data if you need to re-score or dig into individual utterances.
- **Toolchain is fully resolved and build-tested**: Android Studio 2026.1.3.7,
  SDK (platform 36, build-tools 36.0.0, platform-tools) provisioned
  headlessly, Gradle wrapper 9.6.1, AGP 9.1.1 with built-in-Kotlin migration.
- **Privacy claim independently verified** via `aapt2 dump permissions` on the
  built APK: only `RECORD_AUDIO` + an internal broadcast-receiver permission.
  No `INTERNET`, no network permissions of any kind.
- **All 48 eval recordings done, converted, and scored** against both
  reference scripts.
- **Device storage and power quirks found and fixed** — see "Infrastructure
  bugs" below and in `docs/M0_RESULTS.md`. These fixes are now baked into
  `tools/bench_device.py`, `tools/fetch_models.py`, `tools/prepare_audio.py`,
  and `tools/_adb.py`, so rerunning the benchmark (e.g. to test a fine-tuned
  model, or `small` with a speed optimisation) should just work.

## Infrastructure bugs fixed this session (matters if anything regresses)

Beyond the four build-time bugs from the previous update (AGP 9.x migration,
`kotlin.directories` source sets, task-wiring order, an accidental nested
Kotlin comment — still accurate, see below), three more surfaced during actual
device runs:

5. **This device's external/shared storage silently hides shell-written files
   from the app itself.** `adb push` into the app's own `Android/data/<pkg>/`
   dir, and even the public `Download/` folder, left files invisible to the
   app's own `File.listFiles()` — confirmed by writing there and having the
   running app report the directory empty, even though `run-as ls`/`cat`
   could see them fine. Fixed by switching `BenchmarkActivity` to internal
   storage (`filesDir`) and staging pushes through `/data/local/tmp` +
   `run-as <pkg> cp` (a plain Linux directory, no FUSE mediation) — see
   `_adb.push_dir_to_app_storage`.
6. **Screen-off froze the benchmark twice via Doze/App Standby** — ~26 min and
   ~21 min of zero progress while the process stayed alive, not obvious
   without checking logcat timestamps. Fixed with both
   `dumpsys deviceidle whitelist +<pkg>` and `svc power stayon true`; both are
   now issued automatically at the start of `tools/bench_device.py`'s run.
7. **The benchmark was forcing `language=en` on every file**, including
   Hindi/Hinglish — Whisper doesn't transcribe Hindi under an English tag, it
   mistranslates fluently-sounding wrong text. Fixed in `BenchmarkRunner.kt`
   to derive language per file from the `en_`/`hi_`/`mix_` naming convention.

Also: `BenchmarkRunner` now writes `benchmark.json` incrementally after each
config (not just at the very end), so killing the app mid-sweep no longer
loses already-completed configs — and the sweep itself defaults to int8-only
at 2/4 threads (fp32 and 6-thread configs dropped after confirming both are
strictly worse, to stop burning hours of device time on data that won't
change a shipping decision).

Original four build-time bugs (AGP 9.x / built-in Kotlin migration, unchanged):
1. AGP 8.7.3 + separate `kotlin-android` plugin conflicts with AGP 9's
   built-in Kotlin. Fixed by removing the plugin entirely.
2. Built-in Kotlin needs `kotlin.directories`, not `java.srcDirs`, for extra
   source sets — silently broke the vendored sherpa-onnx sources.
3. `checkDebugNoInternet`'s task-wiring called `tasks.named("assembleDebug")`
   before AGP registered it. Fixed with `tasks.matching{}.configureEach{}`.
4. A KDoc comment containing `eval/*.wav` accidentally opened a nested Kotlin
   block comment (`/*` inside), eating the rest of the file.

Full narrative in `docs/SETUP.md`, `docs/M0_RESULTS.md`, and commit history.

## Where things are on disk

| Path | State |
|---|---|
| `android/` | Gradle project, builds clean |
| `android/engine/src/main/vendor/`, `.../jniLibs/` | Vendored sherpa-onnx (gitignored — regenerate with `python tools/setup_sherpa.py`) |
| `models/{tiny,base,small}/` | Downloaded, pushed to device during the M0 run |
| `eval/prompts.jsonl`, `eval/refs/`, `eval/refs_latn/` | Committed, complete |
| `eval/audio/` | All 48 WAVs present (gitignored — it's your voice) |
| `eval/benchmark.json` | M0's raw results (gitignored — contains transcribed speech). Aggregate numbers are in `docs/M0_RESULTS.md` instead |
| `%LOCALAPPDATA%\Android\Sdk` | Machine-local, not in repo |

## If resuming on a different machine

`docs/SETUP.md` has the full non-interactive provisioning recipe. `git
clone`, then:

```powershell
python tools\setup_sherpa.py
python tools\fetch_models.py
# ...follow docs/SETUP.md's SDK provisioning section if adb/gradlew aren't present
```

Rerunning the benchmark against a *different* model (e.g. a Hindi fine-tune,
or `small` with a speed fix) just needs that model's ONNX files in a new
subdirectory under `models/`, then `python tools/fetch_models.py --push`
(adjusted for the new source) and `python tools/bench_device.py`.

## Longer-term context

Full milestone plan (M0-M6) is at
`C:\Users\sarga\.claude\plans\expressive-beaming-backus.md`. M0 (device
feasibility) is done; the plan's M5 (evaluation harness, decide on fine-tune)
is effectively where this session's results land — the decision itself is
still open.
