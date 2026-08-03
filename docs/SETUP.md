# Setup

## Toolchain

| Need | Status on this machine | Notes |
|---|---|---|
| JDK 17 or 21 | ⚠️ **Java 25 installed** | AGP does not support 25. Install 17 or 21. |
| Android SDK | ❌ missing | Needs platform 35 + build-tools |
| adb (platform-tools) | ❌ missing | Required to push models and run the benchmark |
| Gradle | ❌ missing | Only needed once, to generate the wrapper |
| Python 3.10+ | ✅ 3.10.10 | Used by everything in `tools/` |
| git | ❌ missing | Optional, but you want history for this |

### Recommended: Android Studio

Installing Android Studio resolves JDK, SDK, adb and the Gradle wrapper in one step —
it bundles a JetBrains Runtime 21 and can generate `gradlew` for you.

1. Install Android Studio.
2. Open `D:\pj\android` — it will offer to create the Gradle wrapper and sync.
3. SDK Manager → install **Android 15 (API 35)** platform + build-tools.
4. Add `platform-tools` to `PATH` so `adb` works from a terminal.

### Alternative: command line only

```powershell
# JDK 21
winget install EclipseAdoptium.Temurin.21.JDK

# Android command-line tools, then:
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"

# Point Gradle at the right JDK if 25 remains your default:
#   android/gradle.properties -> org.gradle.java.home=C:/path/to/jdk-21
```

The Gradle wrapper JAR is not in this repo (it is a binary). Generate it once:

```powershell
cd D:\pj\android
gradle wrapper --gradle-version 8.11.1
```

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
