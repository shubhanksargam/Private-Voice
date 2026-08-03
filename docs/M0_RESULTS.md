# M0 results — device feasibility gate

Run on: Samsung SM-A356E (Galaxy A35 5G), Exynos 1380 (`s5e8835`), Android 16 (API 36).
48-utterance eval corpus (12 en, 12 hi, 24 Hinglish), your own recordings.

## Timing gate

Budget: 2500ms median per utterance (see `BenchmarkRunner.TARGET_MILLIS`).

| Model | Threads | Median | Size | Verdict |
|---|---|---|---|---|
| tiny (int8) | 4 | 642ms | 98MB | ✅ PASS |
| tiny (int8) | 2 | 732ms | 98MB | ✅ PASS |
| **base (int8)** | **4** | **1030ms** | **153MB** | **✅ PASS** |
| base (int8) | 2 | 1143ms | 153MB | ✅ PASS |
| small (int8) | 4 | 4876ms | 358MB | ❌ too-slow |
| small (int8) | 2 | 5402ms | 358MB | ❌ too-slow |

**Timing gate winner: `base-int8` at 4 threads.** 4 threads beat 2 threads
consistently (more of the 4 big A78 cores in play). `small` fails by roughly
2x regardless of thread count — it's a genuine compute ceiling on this SoC,
not a tuning problem. Thread count 6 (tested in an earlier, abandoned full
sweep before this run) was worse than 2 or 4 across the board, confirming the
4 little A55 cores hurt more than help — dropped from the sweep entirely.

fp32 (non-quantized) variants were not run to completion: one partial
data point (`base-full` at 2 threads) showed per-utterance times of
5,000-7,000ms even for a model this size, 3-5x its int8 counterpart, for no
accuracy benefit anyone would ship. Not worth the device-hours to complete.

## Accuracy: `base-int8-t4` scored against your recordings

Two reference scripts scored, since Whisper emits Devanagari for `hi`/`mix`
while people generally type Hinglish in Latin (see `tools/eval_wer.py`):

**vs Devanagari (`eval/refs/`):**

| split | n | WER | CER |
|---|---|---|---|
| en | 12 | 20.00% | 8.86% |
| hi | 12 | 109.09% | 112.92% |
| mix | 24 | 104.04% | 106.85% |
| **ALL** | 48 | **80.95%** | **76.97%** |

**vs Latin (`eval/refs_latn/`):**

| split | n | WER | CER |
|---|---|---|---|
| en | 12 | 20.00% | 8.86% |
| hi | 12 | 93.51% | 56.57% |
| mix | 24 | 82.06% | 52.74% |
| **ALL** | 48 | **66.43%** | **40.95%** |

(`en` is identical across both since English has no script variant.)

## The actual finding

**`base-int8` passes the latency gate but fails the accuracy bar for Hindi and
Hinglish — badly, not marginally.** WER over 80% on Hindi/Hinglish regardless
of scoring script means the output is largely unusable, not just rough around
the edges. English is workable (20% WER) but still well short of what Google
Voice Typing delivers — mostly named-entity mishears ("Whitefield" →
"White Field", "Koramangala" → "Kormangala", "Aadhaar card" → "hardcore").

This creates a genuine tension, not a simple pick:
- **`base`**: fast enough, not accurate enough on the languages that matter most.
- **`small`**: plausibly more accurate (committed to Devanagari script rather
  than `base`'s garbled Latin transliteration, though still frequently wrong),
  but **2x over the latency budget** regardless of thread count.

Neither model, as tested, delivers on "beat Google on Hindi/Hinglish." This
was flagged as the likely outcome going in — published code-switch WER
across models runs 27-70%, and multilingual Whisper's Hindi is known to be
weaker than English — but seeing it confirmed on-device, at this magnitude,
is the actual answer M0 was built to produce.

## What this changes going forward

Per the original plan's M5 decision tree, the live options are:

1. **Fine-tune**: a Hindi-specific fine-tune (e.g. `vasista22/whisper-hindi-*`
   lineage) at `base` or `small` size could close much of this gap — that
   family reports single-digit Hindi WER on clean benchmarks, vastly better
   than generic multilingual Whisper's ~80-110% seen here. This is the most
   promising lever given how large the gap is.
2. **Speed up `small`**: mel-truncation (skip padding to the full 30s window)
   or NNAPI/GPU offload experiments could plausibly bring `small` under
   budget — worth testing before ruling it out, since the accuracy upside
   looked real (Devanagari-committed output vs `base`'s Latin gibberish).
3. **Dual-model routing**: route by detected language, accepting `base`'s
   weaker Hindi as a stopgap while 1 or 2 is pursued.
4. **Ship `base` as-is for English-dominant use, flag Hindi as beta.** Not
   recommended as a final state, but honest about what's actually usable today.

None of these are implemented yet — this file records the measurement, not a
decision. That decision needs a human call given the tradeoffs above.

## Two infrastructure bugs worth knowing about if this gets rerun

1. **Screen-off silently froze the benchmark twice**, once for ~26 minutes and
   once for ~21 minutes, via Android's Doze/App Standby throttling a
   backgrounded CPU-heavy coroutine to zero progress — the process stayed
   alive throughout, so it wasn't obvious without checking logcat timestamps.
   Fixed by whitelisting the app from Doze (`dumpsys deviceidle whitelist
   +<pkg>`) in addition to `svc power stayon true`; both are now applied
   automatically by `tools/bench_device.py`.
2. **The benchmark was forcing `language=en` on every file**, including
   Hindi/Hinglish ones, for the first (discarded) run — Whisper doesn't
   transcribe Hindi under an English tag, it mistranslates, producing
   fluent-sounding but wrong English text. Fixed in `BenchmarkRunner.kt` to
   derive language per file from the eval corpus's `en_`/`hi_`/`mix_` naming
   convention. The numbers above are all from the corrected run.
