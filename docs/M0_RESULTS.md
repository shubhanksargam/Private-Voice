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

## Decision (2026-08-04): ship English-only for v1 — ⚠️ SUPERSEDED, see below

Option 4 chosen. `base-int8` at 4 threads is the production engine going
forward: 1030ms median, comfortably within budget, ~20% WER on Indian
English (workable, not Google-beating, but usable). Hindi/Hinglish support
is deferred — not built into M1-M4 as a shipping feature — rather than
blocking the rest of the app on a fine-tune that hasn't happened yet.

**This decision rested on invalid Hindi accuracy data.** See the next section.

---

# whisper.cpp backend results (2026-08-04)

Measured after replacing sherpa-onnx with whisper.cpp — see the CORRECTION
section below for why that swap was necessary.

## Latency, GGML q8_0, 4 threads, on a healthy (cool) device

| model | median | verdict |
|---|---|---|
| `ggml-base-q8_0` | **1447ms** | ✅ PASS |
| `ggml-small-q8_0` | ~3970ms | ❌ over the 2500ms budget |

`base` is comfortably within budget and matches what the ONNX backend achieved
(1030ms), while decoding Devanagari correctly — which the ONNX backend did not.

**Quantization format is worth ~3x on ARM and is easy to get wrong.** ggml has
optimized dot-product kernels for q8_0 but not q5_1. Same model, same threads:

| quantization | base median |
|---|---|
| q5_1 | ~3500-3900ms |
| **q8_0** | **~1000-1200ms** |

Benchmarking q5_1 and stopping there would have wrongly concluded whisper.cpp
was too slow to use at all.

## `small` is not merely slow — it is intermittently non-terminating

Beyond its ~4s median, `ggml-small-q8_0` sporadically fails to decode at all.
On `hi_002` — a ~3.5s utterance it usually handles in ~4.3s — it ran past a
30-second abort budget with zero segments produced (`whisper_full` returns -9).
Reproduced across three independent runs.

The mechanism is whisper.cpp's temperature-fallback chain: a decode that trips
its entropy/logprob thresholds is retried at successively higher temperatures,
up to six full decodes. On Hindi, where the model is least confident, fallback
fires often; six times ~4.3s exceeds any usable budget. Observed on one run:
most Hindi utterances ~4.3s, `hi_002` at 14.3s (~3 retries), several timing out.

Disabling the fallback (`temperature_inc = 0.0f`) was tried and is **worse** —
even `base`, previously a clean 1447ms, then timed out during warm-up. The
fallback chain doubles as an escape hatch from decodes that fail to advance
whisper.cpp's seek position. Bound the work with `abort_callback` instead and
leave the default alone.

**For a keyboard this is disqualifying independently of the median.** A model
that intermittently produces nothing cannot back a dictation key.

## ⚠️ Measurements after ~01:50 on 2026-08-04 are INVALID

Roughly three hours of continuous benchmarking left the A35 in a degraded state
that silently corrupted later runs, including a `base` re-measurement that
"failed" everything despite `base` being fine an hour earlier:

- **Thermal cap:** big-core `scaling_max_freq` pinned to 1728000 of 2400000
  (72%). Battery peaked at 39.8°C.
- **Memory pressure:** 2.1GB swapped out, ~300MB of 7.6GB RAM free — the device
  was thrashing.

Neither explains a >20x slowdown alone; together they do. One wrong inference
was drawn from this data before the cause was found (the `temperature_inc`
hypothesis above), which is the concrete cost of benchmarking a saturated
device.

Two observations confirm this was device state and not a broken build:

1. **The degradation was not monotonic.** Mid-way through a run where nearly
   every utterance timed out, `mix_009` completed normally at **1643ms** —
   ordinary `base` speed. A genuinely broken build does not intermittently
   produce correct results at full speed; a system oscillating under memory
   and thermal pressure does.
2. **The device recovered fully once load stopped.** Big-core
   `scaling_max_freq` returned to 2400000 from the 1728000 cap, free RAM went
   from ~300MB to 1.27GB, and swap was released — no reboot required.

Also worth knowing for future runs: Android **restarted the benchmark activity
on its own** several minutes after a `force-stop`, resuming the sweep
unattended. Confirm the process is actually gone (`pidof`) rather than assuming
a force-stop settled it, or a "cooling" device may quietly still be under load.

**Any future sweep must start from a cool, rebooted phone**, and long sweeps
should be split with cooldown gaps. `base`'s 1447ms and `small`'s ~4s were both
captured early while the device was healthy and are the numbers to trust; the
`small` WER scoring is still outstanding because no clean full run completed.

---

# ⚠️ CORRECTION (2026-08-04, same day): the Hindi numbers above are a
# measurement artifact, not model accuracy

**All Hindi/Hinglish WER figures in this document are wrong.** They measured
a text-decoding bug in sherpa-onnx, not Whisper's ability to transcribe Hindi.

## The bug

sherpa-onnx converts each Whisper output token to a string **individually**,
then concatenates the strings. Whisper uses byte-level BPE, so a token is a
sequence of *bytes*, not necessarily a complete character. Devanagari
codepoints are 3 bytes in UTF-8 and Whisper's BPE routinely splits them
across token boundaries. Any token holding a partial UTF-8 sequence isn't
valid text on its own, so it decodes to an **empty string and is silently
dropped**.

Direct evidence — the token stream for `hi_002`:

```
[' म', 'ै', 'ं', ' ', '', 'र', '्', 'स', '', '', 'न', ' ', '', 'ि', 'ल', ...]
                        ^^                ^^  ^^        ^^
```

Those empty strings are exactly where consonants should be. `result.text` is
literally these concatenated, which is why the output reads as orphaned vowel
signs with the consonants missing.

Measured across the Devanagari corpus, sherpa-onnx output retained only
**~40% of expected character length** with **1.6x the normal combining-mark
density**. ASCII is 1 byte/char and never splits, which is why English was
unaffected — and why `mix_011`, the one utterance containing English words
("half day leave"), came through at 103% length while its Devanagari
neighbours were mangled.

Not Android-specific: reproduced byte-for-byte on desktop with sherpa-onnx's
Python bindings and the same ONNX weights. `tokens.txt` itself is fine
(base64-encoded, preserves arbitrary bytes) — the loss happens at
detokenization. Still present in sherpa-onnx 1.13.x.

## The real numbers

Same `whisper-small` weights, same audio, correct byte-level detokenization
(via faster-whisper / CTranslate2):

| split | sherpa-onnx WER | **correct WER** | sherpa-onnx CER | **correct CER** |
|---|---|---|---|---|
| en | 14.17% | **12.50%** | 4.33% | **4.92%** |
| hi | 90.91% | **50.65%** | 62.36% | **21.03%** |
| mix | 89.69% | **66.37%** | 72.72% | **38.10%** |
| ALL | 68.33% | **48.10%** | 49.41% | **24.77%** |

Hindi character error rate improves ~3x (62% → 21%). Hinglish WER lands at
66%, i.e. **inside the published 27-70% code-switch range** — ordinary
for the task, not evidence of a broken model.

Sample of what the same model actually produces when decoded correctly:

```
reference       : आपका पता क्या है, मुझे भेज दीजिए।
sherpa-onnx     : का ता क्या है मे े
correct decode  : अपका पता क्या है, मुझे भेज दिजिए।
```

## Script policy: Devanagari requirement dropped (2026-08-04)

`base` frequently emits Hindi in **Urdu script** (`آپ کا پتہ کیا ہے ?`) or in
Latin romanisation, rather than Devanagari. `small` emits clean Devanagari
essentially always — 5/5 on a spot check where `base` was 0/5.

Two fixes were tried on `base` and neither works:

- **Devanagari initial prompt** (`params.initial_prompt`): unreliable. It
  corrected `hi_003`, but turned `hi_004` into garbage (`drop pen 2 । । ।`)
  and pushed `mix_007` *further* into Urdu.
- **`suppress_regex` over the Arabic block**: returned empty output — the
  `\uXXXX` escape form isn't what whisper.cpp's matcher accepts.

The Urdu is not a tunable setting; it is the same weakness as `base`'s ~102%
Hindi WER and its habit of emitting English translations. Hindi and Urdu are
the same spoken language, so a model that doesn't really know Hindi drifts to
whichever script it is more confident in.

**The requirement was subsequently dropped** — any script is acceptable. That
does not change the model decision. Scoring each model against whichever
script it actually produces:

| model | best-matching script | hi WER | hi CER | mix WER |
|---|---|---|---|---|
| `base` | Latin | **90.91%** | 51.38% | 84.75% |
| `small` | Devanagari | **54.55%** | **20.66%** | 82.06% |

Dropping the constraint helps `base` materially at character level (hi CER
112% → 51%, i.e. it was being penalised for romanising rather than for
mishearing) but leaves word-level accuracy unusable. `small` remains ~36
points better.

Note the mirror artifact: `small` scored against Latin refs reads 103.90% —
meaningless, since it is being penalised for correctly producing Devanagari.
This is the same script-mismatch trap that produced the original false
conclusion about Hindi being hopeless, so **each model must be scored against
its own output script**, never a single fixed one.

## `base` vs `small`, re-decided on valid data

Re-measured with whisper.cpp (correct byte-level detokenization, same decoder
that will run on-device), q5_1 quantization, full 48-utterance corpus:

| model | size | en WER | hi WER | hi CER | mix WER | mix CER |
|---|---|---|---|---|---|---|
| `base-q5_1` | 57MB | 18.33% | **102.60%** | 112.18% | 103.59% | 106.25% |
| `small-q5_1` | 181MB | 13.33% | **54.55%** | **20.66%** | 82.06% | 55.17% |

**`base` is genuinely unusable for Hindi and this is not a decoding
artifact.** Given `-l hi` it emits fluent *English translations* rather than
Hindi transcriptions ("I have submitted the form, but the confirmation has
not come."), a known failure mode where small multilingual Whisper drifts to
English on languages it handles weakly. Correct decoding did not change this;
`base`'s Hindi is simply not there.

**`small` is the minimum viable size for Hindi.** hi CER of 20.66% is in
usable-with-refinement territory; `base`'s 112% is not.

Secondary benefit of GGML over the ONNX export: far smaller on disk for the
same weights, because GGML quantizes the token-embedding matrix too, which
the sherpa-onnx export left in fp32 (`base` 153MB → 57MB, `small` 358MB →
181MB).

### The open question this creates

`small` **failed the latency gate under sherpa-onnx/ONNX Runtime** (4876ms vs
a 2500ms budget). Whether whisper.cpp/GGML — generally faster than ONNX
Runtime for Whisper on ARM CPU — can bring `small` under budget on the A35 is
now *the* deciding measurement for this project. That is what the Android
whisper.cpp backend exists to answer.

For reference, faster-whisper (CTranslate2, int8, beam=5) on the same corpus
scored hi 50.65% / mix 66.37% — somewhat better than whisper.cpp q5_1's
54.55% / 82.06%, particularly on Hinglish. Worth revisiting quantization
(q8_0) and decoding strategy (beam search) as accuracy levers once latency
headroom on-device is known.

## What this changes
- **The English-only decision should be revisited** — it was justified by
  "Hindi is unusable," which is not what the data actually shows.
- **sherpa-onnx cannot ship as the runtime for Hindi** in its current state,
  regardless. The `AsrEngine` interface was designed with `WhisperCppEngine`
  as "backend B, if needed" — this is that case. whisper.cpp accumulates
  bytes across tokens and handles byte-level BPE correctly.
- **The latency numbers remain valid.** The bug affects text output only, not
  compute. `base` passing and `small` failing the 2.5s budget still stands,
  and is still the binding constraint on which model can ship.

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
