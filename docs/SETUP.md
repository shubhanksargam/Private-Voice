# Setup

## Toolchain

Fully resolved and build-verified — `:app:assembleDebug` succeeds end to end.

| Need | Status | Notes |
|---|---|---|
| JDK | ✅ Android Studio JBR 25 | Used explicitly via `JAVA_HOME`, not system Java. AGP 9.1.1 needs JDK 17+; earlier guidance in this file said 25 was incompatible — that was wrong for 2026's AGP. |
| Android Studio | ✅ 2026.1.3.7 | `C:\Program Files\Android\Android Studio` |
| Android SDK | ✅ provisioned headlessly | `%LOCALAPPDATA%\Android\Sdk` — platform 36, build-tools 36.0.0, platform-tools, via `sdkmanager` rather than Studio's GUI wizard |
| adb | ✅ | `tools/_adb.py` resolves the full path so nothing depends on PATH being refreshed in a given shell |
| Gradle | ✅ wrapper generated, 9.6.1 | `android/gradlew.bat`; AGP 9.1.1 needs 9.3.1+ |
| Kotlin | ✅ built-in (AGP 9+) | No separate `kotlin-android` plugin — see below |
| Python 3.10+ | ✅ 3.10.10 | Used by everything in `tools/` |
| git | ✅ 2.55.0 | Repo initialised at `D:\pj` |
| ffmpeg | ❌ missing | Only needed for `tools/prepare_audio.py`: `winget install Gyan.FFmpeg` |

Everything above was provisioned non-interactively — no GUI setup wizard, since
this tool's shell sandbox runs with stdin attached to the null device and
interactive prompts (including `sdkmanager --licenses`) can't receive piped input
there. `sdkmanager` itself was fetched as `commandlinetools-win-*_latest.zip`
under `cmdline-tools/latest/`, and SDK licenses were accepted by writing the
well-known public license-hash files directly into `Sdk/licenses/` (the same
technique `android-actions/setup-android` and most CI pipelines use) rather than
through the interactive prompt.

If you open Android Studio, point it at the same SDK location (Settings →
Languages & Frameworks → Android SDK) rather than letting it provision a second
copy.

### Why AGP 9.1.1 / Gradle 9.6.1 / API 36 / built-in Kotlin, not 8.x / API 35

The project was originally scaffolded against AGP 8.7.3 and a separate
`kotlin-android` plugin — constraints that were current at some point but are
stale for 2026. Two real build failures came from this and both are fixed now:

1. **AGP 9.0+ has built-in Kotlin support**, and applying the old
   `org.jetbrains.kotlin.android` plugin alongside it throws `Cannot add
   extension with name 'kotlin'`. Fixed by removing the plugin entirely — Kotlin
   compiles via AGP directly. This isn't a workaround; [Google's own migration
   guide](https://developer.android.com/build/migrate-to-built-in-kotlin) says
   built-in Kotlin becomes *mandatory* in AGP 10.0.
2. **Built-in Kotlin doesn't read extra sources from `java.srcDirs`.** The
   vendored sherpa-onnx sources under `engine/src/main/vendor/` need the new
   `kotlin.directories` source-set property instead (see
   `engine/build.gradle.kts`) — `java.srcDirs` silently stopped feeding the
   Kotlin compile task under the new model, which surfaced as "Unresolved
   reference" on every sherpa-onnx import even though the files were present.

AGP 9.1.1 (April 2026) is one release behind the July 2026 bleeding edge, needs
Gradle 9.3.1+, and supports compileSdk up to 37. `compileSdk`/`targetSdk` are set
to 36 (Android 16, stable since ~mid-2026) rather than jumping to 37 (Android 17,
only ~2 months old as of this writing).

### A third bug, unrelated to any of the above

`BenchmarkActivity.kt`'s KDoc comment originally described a path as
`files/eval/*.wav`. Kotlin block comments **nest** (unlike C), so the `/*` inside
`eval/*.wav` opened a second comment level that the file's one closing `*/` never
balanced — the whole rest of the file silently became a comment, reported as
"Unclosed comment" at EOF. Reworded to avoid the accidental `/*`; worth knowing
about since it's easy to reintroduce in any doc comment that mentions a glob path.

## Project setup

```powershell
# 1. Vendor sherpa-onnx (native .so + Kotlin API). ~45MB, one time.
python tools\setup_sherpa.py

# 2. Download Whisper ONNX weights and push them to the phone.
python tools\fetch_models.py --push

# 3. Build, install, run the M0 benchmark, tabulate.
python tools\bench_device.py
```

### Why vendoring rather than a Gradle dependency

sherpa-onnx publishes no official AAR or Maven artifact — the documented integration
path is copying prebuilt `.so` files into `jniLibs/` and their Kotlin sources into your
tree. A third-party repackage exists on Maven Central (`com.bihe0832.android`), but
taking an unaudited binary into an app whose whole premise is "your audio never leaves
the device" is the wrong trade. `tools/setup_sherpa.py` pulls the official release
artifacts and pins the version.

## Model footprint

Measured from the actual downloads, not estimated:

| Model | Encoder int8 | Decoder int8 | Total |
|---|---|---|---|
| tiny | 12.3 MB | 85.7 MB | ~99 MB |
| base | ~25 MB | ~115 MB | ~140 MB |
| small | ~85 MB | ~180 MB | ~265 MB |

The decoders look absurdly large next to their encoders because sherpa-onnx's
export leaves Whisper's token-embedding matrix in fp32 — 51865 tokens × d_model × 4
bytes, which is 76 MB for tiny and 152 MB for small on its own. Int8 quantisation
only touches the transformer layers.

Practical consequences:

- Disk and RAM scale with vocabulary, not just capacity, so `small` is heavier than
  its 244M parameter count suggests. Still workable on a 6GB A35.
- The embedding is a lookup table, not compute. It costs memory, not latency — so
  it does **not** move the M0 gate, which is about wall-clock.
- If memory ever becomes the binding constraint rather than speed, quantising the
  embedding is the obvious lever and nothing above the `AsrEngine` interface has to
  change.

## Evaluation audio

48 prompts ship in `eval/prompts.jsonl` — 12 Indian English, 12 Hindi, 24 Hinglish.
References are generated from them, so recording is the only manual step.

```powershell
python tools\make_refs.py --sheet          # print what to read aloud
# ...record one clip per prompt, named <id> (en_001, hi_004, mix_012)
winget install Gyan.FFmpeg                  # one time, for format conversion
python tools\prepare_audio.py --src <recordings-folder> --push
```

`prepare_audio.py` converts whatever your phone recorder produces into the 16kHz
mono 16-bit WAV the models need, verifies filenames against the prompt ids, and
tells you which prompts are still missing.

Record these yourself, and speak the way you actually dictate. Your accent and your
particular Hinglish mix matter far more than any public benchmark, because you are
the user. Do not say punctuation out loud — inferring it is the entire reason we
chose Whisper over a faster CTC model.

### Two reference sets, deliberately

`make_refs.py` writes both `eval/refs/` (Devanagari for hi/mix) and
`eval/refs_latn/` (Latin). Score against both:

```powershell
python tools\eval_wer.py --refs eval\refs
python tools\eval_wer.py --refs eval\refs_latn
```

This matters more than it looks. Scoring correct Hindi against the wrong script
gives **100% WER** — verified, not hypothetical:

| hypotheses | references | hi WER | mix WER |
|---|---|---|---|
| Devanagari | Devanagari | 0.00% | 0.00% |
| Latin | Devanagari | 100.00% | 98.65% |

So a single WER number cannot distinguish "the model misheard you" from "the model
heard you perfectly and wrote it in the other script". Whisper emits Devanagari
under `language=hi`, while most people *type* Hinglish in Latin. Running both tells
you which problem you have — and a script mismatch is a transliteration
post-processing job, not an ASR accuracy problem.
