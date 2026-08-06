# Private Voice

Fully offline voice typing and a real typing keyboard for Android, targeting Indian
English and Hindi/Hinglish (code-switched) speech.

Ships as an `InputMethodService` — a complete custom keyboard, not a fork of an
existing one — plus a system `RecognitionService`, so the same on-device engine also
answers any app's standard Android speech API (e.g. HeliBoard's mic key). Voice is a
*mode* inside the keyboard, reached via a mic key, not a separate app.

**Privacy is structural, not promised.** The app declares no `INTERNET` permission,
and a Gradle task fails the build if one ever appears in the merged manifest — even
one introduced transitively by a dependency. It cannot exfiltrate audio even if it
tried.

Target device: Samsung Galaxy A35 (Exynos 1380). No accessible NPU, so this is CPU
inference, and that constraint drives most of the design.

## Status

**Installed and in daily real use** on the developer's own phone. Bilingual
Hindi/English dictation works; English↔Hindi translation (in both directions) is
verified on-device; a full custom typing keyboard (letters, symbols, emoji,
phrasebook, theming) ships alongside voice, not instead of it.

What's *not* yet verified is at least as important as what is — real human on-device
testing (not just "compiles and installs cleanly") remains the single highest-value
next step across both the ASR routing logic and several keyboard UI gestures. See
**[`docs/PROJECT_OVERVIEW.md`](docs/PROJECT_OVERVIEW.md)** for the full architecture
writeup and an honest current-state checklist, and **[`docs/STATUS.md`](docs/STATUS.md)**
/ **[`docs/UI_KEYBOARD_REDESIGN.md`](docs/UI_KEYBOARD_REDESIGN.md)** for the detailed,
dated engineering log this project is built from — several findings there are
counter-intuitive and worth reading before touching the corresponding code.

No release build has shipped yet (no Play Store listing, no public GitHub push). See
[`docs/RELEASE_CHECKLIST.md`](docs/RELEASE_CHECKLIST.md) for what's outstanding before
either.

## Why Whisper

| Option | Verdict |
|---|---|
| **Whisper** (base/small) | ✅ Hindi + English + punctuation + casing in one model |
| IndicConformer-600M | ❌ 22 Indian languages but **no English**, and no punctuation |
| Conformer/CTC + punctuation model | ❌ no Devanagari punctuation-restoration model exists |
| Streaming Zipformer | ⚠️ fallback only — fast, but unpunctuated |

Whisper is the only single-model option that meets the requirement. The cost is
latency: it always pads input to a 30-second window, so decode time is roughly
constant regardless of how long you spoke, and it cannot stream. Text lands shortly
after the mic is released, not while speaking — unlike Google's on-device streaming
RNN-T, which this project deliberately trades away for accuracy and punctuation.

The runtime is **whisper.cpp** (GGML), not the ONNX/sherpa-onnx path this project
started with — sherpa-onnx's byte-level-BPE detokenization silently drops most
Devanagari characters, which produced a badly wrong initial read on Hindi accuracy.
Full account in `docs/M0_RESULTS.md`.

## Honest expectations on Hinglish

Published code-switch WER across ASR models spans **27–70%**, and models degrade
30–50% relative on code-switched speech versus monolingual. Beating Google on
Hinglish with off-the-shelf weights is unlikely — measured Hinglish WER on this
project's own 48-utterance corpus lands inside that published range, not below it. A
Hindi-specific fine-tune is the leading unexplored lever; see
`docs/PROJECT_OVERVIEW.md` §5 for the reasoning.

## Layout

```
android/
  app/      IME, keyboard UI (typing + voice), RecognitionService, settings,
            phrasebook, M0 benchmark harness
  engine/   AsrEngine abstraction, whisper.cpp backend, EN->HI MT engine,
            transliterators, vendored sherpa-onnx Kotlin API (legacy)
tools/      setup/model-fetch/benchmark/WER-scoring scripts, ONNX MT pipeline
            construction/verification scripts
eval/       48-utterance corpus: audio (gitignored) + Devanagari & Latin refs
docs/       PROJECT_OVERVIEW.md (read this first), STATUS.md,
            UI_KEYBOARD_REDESIGN.md, M0_RESULTS.md, SETUP.md, RELEASE_CHECKLIST.md
```

## Quick start

```powershell
python tools\setup_whispercpp.py
python tools\fetch_ggml_models.py --models base,small --quant q8_0
python tools\bench_device.py
```

Full toolchain recipe (AGP 9 / built-in Kotlin / `JAVA_HOME` gotchas): `docs/SETUP.md`.

## Licences

Whisper is MIT. whisper.cpp is MIT. sherpa-onnx is Apache-2.0 (legacy path, not the
shipping backend). `opus-mt-en-hi` (Helsinki-NLP) is Apache-2.0. IndicConformer is
MIT (evaluated, not used). Verify the licence of any fine-tune before shipping it.
This project's own licence is not yet decided — see `docs/RELEASE_CHECKLIST.md`.
