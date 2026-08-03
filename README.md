# Private Voice

Fully offline voice typing for Android, targeting Indian English and Hindi.

Ships as a standalone voice engine — an `InputMethodService` plus a
`RecognitionService` — so it works behind HeliBoard's mic key and behind any app
that uses the standard Android speech API. It is not a keyboard fork.

**Privacy is structural, not promised.** The app declares no `INTERNET` permission,
and a Gradle task fails the build if one ever appears in the merged manifest. It
cannot exfiltrate audio even if a dependency tries.

Target device: Samsung Galaxy A35 (Exynos 1380). No accessible NPU, so this is CPU
inference, and that constraint drives most of the design.

## Status

**M0 — device feasibility benchmark.** Not yet run: needs an Android SDK and a
connected phone. See [docs/SETUP.md](docs/SETUP.md).

Everything above M0 is deliberately unbuilt. The project rests on one unknown —
whether the A35 can run a model that is both accurate enough and fast enough — and
that question gets a real answer before any UI work happens.

## Why Whisper

| Option | Verdict |
|---|---|
| **Whisper** (tiny/base/small) | ✅ Hindi + English + punctuation + casing in one model |
| IndicConformer-600M | ❌ 22 Indian languages but **no English**, and no punctuation |
| Conformer/CTC + punctuation model | ❌ no Devanagari punctuation-restoration model exists |
| Streaming Zipformer | ⚠️ fallback only — fast, but unpunctuated |

Whisper is the only single-model option that meets the requirement. The cost is
latency: it always pads input to a 30-second window, so decode time is roughly
constant regardless of how long you spoke, and it cannot stream.

Google's on-device voice typing uses a streaming RNN-T, which is why its text
appears as you speak. **We are trading latency for accuracy and punctuation.**
Expect text shortly after you release the mic, not during.

## Honest expectations on Hinglish

Published code-switch WER across ASR models spans **27–70%**, and models degrade
30–50% relative on code-switched speech versus monolingual. Beating Google on
Hinglish with off-the-shelf weights is unlikely.

M5 exists to measure that gap against your own voice rather than guess at it, and
to decide from data whether a fine-tune is warranted.

## Layout

```
android/
  app/      IME, RecognitionService, settings, M0 benchmark harness
  engine/   AsrEngine abstraction, Whisper backend, VAD, WAV I/O
tools/
  setup_sherpa.py   vendor sherpa-onnx native libs + Kotlin API
  fetch_models.py   download Whisper ONNX weights, adb push
  bench_device.py   drive M0 on device, tabulate, apply the gate
  eval_wer.py       Devanagari-aware WER/CER
eval/
  audio/    your recordings (gitignored)
  refs/     reference transcripts (tracked)
```

## Quick start

```powershell
python tools\setup_sherpa.py
python tools\fetch_models.py --push
python tools\bench_device.py
```

Full plan: `C:\Users\sarga\.claude\plans\expressive-beaming-backus.md`

## Licences

Whisper is MIT. sherpa-onnx is Apache-2.0. IndicConformer is MIT. Verify the licence
of any fine-tune before shipping it.
