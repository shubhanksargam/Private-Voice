# Session status — resume point

Last updated: 2026-08-04. Repo at commit `336e556`. Working tree clean.

## One-line state

**The keyboard exists and builds, but has never been run on the phone.**
`:app:assembleDebug` passes. Next session's first job is: install, enable the
IME, import a model, and dictate into a real text field. Nothing below this
line has been touched by a device yet.

## Do this next

```powershell
cd D:\pj\android
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:installDebug --console=plain

# Launch SetupActivity (the launcher icon), or:
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell am start -n dev.privatevoice.app/.SetupActivity
```

Then, on the phone, walk the 3-step checklist SetupActivity shows:
1. Grant microphone — tap the "Allow microphone" line.
2. Add a model — tap "Add a speech model", pick a `.bin` from
   `models_ggml/` (already has `ggml-base-q8_0.bin` and
   `ggml-small-q8_0.bin` from the M0 runs — **use `base`**, see the model
   decision below). The picker reads from wherever the phone's file browser
   can reach; you may need to `adb push` a model into `Download/` first
   since the app itself can't fetch it (no INTERNET permission).
3. Enable — opens system input-method settings; toggle Private Voice on,
   accept the security dialog.

Then switch to it (globe icon on any keyboard, or the "Switch keyboard"
button SetupActivity shows once all three steps are done) and hold the mic
in a real text field.

**Watch for, in rough order of how likely each is to bite:**
- Does `commitText` actually land at the cursor, cleanly, with correct
  spacing before/after existing text?
- Does the amplitude-reactive pulse actually look right against a real
  finger hold, not just in imagination? (`VoiceKeyboardView.kt`)
- Cold model load is ~350-550ms (from M0 numbers) — does the UI feel broken
  during that gap on first press after enabling, before the background warm
  in `onCreateInputView` has finished?
- `switchToPreviousInputMethod()` — confirm it actually returns to
  HeliBoard/Gboard/whatever, not back to itself.
- RECORD_AUDIO permission flow: deny it once, confirm the "tap to grant"
  message appears and tapping the mic opens SetupActivity rather than
  silently failing.

## Model decision: use `base`, not `small` — for now

This is a **provisioning choice**, not a code choice — nothing in
`AsrEngineHolder` picks a specific model, it just takes the largest `.bin`
present in `files/ggml/`. So which model ships is entirely about which file
you import in step 2.

- `base` (q8_0, 78MB): 1447ms median on-device, comfortably under a 2.5s
  budbudget. English WER ~18-20%. **This is what should be in
  `files/ggml/` for real use right now.**
- `small` (q8_0, 252MB): far better Hindi (WER 54.55% vs base's ~91%,
  CER 20.66% vs 51.38%), but ~4s median — over budget — **and
  intermittently non-terminating**, reproduced across three separate runs
  (>30s, sometimes >180s, on ordinary 3-4s utterances). Do not ship this
  as-is. See `docs/M0_RESULTS.md` for the full data and the open question
  about whether that instability is device-state-dependent or inherent.

`AsrEngineHolder.pickModel()` will silently pick `small` over `base` if both
are present, since it just takes the largest file. **Only put `base` in
`files/ggml/` until `small`'s reliability question is resolved** — either
delete `small` from the device, or don't import it in the first place.

## What's built (all committed, none device-tested)

- **`AudioRecorder`** (`engine/.../AudioRecorder.kt`) — AudioRecord capture,
  16kHz mono float, `VOICE_RECOGNITION` source (avoids telephony AGC/noise
  suppression distorting the signal). No VAD by design — Whisper pads to a
  30s window regardless, and the held key already marks start/stop. Tracks
  smoothed RMS `amplitude` for the UI.
- **`AsrEngineHolder`** (`engine/.../AsrEngineHolder.kt`) — process-wide
  singleton, shared by the IME and RecognitionService so a second entry
  point doesn't reload the model. `pickModel()` takes the largest `.bin` in
  `files/ggml/` — see the model decision above for why that matters.
- **`VoiceImeService`** — the keyboard. Hold-to-talk, refuses password
  fields (checks `TYPE_TEXT_VARIATION_PASSWORD` and siblings), requires
  `RECORD_AUDIO` before recording, warms the model in
  `onCreateInputView()`.
- **`VoiceKeyboardView`** — canvas-drawn, no XML/drawables at all. One mic
  target reacting to live amplitude, a status line, two low-contrast
  utility glyphs (switch keyboard / backspace). Colour appears only while
  recording — that's the entire signal vocabulary. Built this way because
  the user asked for "ultra modern and minimalistic."
- **`VoiceRecognitionService`** — implements `android.speech.RecognitionService`
  so HeliBoard's mic key or any `SpeechRecognizer` caller can use this
  engine once selected under Settings → Voice input, without the user
  switching keyboards. Caller-driven lifecycle with a 30s hard cap for
  callers that never call `stopListening()`.
- **`SetupActivity`** — replaces the bare launcher. 3-step checklist, no
  cards/icons, matching the keyboard's style. Model import is via SAF file
  picker (`OpenDocument`), never a download — the app holds no INTERNET
  permission, full stop.
- **Manifest** — IME + RecognitionService declared. IME has one subtype
  (`en_IN`) even though the language-routing plumbing supports more; see
  "English-only for v1" below.

## Standing decisions (context, not new work)

- **English-only for v1.** Stock Whisper (any size tested) is not
  accurate enough on Hindi/Hinglish to beat Google — `base` emits English
  translations or wrong-script text instead of transcribing Hindi;
  `small` is more accurate but fails the latency/reliability bar. Full
  writeup: `docs/M0_RESULTS.md`. The `AsrEngine` interface and per-subtype
  language hook exist specifically so adding Hindi later (via a fine-tune)
  is a model swap, not a rearchitecture — nothing needs restructuring to
  keep that door open.
- **whisper.cpp, not sherpa-onnx**, as the ASR backend. sherpa-onnx has a
  byte-level-BPE detokenization bug that silently drops ~60% of Devanagari
  characters while leaving ASCII untouched — this produced a false "Hindi
  is unusable" conclusion early in the session that was later corrected.
  Full narrative in `docs/M0_RESULTS.md`.
- **Devanagari-output requirement was dropped** at user request. Verified
  this doesn't change the model decision either way — `base`'s Hindi WER is
  still ~91% even scored against its own preferred (Latin) output.
- **Quantization: q8_0, not q5_1.** ~3x faster on ARM (ggml has optimized
  dot-product kernels for q8_0, not q5_1). Getting this wrong makes
  whisper.cpp look unusably slow when it isn't.

## Known unresolved risk: on-device instability under sustained load

Three separate benchmark runs saw `whisper_full` hang past 30s (once past
180s) on ordinary Hindi utterances that normally decode in ~4s, always after
the phone had been under continuous benchmark load for a while. A reboot
fixed it every time; the same audio then decoded in 1.5s. Two hypotheses
were tested and ruled out (thermal throttling — thermal service reported
Status: 0, never throttling; Battery Saver capping clocks — confirmed and
fixed for its own effect, but instability recurred even with Battery Saver
off and thermal healthy). Most likely a swap/page-cache exhaustion effect
from repeatedly loading 250MB+ models many times over hours, but this is not
confirmed.

**This has not been tested during light, realistic use** — every
observation so far comes from 40+ back-to-back synthetic decodes, which is
nothing like a real dictation session's occasional bursts. It may simply not
apply. This is exactly what the "watch for" list above is partly aimed at
catching, informally, during real use.

## Where things are on disk

| Path | State |
|---|---|
| `android/` | Builds clean (`:app:assembleDebug` verified this session) |
| `android/engine/src/main/cpp/whispercpp/` | Vendored whisper.cpp source (gitignored — `python tools/setup_whispercpp.py`) |
| `android/engine/src/main/vendor/`, `.../jniLibs/` | Vendored sherpa-onnx — **no longer used by the app**, kept only because `BenchmarkRunner` can still sweep it for comparison. Candidate for removal once confirmed unneeded. |
| `models_ggml/` | Exactly two files: `ggml-base-q8_0.bin` (78MB), `ggml-small-q8_0.bin` (252.2MB). No q5_1 leftovers. |
| `eval/prompts.jsonl`, `eval/refs/`, `eval/refs_latn/` | Committed, complete, unrelated to shipping work now |
| `eval/benchmark.json`, `eval/wcpp_*.jsonl` | Gitignored (contains transcribed speech) — M0 raw data, superseded by `docs/M0_RESULTS.md`'s aggregate numbers |
| Phone | **Not connected as of this writing — unverified.** As of the last M0 session it had `ggml-base-q8_0.bin` in the app's `files/ggml/` and the OLD BenchmarkActivity-only build installed (predates this session's IME/RecognitionService code). Confirm with `adb devices` and `dumpsys package dev.privatevoice.app` before assuming; `:app:installDebug` will update it regardless. |

## If resuming on a different machine

`docs/SETUP.md` has the full non-interactive toolchain provisioning recipe.
`git clone`, then:

```powershell
python tools\setup_sherpa.py       # only if re-testing the old backend
python tools\setup_whispercpp.py
python tools\fetch_ggml_models.py --models base --quant q8_0
# ...follow docs/SETUP.md's SDK section if adb/gradlew aren't present
```

## Longer-term context

Full milestone plan: `C:\Users\sarga\.claude\plans\expressive-beaming-backus.md`.
M0 (device feasibility) is done. M1-M3 (engine, IME, RecognitionService) are
built but unverified on-device — that verification is the very next step, not
a "later" milestone. M4 (text UX: voice commands, ITN, undo) and M6 (privacy
hardening, polish) haven't been started.
