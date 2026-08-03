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

## Accuracy: `small-int8-t4` (failed the timing gate, scored anyway)

Worth knowing even though `small` can't ship as-is: does more model capacity
actually buy better Hindi/Hinglish, or is the ceiling elsewhere? Scored using
the same corpus despite the timing failure, since the JSON already has its
transcripts.

**vs Devanagari:**

| split | n | WER | CER |
|---|---|---|---|
| en | 12 | 14.17% | 4.33% |
| hi | 12 | 90.91% | 62.36% |
| mix | 24 | 89.69% | 72.72% |
| **ALL** | 48 | **68.33%** | **49.41%** |

**vs Latin:**

| split | n | WER | CER |
|---|---|---|---|
| en | 12 | 14.17% | 4.33% |
| hi | 12 | 100.00% | 100.00% |
| mix | 24 | 99.10% | 98.32% |
| **ALL** | 48 | **75.00%** | **71.88%** |

**Reading this against `base`:** English improves meaningfully (20.00% →
14.17% WER). Hindi CER improves substantially too (112.92% → 62.36% — roughly
half the character-level error), suggesting `small` really is getting closer
to the right sounds, not just noise. But word-level accuracy stays
catastrophic either way (90%+ WER), and `small` commits to Devanagari more
consistently than `base` (worse on the Latin-scored comparison, better on the
Devanagari one — the opposite pattern from a model that's actually hedging
between scripts).

**The actual implication: going from 74M-ish `base` to a 2.4x larger `small`
narrows the gap but doesn't come close to closing it.** That argues against
"just use a bigger stock Whisper" as the fix — model scale alone isn't the
lever. It's a point in favour of fine-tuning on real Hindi/Hinglish data
specifically, over waiting for a speed optimisation on `small` to pay off:
even a `small` that somehow ran in budget would likely still fail the
accuracy bar.

## The actual finding

**`base-int8` passes the latency gate but fails the accuracy bar for Hindi and
Hinglish — badly, not marginally.** WER over 80% on Hindi/Hinglish regardless
of scoring script means the output is largely unusable, not just rough around
the edges. English is workable (20% WER) but still well short of what Google
Voice Typing delivers — mostly named-entity mishears ("Whitefield" →
"White Field", "Koramangala" → "Kormangala", "Aadhaar card" → "hardcore").

This creates a genuine tension, not a simple pick:
- **`base`**: fast enough, not accurate enough on the languages that matter most.
- **`small`**: somewhat more accurate (character-level Hindi error roughly
  halved vs `base`), still nowhere close to usable, and **2x over the latency
  budget** regardless of thread count.

Neither model, as tested, delivers on "beat Google on Hindi/Hinglish." This
was flagged as the likely outcome going in — published code-switch WER
across models runs 27-70%, and multilingual Whisper's Hindi is known to be
weaker than English — but seeing it confirmed on-device, at this magnitude,
is the actual answer M0 was built to produce.

**Model size alone is not the lever.** Going from `base` (74M) to a 2.4x
larger `small` measurably improved English and Hindi's character-level error,
but word-level Hindi/Hinglish accuracy stayed catastrophic either way (90%+
WER both sizes). A `small` that somehow ran in budget would likely still fail
the accuracy bar — so speeding it up is a weaker bet than it looked before
`small` was actually scored.

## What this changes going forward

Per the original plan's M5 decision tree, the live options, now re-ordered by
what the `small` scoring actually showed:

1. **Fine-tune, now the clear leading option.** A Hindi-specific fine-tune
   (e.g. `vasista22/whisper-hindi-*` lineage) reports single-digit Hindi WER
   on clean benchmarks — vastly better than generic multilingual Whisper's
   ~70-110% seen here at *either* size. Since scaling stock Whisper up didn't
   meaningfully close the gap, training data quality — not parameter count —
   looks like the actual bottleneck.
2. **Speed up `small` — weaker bet than it looked before scoring it.** Even if
   mel-truncation or NNAPI/GPU offload got `small` under budget, its accuracy
   is still far short of usable. Worth revisiting only if a fine-tune isn't
   pursued and `base`'s ~50% CER penalty vs `small` matters enough on its own.
3. **Dual-model routing**: route by detected language, accepting `base`'s
   weaker Hindi as a stopgap while 1 is pursued.
4. **Ship `base` as-is for English-dominant use, flag Hindi as beta.** Not
   recommended as a final state, but honest about what's actually usable today.

None of these are implemented yet — this file records the measurement, not a
decision. That decision needs a human call given the tradeoffs above.

## Decision (2026-08-04): ship English-only for v1

Option 4 chosen. `base-int8` at 4 threads is the production engine going
forward: 1030ms median, comfortably within budget, ~20% WER on Indian
English (workable, not Google-beating, but usable). Hindi/Hinglish support
is deferred — not built into M1-M4 as a shipping feature — rather than
blocking the rest of the app on a fine-tune that hasn't happened yet.

**What this means for M1 onward:** the IME/RecognitionService/text-UX
milestones proceed with English as the only supported language for this
release. The `AsrEngine` interface and per-subtype language hook from the
original plan stay as designed — they're what make adding Hindi back in
(via a fine-tune, once one exists) a model swap rather than a rearchitecture.
Nothing needs to be built differently now to keep that door open later; it
just isn't the thing being built next.

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
