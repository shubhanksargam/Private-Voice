# Session status — resume point

Last updated: 2026-08-03 (recordings completed). Repo at commit `0f8d6be` as of
the last commit before this update — see `git log` for the latest.

## One-line state

**The app builds cleanly, all 48 eval recordings are done and converted.** The
only remaining step is connecting the A35 and running the benchmark — recording
is no longer the bottleneck.

## Do this next

```powershell
# Connect A35 via USB, enable USB debugging, accept the RSA prompt, then:
python tools\fetch_models.py --push          # pushes tiny/base/small to the device
python tools\prepare_audio.py --src recordings --push   # pushes the 48 WAVs
python tools\bench_device.py
# Reads eval/benchmark.json, applies the 2.5s-median gate, prints a verdict.
```

`tools/bench_device.py` records transcripts alongside timings — a fast model
returning garbage is not a pass, so read `eval/benchmark.json`, don't just trust
the verdict line. Once it's run, score properly:

```powershell
python tools\eval_wer.py --refs eval\refs        # Devanagari
python tools\eval_wer.py --refs eval\refs_latn   # Latin
```

## What's actually been verified (not just written)

- **Toolchain is fully resolved and build-tested**, not just installed:
  Android Studio 2026.1.3.7, SDK (platform 36, build-tools 36.0.0,
  platform-tools) provisioned headlessly, Gradle wrapper 9.6.1 generated,
  AGP bumped to 9.1.1 with the built-in-Kotlin migration.
  `:app:assembleDebug` → **BUILD SUCCESSFUL**.
- **Privacy claim independently verified**: ran `aapt2 dump permissions`
  directly on the built APK (not just the project's own `checkNoInternet`
  Gradle task). Only `RECORD_AUDIO` + an internal broadcast-receiver
  permission. No `INTERNET`, no network permissions of any kind.
- **sherpa-onnx vendored and compiling**: 4 native `.so` + all 22 official
  Kotlin API files at v1.13.4, wired via the `kotlin.directories` source-set
  API (built-in Kotlin doesn't read `java.srcDirs` the way the old plugin did
  — this cost a debugging cycle, see `docs/SETUP.md`).
- **All three Whisper sizes downloaded** — `tiny`, `base`, `small` under
  `models/`, both int8 and full precision each (~99MB / ~450MB / ~1.3GB on
  disk respectively). Not yet pushed to a device. `ffmpeg` also installed
  (winget, `Gyan.FFmpeg` 8.1.2) for `tools/prepare_audio.py`.
- **Eval pipeline validated end-to-end with synthetic checks**: scored
  references against themselves → exactly 0.00% WER/CER on every split
  (confirms the Devanagari normalisation — nukta, ZWJ/ZWNJ, danda, digit
  folding — is lossless). Cross-script check confirmed Hindi scored against
  the wrong script gives 100% WER regardless of accuracy — this is *why*
  `eval/refs/` (Devanagari) and `eval/refs_latn/` (Latin) both exist; score
  against both once real audio exists.
- **All 48 eval recordings done and converted.** `eval/audio/` has all 48 WAVs
  (16kHz mono 16-bit, via `tools/prepare_audio.py`) matching every id in
  `eval/prompts.jsonl`. The only remaining blocker is connecting the A35 and
  running the benchmark — recording is no longer the bottleneck.

## Three real bugs fixed getting to a green build (context if anything regresses)

1. AGP 8.7.3 + separate `kotlin-android` plugin was stale for 2026. AGP 9.0+
   has built-in Kotlin; applying the old plugin throws `Cannot add extension
   with name 'kotlin'`. Fixed by removing the plugin — not a workaround, since
   [Google's migration guide](https://developer.android.com/build/migrate-to-built-in-kotlin)
   says built-in Kotlin is mandatory from AGP 10.0.
2. Built-in Kotlin doesn't compile extra sources added via `java.srcDirs` —
   needs `kotlin.directories` instead. This silently broke the vendored
   sherpa-onnx sources (`engine/build.gradle.kts`).
3. `checkDebugNoInternet`'s task-wiring called `tasks.named("assembleDebug")`
   before AGP had registered that task. Fixed with
   `tasks.matching{}.configureEach{}` (`app/build.gradle.kts`).
4. (Not a version bug, a typo) `BenchmarkActivity.kt`'s KDoc literally
   contained `eval/*.wav`. Kotlin block comments nest, so that `/*` opened an
   unclosed second comment level, eating the rest of the file. Reworded.

Full narrative in `docs/SETUP.md` and the commit `eb9abe1` message.

## Where things are on disk

| Path | State |
|---|---|
| `android/` | Gradle project, builds clean. `local.properties` and `gradlew.bat`/wrapper jar present (gitignored / committed respectively) |
| `android/engine/src/main/vendor/` | Vendored sherpa-onnx Kotlin sources (gitignored — regenerate with `python tools/setup_sherpa.py`) |
| `android/engine/src/main/jniLibs/arm64-v8a/` | Vendored `.so` files (gitignored, same regen command) |
| `models/{tiny,base,small}/` | All downloaded, not yet pushed to a device |
| `eval/prompts.jsonl`, `eval/refs/`, `eval/refs_latn/` | Committed, complete |
| `eval/audio/` | All 48 WAVs present (gitignored — it's your voice, not source) |
| `%LOCALAPPDATA%\Android\Sdk` | Machine-local, not in repo. Regenerate per `docs/SETUP.md` if this is a different machine |

## If resuming on a different machine

`docs/SETUP.md` has the full non-interactive provisioning recipe (cmdline-tools
URL, license-hash technique, wrapper-jar source). `git clone`, then:

```powershell
python tools\setup_sherpa.py
python tools\fetch_models.py
# ...follow docs/SETUP.md's SDK provisioning section if adb/gradlew aren't present
```

## Longer-term context

Full milestone plan (M0–M6) is at
`C:\Users\sarga\.claude\plans\expressive-beaming-backus.md`. Short version: M0
(this device-feasibility gate) must pass before any IME/RecognitionService code
gets written — the project is deliberately unbuilt above the benchmark harness
until the A35's actual speed/accuracy trade-off is known from real numbers,
not assumed.
