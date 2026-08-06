# Private Voice — Project Overview

*A synthesized analysis of the codebase as of 2026-08-06. This document
consolidates `README.md`, `docs/STATUS.md`, `docs/UI_KEYBOARD_REDESIGN.md`,
and `docs/M0_RESULTS.md` into one reference. Those files remain the
authoritative session-by-session history; this is the "read this first"
summary of what the project is, how it's built, and what state it's in.*

---

## 1. What this is

**Private Voice** is a fully offline, privacy-structural Android keyboard
(IME) with voice dictation built in, targeting **Indian English and
Hindi/Hinglish (code-switched) speech**. It ships as:

- A real typing keyboard (`VoiceImeService`, an `InputMethodService`) — not
  a voice-only add-on. Voice is one mode within the keyboard, reached via a
  mic key, not a separate app.
- A system `RecognitionService`, so any app using Android's standard speech
  API (or HeliBoard's mic key) can use the same on-device engine.

**Privacy is enforced structurally, not just promised.** The app declares no
`INTERNET` permission, and `AndroidManifest.xml` explicitly `tools:node="remove"`s
`INTERNET`/`ACCESS_NETWORK_STATE`/`ACCESS_WIFI_STATE` even if a dependency
tries to add them. A Gradle task (`checkNoInternet`) fails the build if any
survive in the merged manifest. A "🔒" privacy-proof glyph on the voice panel
reads the actual live `PackageManager` permissions and displays them — a
runtime check, not marketing copy.

**Target device**: Samsung Galaxy A35 (Exynos 1380, `s5e8835`), no
accessible NPU → CPU-only inference. This constraint drove the entire model
and quantization strategy.

---

## 2. Why Whisper, and why this is hard

| Option | Verdict |
|---|---|
| Whisper (tiny/base/small) | ✅ Hindi + English + punctuation + casing, one model |
| IndicConformer-600M | ❌ 22 Indian languages, but **no English**, no punctuation |
| Conformer/CTC + punctuation model | ❌ no Devanagari punctuation-restoration model exists |
| Streaming Zipformer | ⚠️ fast, but unpunctuated — fallback only |

Whisper is the only single-model option meeting the requirement, at the cost
of latency: it pads every input to a 30-second window, so decode time is
roughly constant regardless of utterance length, and it cannot stream (text
appears after release, not live like Google's on-device RNN-T).

Published code-switch (Hinglish) WER across ASR models generally runs
**27–70%**; models degrade 30–50% relative on code-switched speech versus
monolingual. Beating Google on Hinglish with off-the-shelf weights was never
expected to be easy — this was flagged going in and confirmed by measurement
(§5).

---

## 3. Repository layout

```
android/
  app/      IME service, keyboard UI, settings, phrasebook, M0 benchmark harness
  engine/   AsrEngine abstraction, whisper.cpp (JNI/native) backend, MT engine,
            transliterators, vendored sherpa-onnx Kotlin API (legacy)
tools/      Python: model fetch/quantize, on-device benchmarking, WER scoring,
            ONNX tokenizer construction/verification for the MT model
eval/       Evaluation corpus: audio (gitignored) + reference transcripts
            (Devanagari and Latin), benchmark/independent-check JSON
docs/       STATUS.md, UI_KEYBOARD_REDESIGN.md, M0_RESULTS.md, SETUP.md,
            this file
models/, models_ggml/, models_mt/   Downloaded/converted model weights (gitignored)
```

### `android/engine` module (`dev.privatevoice.engine`)

| File | Role |
|---|---|
| `AsrEngine.kt` | Core interface: `transcribe()`, `detectLanguage()`, `cancel()`, `warmUp()`. Everything above this line is backend-agnostic by design. |
| `AsrEngineHolder.kt` | Process-wide singleton cache holding up to **two** warm engines at once (`Tier.BASE`, `Tier.SMALL`), loaded lazily, matched by filename. |
| `WhisperCppEngine.kt` / `WhisperLib.kt` / `cpp/jni_whisper.c` | **Production backend.** JNI wrapper around vendored whisper.cpp/GGML. Exports `fullTranscribeToString`, `fullTranscribeWithConfidence`, `detectLanguage`, `requestCancel`. |
| `SherpaWhisperEngine.kt` + `vendor/com/k2fsa/sherpa/onnx/*` | **Legacy/superseded backend.** ONNX Runtime via sherpa-onnx. Kept in-tree but not the production path — see §5's detokenization bug. |
| `EnglishToHindiTranslator.kt` / `TranslationEngineHolder.kt` | ONNX Runtime MarianMT (`opus-mt-en-hi`) pipeline for the direction Whisper itself cannot do (see §6). |
| `DevanagariTransliterator.kt` | Devanagari → Latin romanization (schwa-deletion, syllable-scoped). |
| `LatinToDevanagariTransliterator.kt` | Reverse direction, phonetic approximation; also powers the typed transliteration suggestion strip. |
| `EnglishLoanwordCorrector.kt` | ~180-entry curated dictionary correcting English loanwords mangled by forced-Hindi decoding, matched via simplified phonetic key. |
| `AudioRecorder.kt`, `WavIo.kt`, `AsrResult.kt`, `ModelSpec.kt` | Supporting I/O and data types. |
| `BenchmarkRunner.kt` | Drives the M0 device-feasibility sweep (latency gate, per-model). |

### `android/app` module (`dev.privatevoice.app`)

| File | Role |
|---|---|
| `VoiceImeService.kt` (1,278 lines) | The `InputMethodService`. Owns recording state machine, language/tier routing, bold/undo/phrasebook/confidence logic, `InputConnection` interaction. |
| `TextKeyboardView.kt` (1,080 lines) | Full custom-drawn typing keyboard: letters, two symbols pages, emoji grid, phrasebook page, transliteration suggestion strip. |
| `VoiceKeyboardView.kt` (873 lines) | Voice-first panel: mic, language/script toggles, 5-way utility row (ABC/B/☺/undo/backspace). |
| `KeyboardPalette.kt` / `KeyboardSettings.kt` | Theming (Light / Dark / Black-AMOLED) and persisted `SharedPreferences` settings. |
| `PhrasebookStore.kt` / `PhrasebookActivity.kt` | Text-only (never audio) saved-phrase store + CRUD screen. |
| `SetupActivity.kt` | Launcher / settings screen (theme, default language, contacts opt-in, phrasebook entry point). |
| `BenchmarkActivity.kt` | M0 harness UI, not exported — a dev tool kept in the shipping build so models can be re-measured after engine changes. |
| `VoiceRecognitionService.kt` | System `RecognitionService` implementation, so third-party apps can call the same engine via the standard Android speech API. |
| `EmojiData.kt` | ~800 emoji across 9 categories. |

### Native / vendored code

`android/engine/src/main/cpp/whispercpp/` vendors the whisper.cpp + GGML C
source tree (gitignored, restored via `tools/setup_whispercpp.py`). This is
the real inference engine; `jni_whisper.c` is the thin JNI boundary Kotlin
calls into.

---

## 4. Tech stack

- **Kotlin**, AGP **9.1.1** with AGP's *built-in* Kotlin support (no
  separate `org.jetbrains.kotlin.android` plugin — applying both fails with
  a namespace clash; see `docs/SETUP.md`).
- **Native**: whisper.cpp/GGML via JNI, cross-compiled for `arm64-v8a`.
- **ONNX Runtime** 1.22.0 + `onnxruntime-extensions` 0.13.0 — used *only*
  for the EN→HI MarianMT translation model, not for ASR (ASR moved off ONNX
  entirely, see §5).
- **Coroutines** 1.9.0, AndroidX core/appcompat/material/lifecycle.
- **Python tooling** (`tools/`) for model acquisition, quantization, ONNX
  tokenizer graph construction, on-device benchmarking, and WER/CER scoring.
- No test framework beyond stock JUnit dependency (declared, not
  substantially used — this is a device-verified project, not a
  unit-tested one; see §9).

---

## 5. The ASR backend story: sherpa-onnx → whisper.cpp

This is the single most consequential technical arc in the project.

1. **M0 initially measured `base-int8` (ONNX/sherpa-onnx) as fast enough**
   (1030ms median at 4 threads, under the 2500ms budget) **but catastrophic
   on Hindi** (WER 80–109% depending on scoring script). `small` scored
   somewhat better but **failed the latency gate by ~2x** (4876ms).
2. Based on that, the team **decided to ship English-only for v1**
   (`base-int8`, Hindi deferred).
3. **That decision was later found to rest on invalid data.** sherpa-onnx
   detokenizes Whisper's byte-level BPE tokens **one token at a time**, but
   Devanagari codepoints are 3 UTF-8 bytes and Whisper's BPE routinely
   splits them across token boundaries — any token holding a partial UTF-8
   sequence decodes to an **empty string and is silently dropped**. Measured:
   sherpa-onnx retained only ~40% of expected Devanagari character length.
   Reproduced byte-for-byte on desktop, independent of Android.
4. **Fix**: replace the runtime with **whisper.cpp**, which accumulates
   bytes across tokens correctly. Re-measured on real hardware:

   | model | quant | median latency | verdict |
   |---|---|---|---|
   | `ggml-base-q8_0` | q8_0 | **1447ms** | ✅ pass |
   | `ggml-small-q8_0` | q8_0 | ~3970ms | ❌ over 2500ms budget |

   Quantization format matters ~3x on ARM: the same `base` model at q5_1 ran
   3500–3900ms (ggml lacks optimized dot-product kernels for q5_1); q8_0 is
   the one to ship.
5. **Corrected accuracy** (whisper.cpp, byte-correct decode, `small`):
   Hindi CER dropped from a falsely-measured 62% to a real **21%** — inside
   the published Hinglish WER range, not evidence of a broken model.
6. **Current standing model decision**: `base` is fast but genuinely weak on
   Hindi (it drifts to fluent *English translations* or Urdu script rather
   than transcribing Hindi — not a decoding artifact). `small` is the
   minimum viable size for usable Hindi but costs ~3x the latency. Rather
   than pick one, **`AsrEngineHolder` now holds both, warm, simultaneously**,
   and routing (per-utterance, based on language hint + cheap language-ID)
   decides which tier a given utterance uses — see §6.
7. `small`'s decode was also found to be **intermittently non-terminating**
   under whisper.cpp's temperature-fallback retry chain on hard Hindi audio
   (observed hangs past 30s, once past 180s) — mitigated with a bounded
   `abort_callback` and a user-facing cancel gesture (tap mic during
   "Transcribing" state), not eliminated. Still an open risk under sustained
   load (§9).
8. Long, unrelated benchmarking sessions (~3 hours continuous) were found to
   thermally/memory-degrade the A35 itself, silently invalidating later
   measurements in the same run (>20x slowdown, non-monotonic, recovered
   without reboot). Any future benchmark sweep must start from a cool,
   rebooted phone.

**Open decision the project made, not yet fully re-validated end-to-end**:
a Hindi-specific *fine-tune* (e.g. `vasista22/whisper-hindi-*`) is flagged as
the strongest lever for real Hindi accuracy — scaling stock Whisper from
`base`→`small` narrowed but did not close the gap (word-level WER stayed
90%+ at both sizes even with correct decoding on the *worst-case* scoring;
real numbers with correct detokenization landed at 50–66% for `small`,
inside normal Hinglish range but still nowhere near English-level quality).
No fine-tune has been trained or integrated as of this writing — the shipped
solution is the dual-tier routing described in §6, not a fine-tune.

---

## 6. Runtime language/model routing (current behavior)

Computed per-utterance inside `VoiceImeService.finishRecording()`:

| `languageHint` | `devanagariMode` | Routing |
|---|---|---|
| EN | either | `base`, `translate=true`, `language=null` — auto-detect source language, then Whisper's built-in translate-to-English task |
| HI | either | Cheap language-ID pass on `base` first. Looks Hindi (or the MT model is missing) → `small`, forced Hindi, English prompt hint skipped, loanword correction applied. Looks English *and* the MT model is present → `base`, forced-English clean transcription, then run through `EnglishToHindiTranslator` |
| Auto | on ("अ") | Treated as HI (forces Hindi intent) |
| Auto | off ("A") | Cheap LID pass on `base`; Hindi detected → `small`+Hindi; else → `base` with the detected language |

Key supporting mechanism: **`detectLanguage()`** is a *cheap* language-ID
primitive (`whisper_lang_auto_detect` — one encoder pass + a single decode
step reading language-token logits), **not** a full autoregressive decode,
used to route without paying for two full transcriptions. It returns not
just the top language but English/Hindi probabilities specifically, so
code-switched audio where English "wins" but Hindi probability is
non-trivial (≥ `HINDI_PROB_THRESHOLD`, currently an **untuned 0.15 guess**)
still routes to the Hindi path.

**Why a second, separate MT model exists**: Whisper's `translate` task only
translates *into* English — there is no flag that makes it translate out of
English. Confirmed as a hard architectural limit, not a missing parameter.
The EN→HI direction required building an entirely separate pipeline:
`opus-mt-en-hi` (Helsinki-NLP, MarianMT, Apache-2.0) exported to ONNX,
quantized int8 (~167MB total across encoder/decoder/decoder-with-past/vocab),
run via ONNX Runtime + the `onnxruntime-extensions` SentencePiece custom op.
Two non-obvious bugs were found and fixed building this (full provenance in
`EnglishToHindiTranslator`'s KDoc and `tools/build_onnx_tokenizer3.py` /
`tools/verify_onnx_final.py`):
- MarianTokenizer's model vocab (61,950 entries) is a separate ID space from
  the raw SentencePiece model's internal piece IDs (32,000 entries) —
  encoding needs a `Gather`-based remap baked into the ONNX graph.
- MarianMT's decode side isn't a real SentencePiece decode at all — just a
  vocab reverse-lookup + `"▁"→" "` join, implemented as plain Kotlin string
  logic, no SentencePiece needed on decode.

---

## 7. Keyboard UI/UX (current design)

Both keyboard surfaces (`TextKeyboardView`, `VoiceKeyboardView`) are fully
custom `Canvas`-drawn `View`s with **no XML layout and no accessible widget
tree** (aside from `PhrasebookActivity`, a real `Activity`). This has direct
consequences for how the project can be tested (§9).

### Theming
Three themes, cycled from Settings: **System → Light → Dark → Black →
System**. Light is a "burnt paper" beige (`#EDE3CE`) with warm-brown text.
Dark is a cool blue-grey "brushed metal" (`#242B33`) with white text — an
explicit correction away from reusing the beige as foreground (read as
muddy). Black is pure AMOLED (`#000000`). Both panels' backgrounds are drawn
with rounded top corners (18dp), flush/square at the bottom.

### The "B" (bold) key — most-iterated feature in the project
- **Single tap**: toggles bold-arming for future typed/dictated text, *and*
  if there's a live text selection, toggles bold on that selection
  specifically (real per-selection toggle, using
  `GET_TEXT_WITH_STYLES` + `TextPaint` span resolution — works against
  *any* app's bold spans, not just this keyboard's own).
- **Double tap**: if the first tap touched a selection, saves that
  selection's text to the phrasebook and **reverts the field's formatting**
  to exactly what it was before the first tap (a save shouldn't leave an
  unrequested formatting change behind). If nothing was selected, just
  opens the phrasebook page.
- The B key itself **never changes color** based on state (explicitly
  rejected by the user twice, once for a muted/fg toggle, once for an
  accent-red fill) — it's statically muted like other utility glyphs. The
  glyph is drawn in a bold typeface unconditionally as a fixed label, not a
  state indicator.
- Implementation subtlety worth preserving: every tap's effect fires
  **instantly** (no delay-based double-tap disambiguation) — an earlier
  delayed-single-tap version caused a real bug where typing immediately
  after tapping B produced unbolded text. A following double-tap instead
  acts retroactively on **cached** state from the first tap.

### The emoji key — a different gesture split than B, on purpose
Single tap opens the emoji page (in-keyboard grid on `TextKeyboardView`;
`VoiceKeyboardView` switches to text mode and delegates). **Long press**
(not double-tap — explicitly corrected back after an intermediate
double-tap version) opens the phrasebook.

### Other shipped features
- **~800-emoji grid**, 9 categories, drag-to-scroll.
- **Two symbols pages** (`?123` → `=\<` toggles page 2: brackets/currency/
  math/misc), mirroring Gboard's convention.
- **Tap-to-talk mic** (not hold): tap starts, tap again while listening
  stops+transcribes, tap while transcribing cancels the in-flight decode.
- **Script toggle** (A/अ, Hindi-forced-only) and **language toggle**
  (Auto/EN/HI) on the voice panel, both persisted.
- **Personal phrasebook**: voice- or type-created, CRUD via
  `PhrasebookActivity`, reachable from both panels.
- **Typed transliteration suggestion strip**: live Latin→Devanagari
  suggestion above the key rows, recomputed from scratch per keystroke (not
  incrementally tracked, so it can't drift).
- **Undo last dictation**: long-press mic while idle, deletes exactly the
  last voice-committed span (tracked by character length, invalidated by
  any manual edit).
- **Selection-aware voice correction**: dictating while a field has an
  active selection replaces it instead of inserting at the cursor.
- **Battery/thermal-aware fallback**: low battery (not charging) or elevated
  thermal state forces any `small`-tier routing decision down to `base` for
  that utterance.
- **On-keyboard privacy proof** ("🔒"): live `PackageManager` permission
  read, not static copy.
- **Per-word confidence underlining**: a *separate* native export
  (`fullTranscribeWithConfidence`, deliberately not a refactor of the
  existing transcribe path, to keep it isolated) flags words whose minimum
  token probability falls below 0.5 and underlines them via `UnderlineSpan`.
  Only works on paths where raw Whisper text reaches the field largely
  unchanged (English/Latin) — transliteration/loanword-correction/MT paths
  rewrite the text enough that flagged words can't be re-matched, so those
  paths silently get no markup rather than wrong markup.

### Explicitly rejected/superseded designs (do not reintroduce without being asked)
Documented in detail in `docs/UI_KEYBOARD_REDESIGN.md`: mic-long-press for
phrasebook-save (moved to B), long-press-B for phrasebook-save (moved to
double-tap), delayed single-tap disambiguation (caused the bold bug above),
B changing color by state (rejected twice), emoji double-tap-to-phrasebook
(reverted to long-press), "always bold on tap" regardless of selection.

---

## 8. Evaluation & benchmarking infrastructure

- **`eval/`**: a 48-utterance corpus (12 English, 12 Hindi, 24 Hinglish),
  the user's own recordings, with both Devanagari and Latin-script reference
  transcripts (`eval/refs/`, `eval/refs_latn/`) — necessary because Whisper
  emits Devanagari for Hindi/mixed but people generally type Hinglish in
  Latin, so scoring against only one script produces misleading numbers
  (learned the hard way, see §5).
- **`tools/bench_device.py`** drives `BenchmarkActivity` on a connected
  device, applies Doze/App-Standby whitelisting and `stayon` automatically
  (screen-off was found to silently freeze the benchmark for 20+ minutes
  twice before this fix), tabulates results, and applies the latency gate
  (`BenchmarkRunner.TARGET_MILLIS` = 2500ms).
- **`tools/eval_wer.py`**: Devanagari-aware WER/CER scoring.
- **`tools/setup_sherpa.py`** / **`tools/setup_whispercpp.py`**: vendor the
  respective native backends (gitignored source, restored on demand).
- **`tools/fetch_models.py`** / **`fetch_ggml_models.py`**: download and
  `adb push` model weights.
- **ONNX MT tooling**: `build_onnx_tokenizer{,2,3}.py`,
  `quantize_encoder_with_tokenizer.py`, `verify_onnx_{e2e,final}.py`,
  `verify_opus_mt_onnx.py`, `debug_tokenizer.py`, `push_mt_model.py` — the
  iterative construction/verification of the EN→HI MarianMT ONNX pipeline
  (§6), including two non-obvious bugs found along the way.

---

## 9. Current state, honestly

**Installed and in daily real use** on the developer's Samsung SM-A356E.
Bilingual Hindi/English dictation works; EN→HI translation is verified
on-device; the dual-tier routing pipeline is live.

**What has NOT been verified**, and is the actual next-step list:

1. **AUTO-mode language-ID routing and the HI-forced English→translation
   trigger** (§6) — built and unit-verified in isolation, but the
   LID-gated trigger inside `VoiceImeService.finishRecording()` has never
   been exercised with real live speech end-to-end.
2. **The `HINDI_PROB_THRESHOLD` (0.15)** used to detect code-switching is an
   untuned first guess.
3. **`small`'s intermittent non-terminating decode** under sustained load
   (§5) is a known risk, only partially mitigated (user-facing cancel), not
   reproduced or eliminated under the lighter usage seen so far.
4. **Most of the keyboard UI session's work** (emoji long-press, rounded
   corners, theme cycling, symbols page 2, layout reorders) has only been
   verified by "compiles + installs cleanly," not by an actual human tap
   on-device — see the checklist in `docs/UI_KEYBOARD_REDESIGN.md#do-this-next`.
   This is deliberate: two earlier blind coordinate-based `adb shell input
   tap` attempts went astray (typed garbage into the launcher search, opened
   the user's real WhatsApp chat list), so this project now avoids
   coordinate-guessing taps entirely in favor of compile-verification and
   code review, or deterministic `am start`/`uiautomator dump` when visual
   confirmation is unavoidable. **The B key's selection bold/un-bold is the
   one exception** — real-device-tested and fixed (2026-08-06). Two genuine
   bugs surfaced: `isFullyBold()` required one span to cover an entire
   selection, but text typed while bold is armed commits one character at a
   time, so it's actually several adjacent single-character spans — fixed by
   checking boldness position-by-position instead. Deeper bug:
   `InputConnection.commitText()` replacing a selection in place doesn't
   clear the *old* span pinned to that position, even when the replacement
   text carries none of its own (`Editable.replace()` semantics), so
   un-bolding silently failed and the toggle got stuck reporting "still
   bold" indefinitely — fixed by deleting the selection and inserting the
   replacement as two separate edits. See
   `docs/UI_KEYBOARD_REDESIGN.md`'s "Session update — 2026-08-06 (continued)"
   for the full account. Bold-arming's default was also finalized: **off**
   until B is actually touched, capslock-style (not armed from a fresh
   field).
5. **The 8 "eight more features"** (emoji panel, battery fallback, privacy
   proof, undo, selection-replace, transliteration strip, phrasebook, and
   confidence underlining) were smoke-tested for "builds and doesn't crash,"
   with only the confidence-underlining feature getting a real-audio pass
   (via recorded eval WAVs, not the live mic).
6. **`VoiceRecognitionService`** (the third-party `RecognitionService`
   surface, e.g. for HeliBoard's mic key) is built but unverified against
   an actual external caller.
7. No fine-tune has been trained; the project's own M0 analysis identifies
   fine-tuning as the strongest remaining lever for real Hindi/Hinglish
   accuracy, still unactioned.

**Milestone status** (per the original plan,
`C:\Users\sarga\.claude\plans\expressive-beaming-backus.md`): M0 (device
feasibility) done, though its model decision reversed mid-project. M1/M2
(engine + IME, both voice and typing) built and in real use. M3
(RecognitionService) built, unverified. M4 (voice commands, inverse text
normalization, undo — undo is partially done ad hoc) and M6 (privacy
hardening, polish) not started.

---

## 10. Licensing

Whisper: MIT. sherpa-onnx: Apache-2.0 (legacy/unused path). IndicConformer:
MIT (evaluated, not used). `opus-mt-en-hi` (Helsinki-NLP): Apache-2.0.
Verify licensing of any future fine-tune before shipping it.

---

## 11. Release readiness (Google Play Store + GitHub)

Both are planned but **not yet executed** — no public repo push, no Play
Store submission. Full inventory: **[`docs/RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md)**.
The two items most likely to actually block a public release, in short:

- **Model distribution has no answer yet.** The app has no `INTERNET`
  permission by design, so there's no in-app download path — every model
  used so far arrived via `adb push` or a file picker pointed at a file
  already on disk. A real Play Store user has neither option. Needs a
  decision: bundle a model in the APK/AAB (size cost), use Play Asset
  Delivery, or ship with a documented manual-import flow.
- **No release signing config, version metadata still reads `0.1.0-m0`,**
  and none of the Play Store listing assets (icon, screenshots, privacy
  policy page, data safety answers) exist yet.

`.gitignore` had one real gap closed already (2026-08-06): `models_mt/`
(~167MB of EN→HI MarianMT weights) was untracked but not ignored, one
`git add -A` away from landing in history. Nothing had actually been
committed yet, so no cleanup was needed — just the gitignore entry.

---

## 12. How to pick this project back up

- **Read `docs/STATUS.md` before touching any ASR/translation/routing
  code** — it documents 18 numbered root-cause findings, several
  counter-intuitive (e.g. an English `initial_prompt` actively fighting a
  Hindi-forced decode; sherpa-onnx's silent Devanagari byte-drop).
- **Read `docs/UI_KEYBOARD_REDESIGN.md` before touching the B key, emoji
  key, phrasebook, or either panel's utility row** — several of these went
  through 3-4 rounds of build-report-fix and the current behavior is not
  what a partial git-history read would suggest.
- **`docs/M0_RESULTS.md`** is the full benchmarking narrative, including the
  sherpa-onnx detokenization bug discovery and the device-thermal-degradation
  false-signal incident — useful context before re-running any benchmark.
- **`docs/SETUP.md`** has the toolchain recipe (AGP 9 + built-in Kotlin
  gotchas, `JAVA_HOME` pointing at the Android Studio JBR, `adb` PATH).
- The single highest-value next action, per the project's own records, is
  **real human on-device testing** — both the ASR routing paths (§9 items
  1–3, 5–6) and the UI gestures (§9 item 4) — not new feature work.
