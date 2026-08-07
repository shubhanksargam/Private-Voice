# Release checklist — GitHub push & Google Play Store

Nothing on this list has been executed. This is a prep inventory, written
2026-08-06, for when the developer decides to actually push publicly and/or
submit to Play. Re-check anything below against the current state of the
repo before acting on it — this is a snapshot, not a live status.

## GitHub push

- [ ] **README is now current** (rewritten 2026-08-06) — was describing an
  unbuilt M0-only state; the keyboard has been built and in daily use for
  several sessions since. Re-check it's still accurate before pushing.
- [x] **Licence chosen and added (2026-08-08)**: all rights reserved, not
  MIT — the developer decided against a permissive licence despite matching
  the dependency stack. `LICENSE` file at repo root states the source is
  public for viewing only, with no licence granted to use, copy, modify, or
  redistribute it. `README.md`'s Licences section updated to match.
- [x] **`.gitignore` gap closed (2026-08-06)**: `models_mt/` (the EN→HI
  MarianMT ONNX weights, ~167MB across 4 files) was untracked but *not*
  gitignored — one `git add -A` away from bloating the repo with binaries
  that `tools/build_onnx_tokenizer3.py` + `tools/quantize_encoder_with_tokenizer.py`
  can regenerate anyway. Added alongside the existing `models/`,
  `models_ggml/`, `eval/audio/` entries. Confirmed via `git ls-files` that
  no large binary was already committed to history — nothing to purge.
- [ ] **Scrub machine-local paths from tracked docs.** At least one
  reference to a Windows user-profile path
  (`C:\Users\sarga\.claude\plans\...`) existed in `README.md` before this
  rewrite — removed there, but grep the rest of `docs/` for `C:\Users\sarga`
  before pushing publicly; a path like that is meaningless to anyone else
  and mildly reveals local machine/username info.
- [ ] **Decide public vs. private repo, and re-read `docs/STATUS.md` /
  `docs/UI_KEYBOARD_REDESIGN.md` for anything overly personal** — they
  contain the developer's own name (`Your name · Shubhank Sargam` appears
  as a settings *value*, not hardcoded, so it's not in source — but the
  docs' prose is written in first/second person about a specific person's
  own usage and voice recordings; that's fine for a private repo, worth a
  read-through if going public).
- [ ] **Sort out the untracked files sitting at the repo root** —
  `ui.xml` (a stray `uiautomator dump`, dated 2026-08-05) and roughly two
  dozen `.png` screenshots (`kb_screenshot.png`, `voice_panel*.png`,
  `theme_light.png`, etc.), none of them created this session, none
  currently gitignored or committed. Some of the screenshots may be useful
  raw material for the Play Store listing's required screenshots — worth
  triaging (keep useful ones somewhere deliberate, e.g. a `docs/screenshots/`
  folder; gitignore or delete the rest) rather than leaving them loose at
  the root before either push.
- [ ] **Verify the build from a clean clone.** Every setup doc assumes
  `tools/setup_whispercpp.py`, `tools/setup_sherpa.py`, and
  `tools/fetch_ggml_models.py` are run first (vendored/downloaded content is
  gitignored by design) — confirm a fresh `git clone` + documented setup
  steps actually produces a green build, not just "it works on this
  machine because the vendored dirs are already populated."

## Google Play Store

This is the larger lift. Roughly in order of how much they'd block
everything else:

- [ ] **Model distribution has no answer yet, and this is the biggest open
  question.** The app has no `INTERNET` permission *by design*, so there is
  no in-app download path — `SetupActivity.importModel()`'s own comment is
  explicit: "the app has no INTERNET permission by design, so weights
  arrive through the file picker or adb, never over the network." Every
  model used so far (`ggml-base-q8_0.bin` 77MB, `ggml-small-q8_0.bin`
  252MB) was installed via `adb push` + `run-as cp`, or the Storage Access
  Framework file picker pointed at a file already on disk. **A real Play
  Store user has neither option** — they can't adb push, and there's
  nothing to point the file picker at unless they source the `.bin` file
  from somewhere themselves first. Needs an actual decision before this can
  ship publicly, e.g.:
  - Bundle a small model (maybe `base`, 77MB) directly in the APK/AAB as an
    asset, accepting the size cost, and let `small` remain an optional
    picker-based import for users who want better Hindi accuracy.
  - Use Play Asset Delivery / an expansion file for the bundled model so it
    doesn't count against the base APK size limit.
  - Ship with no bundled model and a clear first-run flow explaining "get
    the model file from [X], then import it here" — X being something
    outside Play's own distribution (GitHub Releases, defeats some of the
    "just install and go" simplicity but keeps the zero-network guarantee
    literal).
  - This decision also determines what `docs/PROJECT_OVERVIEW.md`'s "Model
    footprint" numbers mean for a real install — worth revisiting that
    section once decided.
- [ ] **No release signing config exists.** `android/app/build.gradle.kts`'s
  `release` build type only sets `isMinifyEnabled` and proguard files — no
  `signingConfig`. Play requires either Play App Signing (upload key +
  Google-managed signing key) or a self-managed release keystore; neither
  is set up. `isMinifyEnabled = false` for release is also worth
  reconsidering before shipping (larger APK, no shrinking/obfuscation) —
  it's a legitimate choice, just worth being a deliberate one before
  release rather than an inherited default.
- [ ] **Version metadata is still pre-release.** `versionName = "0.1.0-m0"`,
  `versionCode = 1` — the `-m0` suffix specifically references the
  milestone-0 stage this is well past now. Bump both deliberately as part
  of cutting an actual release build, not silently.
- [ ] **Privacy policy required.** Play Console requires a privacy policy
  URL for *any* app requesting sensitive permissions, and this app requests
  both `RECORD_AUDIO` and (optional, opt-in) `READ_CONTACTS`. The app's own
  answer — audio never leaves the device, nothing is transmitted anywhere —
  is a strong, simple story to tell, but it still needs to be a real hosted
  page Play can link to. A GitHub Pages page off the eventual public repo
  is a natural fit once that repo exists.
- [ ] **Data safety form.** Declare `RECORD_AUDIO` (collected, processed
  on-device only, never transmitted, not shared) and `READ_CONTACTS`
  (optional/opt-in, on-device only, used solely to bias dictation toward
  contact names — see `EnglishLoanwordCorrector`/`VoiceImeService`'s
  proper-noun hint lists) accurately in Play Console's questionnaire.
- [ ] **Store listing assets don't exist yet**: app icon, feature graphic,
  phone screenshots, short/long description, content rating questionnaire.
  Nothing in the repo currently produces any of these.
- [ ] **IME-specific Play review considerations.** Apps that request to be
  set as the system input method (and, here, additionally request
  `RECORD_AUDIO` as a keyboard) tend to get closer scrutiny — worth reading
  Play's current policy on input method apps and sensitive permissions
  before submitting, not after a rejection.
- [ ] **`targetSdk`/`compileSdk` currency.** Both are `36` (Android 16) as
  of this writing — check this still satisfies Play's minimum target API
  level requirement at actual submission time, since that floor moves
  roughly yearly.

## Not blocking either, but worth doing first

- The verification gaps in `docs/PROJECT_OVERVIEW.md` §9 (AUTO-mode LID
  routing untested end-to-end with real speech, several keyboard UI
  gestures compile-verified only, `VoiceRecognitionService` never exercised
  by a real external caller) aren't release blockers in the sense of
  "Play will reject this," but shipping them unverified to real users
  is a materially bigger risk than shipping them to an audience of one.
  Worth clearing as much of that list as practical before either push.
