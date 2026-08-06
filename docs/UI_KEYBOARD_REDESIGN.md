# Keyboard UI redesign — session log (2026-08)

This documents a long, mostly UI/UX-focused session that came *after*
everything in `docs/STATUS.md` (the Hindi/translation ASR saga + "eight more
features"). That file is still accurate for the ASR/translation pipeline and
is not re-explained here. This file is the resume point for the **keyboard
surface itself** — `TextKeyboardView`, `VoiceKeyboardView`, `VoiceImeService`'s
UI-facing code, `KeyboardPalette`, `KeyboardSettings`, the phrasebook, and
settings navigation.

Read this before touching the "B" (bold) key, the emoji key, the phrasebook,
or either panel's utility row — several of these went through 3-4 rounds of
"user tries it, reports a bug or a design change, gets fixed" and the current
behavior is *not* what a first read of the code's git history would suggest
if you stopped partway through.

## Current design (end state — read this section if you just need "what does
it do now", not the history)

### Visual palette (`KeyboardPalette.kt`)

- **Light mode**: "burnt paper" beige background (`#EDE3CE`), dark warm-brown
  text (`#2B2620`).
- **Dark mode**: cool desaturated blue-grey, "brushed metal" (`#242B33`),
  plain white text (`#FFFFFF`). Explicitly *not* the beige reused as
  foreground — read as muddy/low-contrast in practice.
- **Black mode** (`KeyboardSettings.Theme.BLACK`, third theme, new this
  session): pure AMOLED black (`#000000`) background, same white text.
  `KeyboardPalette.bg(dark, black)` takes a second `black` flag; `muted()`,
  `ring()`, `keyPressedBg()` all derive from `bg()` via `ColorUtils.blendARGB`,
  so they automatically shift darker too — no separate black-mode color set
  needed. `KeyboardSettings.isPureBlack(context)` reports which dark variant
  is active; `isDark()` is true for both DARK and BLACK (most call sites only
  care about the light/dark split, not which dark variant).
- Theme cycles System → Light → Dark → Black → System from the "Theme" row in
  Settings (`SetupActivity`), one tap per step.
- Typeface: classic serif (`Typeface.create("serif", NORMAL)`), platform
  built-in, no bundled font asset.
- **Rounded top corners** (new this session): both panels now fill their
  background via a `Path`/`addRoundRect` with only the top-left/top-right
  corners rounded (`KeyboardPalette.TOP_CORNER_RADIUS_DP = 18f`), bottom
  square (flush with the screen edge/nav bar). `TextKeyboardView.drawBackground()`
  and `VoiceKeyboardView.drawBackground()` both implement this identically;
  called first thing in `onDraw()`, replacing the old flat `canvas.drawColor(bg)`.

### The "B" (bold) key — identical behavior on both panels, this took the most iteration

**Single tap** (instant, no delay):
- Toggles `TextKeyboardView.boldActive` — arms/disarms bold for whatever gets
  typed or dictated *next* (read by `VoiceImeService.styledText()` for typed
  text, `applyBoldSpan()` for dictated text).
- **If there's a current text selection in the field**, also toggles bold
  *on that selection specifically*, independent of the future-typing state:
  already-bold selection → un-bolds; not-bold selection → bolds. This is a
  genuine per-selection toggle (`VoiceImeService.boldSelectionIfAny()` +
  `isFullyBold()`), not "always bold on tap."
  - Bold detection is **general**, not limited to spans this keyboard itself
    committed: `isFullyBold()` requests the selection with
    `InputConnection.GET_TEXT_WITH_STYLES`, then resolves every
    `CharacterStyle` span covering the *entire* selection against a real
    `android.text.TextPaint` (`span.updateDrawState(paint)`) and checks
    `paint.isFakeBoldText || paint.typeface?.isBold`. This correctly detects
    bold text from other apps/editors, not just this keyboard's own
    `StyleSpan(Typeface.BOLD)`. A **partially**-bold selection is treated as
    "not bold" (bolds the whole thing on tap, doesn't strip the part that
    already was).
  - Caveat: this only works if the host app's `EditText` actually honors
    `GET_TEXT_WITH_STYLES` and preserves spans in its `Editable` — most
    standard `EditText`s do; a custom text widget that ignores the flag just
    always reports "not bold" (bolds every tap, same as the pre-toggle
    behavior, not a regression, just no un-bold in that specific app).
- **No color change on the B key itself, ever.** It's drawn statically muted
  (same treatment as ABC/Emoji/Mic/backspace — the other "utility" glyphs),
  on both panels. This was an explicit late correction: earlier versions
  changed B's color (or its typeface) based on `boldActive`; the user
  rejected both — B's function depends entirely on the current selection,
  which a fixed on/off key color can't represent meaningfully anyway.
- **The "B" glyph itself is always drawn in a bold typeface**, unconditional
  — this is B's *natural* letterform (it's the letter B, drawn bold, as a
  permanent design choice/label), not a toggle-state indicator. Don't
  conflate this with the (now-removed) color-based state indicator.

**Double tap** (within `DOUBLE_TAP_MS` — 350ms on `TextKeyboardView`, 300ms on
`VoiceKeyboardView`; the two constants aren't unified, just each internally
consistent):
- If the field had a selection (i.e. the first tap's `boldSelectionIfAny()`
  found and toggled one): saves that selection's text to the phrasebook
  (`VoiceImeService.saveSelectionToPhrasebook()`), **and reverts the field's
  formatting back to exactly what it was before the first tap** (spans
  included — restores `lastSelectionBeforeBolding`, not just "plain text").
  Rationale: double-tap-B's job is *saving to the phrasebook*, not
  formatting the field — a save shouldn't leave a visible, unrequested
  formatting change behind, whichever direction the first tap's toggle went.
- If nothing was selected: falls back to just opening the phrasebook page
  (`TextKeyboardView.showPhrasebookPage()` / `VoiceKeyboardView.onOpenPhrasebook`).
- Implementation note (why this isn't a "delay the single tap" pattern):
  every tap's single-tap effect (toggle + selection bold/un-bold) fires
  **immediately**, no waiting to see if a second tap follows. An earlier
  version delayed the single tap by the double-tap window to disambiguate
  first — this was a real, reported bug ("B + voice does not render bolden
  text" / effectively "bold does nothing"): typing immediately after tapping
  B (completely normal) produced unbolded characters because the toggle
  hadn't fired yet. Fixed by making every tap instant again, and instead
  having the service **cache** the plain text and the original spanned
  `CharSequence` from whatever `boldSelectionIfAny()` last touched
  (`lastBoldedSelectionText: String?`, `lastSelectionBeforeBolding:
  CharSequence?`) so a *following* double-tap can still act on "what tap 1
  touched" even though the live selection is already gone (replaced by
  tap 1's own commit).
- Order of operations matters inside the tap-release handler: the
  double-tap branch (`onDoubleTapBoldSave`/`saveSelectionToPhrasebook`) must
  run **before** the current tap's own `onToggleBold`/`boldSelectionIfAny()`
  call — otherwise tap 2's own (harmless, nothing-selected) call to
  `boldSelectionIfAny()` clobbers the cached selection before the save logic
  gets to read it. Both `TextKeyboardView.onBoldTapped()` and
  `VoiceKeyboardView`'s inline B-handling in `onTouchEvent` do this in the
  same order; keep them in sync if either changes.

### Session update — 2026-08-06 (continued): the B key's real bold/un-bold
bugs, found and fixed on real-device testing

The two items above ("Caveat: this only works if the host app...") were
written before this key was ever actually exercised on-device — see
"Verification approach" below, which flagged this exact gesture as untested.
It has since been tested for real, on a real `EditText`, and two separate,
concrete bugs were found and fixed. Neither was a host-app quirk.

1. **`boldActive`'s default was flipped to `true` (bold-armed from a fresh
   field), then reverted back to `false` after user testing.** Typing before
   ever touching B must stay plain — B arms bold like caps-lock arms
   capitals, off until pressed. `TextKeyboardView.boldActive`'s declaration
   and `resetToLetters()` (fired on a genuinely new field, per
   `VoiceImeService.onStartInputView`'s `!sameField` branch) both default to
   `false`. Don't change this back to `true` without being asked again — it
   was tried and explicitly rejected: "I just want the Bold to be activated
   once B is touched initially."
2. **Bug 1 — `isFullyBold()` required a single span to cover the whole
   selection.** Text typed while bold is armed commits **one key at a
   time** (`VoiceImeService.styledText()`/`applyBoldSpan()`, called per
   keystroke), so a bold word typed that way ends up as several *adjacent
   one-character* `StyleSpan`s in the host's `Editable` — none of which
   alone spans the full selection, even though every character in it is
   visibly bold. The old `isFullyBold()` required one span with
   `getSpanStart() == 0 && getSpanEnd() == length`, so this — the common
   case in practice — always read as "not bold" and silently re-bolded
   instead of un-bolding. Fixed by walking span-transition boundaries
   (`Spanned.nextSpanTransition`) and checking boldness position by
   position, requiring every character to be bold via *whichever* span(s)
   cover it, not one span object covering everything.
3. **Bug 2 — the deeper one: `commitText()` over a selection doesn't clear
   the *old* span, even after fixing bug 1.** Confirmed with temporary
   `Log.d` instrumentation and `adb logcat` against a live repro
   (`docs/UI_KEYBOARD_REDESIGN.md` readers: this is why the fix below reads
   "delete then insert," not "commit the plain string"). The evidence:
   selecting the same already-bold word twice in a row, with an un-bold
   commit in between, showed the **identical** `StyleSpan[0,9]` on both
   reads — the "un-bold" commit (a plain, unspanned `String`) had done
   nothing. Root cause: `InputConnection.commitText()` over a selection is
   an `Editable.replace(start, end, text)` under the hood, and Android's
   `Editable` does not clear spans pinned to that position range just
   because the replacement `CharSequence` carries none of its own — for
   same-length replacement text, the old span's bounds are simply
   re-adjusted to still cover it. So every "un-bold" attempt silently
   failed, every later read still saw the stale bold span, and the toggle
   got permanently stuck reporting "bold" no matter what was committed
   (user's report: works bold→unbold→bold→unbold correctly a few times,
   "then gets stuck", "pressing b has no effect"). **Fixed in
   `VoiceImeService.boldSelectionIfAny()`** by deleting the selection and
   inserting the replacement as two separate `commitText()` calls
   (`commitText("", 1)` then `commitText(toCommit, 1)`, wrapped in
   `beginBatchEdit()`/`endBatchEdit()` for atomicity) instead of one
   in-place replace. Deleting first collapses the old span to zero width;
   being `SPAN_EXCLUSIVE_EXCLUSIVE`, it does not re-expand to cover text
   inserted afterward in a separate edit.
4. **Verified for real**, not just compile-clean: reproduced live via
   `adb logcat -s VoiceIme:D` against temporary diagnostic logging (since
   removed — this was debug-only instrumentation, not shipped), then
   re-tested after the fix. User confirmed the bold→unbold cycle now
   repeats indefinitely as expected. This closes the single item that
   "Do this next" #2/#3 below were asking for.

### The emoji key ("☺") — also identical on both panels, but a *different*
gesture split than B

- **Single tap**: opens the emoji page.
  - `TextKeyboardView`: its own scrollable in-keyboard emoji grid
    (`showEmoji`/`drawEmojiPage`/`handleEmojiTouch`; ~800 emoji across 9
    categories, `EmojiData.kt`, unchanged from the prior session).
  - `VoiceKeyboardView`: has no room for its own grid, so `onOpenEmojiPanel`
    switches to text mode and calls `TextKeyboardView.showEmojiPage()`
    (new this session — text keyboard's `showEmojiPage()` mirrors its
    pre-existing `showPhrasebookPage()`).
- **Long press**: opens the phrasebook page (`onOpenPhrasebook`).
  - **This is long-press, not double-tap** — an explicit late correction. An
    intermediate version of this session made emoji's phrasebook access a
    double-tap (to mirror B), then the user corrected it back to long-press:
    "for emoji long press will redirect it to phrasebook." Don't re-unify
    these two keys' gestures without being asked again — B is double-tap,
    emoji is long-press, on purpose, as things stand now.
  - `TextKeyboardView`'s long-press-emoji-opens-phrasebook mechanism
    (`armLongPressEmoji`/`cancelLongPressEmoji`/`emojiLongPressed`) predates
    this session and was never touched. `VoiceKeyboardView` gained the
    equivalent (`armEmojiLongPress`/`cancelEmojiLongPress`/`emojiLongPressed`)
    this session, replacing an intermediate double-tap implementation that
    was fully ripped out again.

### "ABC" key (voice panel) — simplified back to a plain tap

Used to have a long-press-opens-phrasebook behavior (this session, briefly);
that moved to the emoji key (first as double-tap, corrected to long-press —
see above). ABC on the voice panel is now tap-only: switches to the text
keyboard, no long-press behavior at all.

### Voice panel utility row layout

Now 5-way (`width / 5f`), left to right: **ABC | B | ☺ | ↺ (undo) | ⌫
(backspace)**. Was 3-way (ABC | undo | backspace) at the start of this
session; B and ☺ were added as new dedicated buttons partway through,
mirroring the text keyboard's B/emoji keys instead of relying on
mic-long-press tricks (see "Superseded/rejected designs" below for what was
tried and abandoned before landing here).

### Text keyboard bottom row layout

Current order: **?123 | 🎤 | B | ☺ | Space | . | , | ⏎**. Notable moves this
session, each a separate explicit user request:
- Mic moved to right after `?123` (was further right, near the middle).
- Comma moved twice: first to just left of Space, then finally to just right
  of the period (its current position). If asked to move it again, the
  period currently sits between Space and comma.

### Symbols page — now two pages, not one

`TextKeyboardView.symbolRows()` (page 1, reached via `?123`) gained a
`"=\<"` toggle key (`KeyAction.MoreSymbols`, replacing one slot in what used
to be a single flat row) that switches to `symbolRows2()` (page 2: brackets,
currency `£¢€¥`, math `~\`|°¶∆√∞≈÷`, misc `©®™§•✓`), which itself has a
`"?123"` key that switches back to page 1. `"ABC"` on either page still jumps
straight back to letters, bypassing the other symbols page — mirrors
Gboard's own two-symbol-page convention. New `symbolsPage: Int` state field
(1 or 2), reset to 1 by `resetToLetters()` and whenever `SymbolsToggle`
closes symbols mode entirely (so re-opening `?123` always starts on page 1).

## Superseded / rejected designs (don't redo these without being asked)

Worth knowing what was tried and explicitly walked back, so a future session
doesn't reintroduce a design the user already rejected:

1. **Mic long-press for "save selection to phrasebook."** First attempt at
   this feature (an answer to "how do I add a selected text to the
   phrasebook from either panel?") was wired to a long-press on the *Mic*
   key on `TextKeyboardView`. User redirected mid-implementation: "instead
   of long pressing the mic long press the B button." Fully reverted and
   rebuilt on B.
2. **Long-press-B for "save selection."** The B-based version above still
   used long-press as the gesture (matching the still-standing "undo" and
   "emoji→phrasebook" long-press patterns at the time). User later asked for
   this to become a *double-tap* instead ("double tap would redirect to
   phrasebook"), and to add the "unbold on second tap over selection"
   nuance. Long-press-B doesn't exist anymore.
3. **Delayed single-tap to disambiguate double-tap.** Both B and (briefly)
   emoji were implemented with the single-tap's real action delayed by
   `DOUBLE_TAP_MS` so a following second tap could cancel it and substitute
   the double-tap action instead. This caused the "bold doesn't work" bug
   (see B's double-tap section above) and was replaced everywhere with
   "instant single tap + cached state for a following double-tap to act on
   retroactively." If you're tempted to add a delay-based double-tap
   anywhere else in this codebase, read that bug report first
   (conversation, not reproduced verbatim here) — the failure mode is subtle
   and easy to reintroduce by accident.
4. **B key changing color based on `boldActive`.** Tried muted/fg toggle
   (mirroring how Shift/EmojiToggle/SymbolsToggle are colored), explicitly
   rejected: "The B should not have a change of color." Also tried an
   accent-red fill (matching Shift-lock/Enter's accent treatment) even
   earlier, also rejected ("Other behaviour like the B turning red should
   not be present"). B is statically muted now, full stop.
5. **Emoji double-tap-to-phrasebook.** Built once (to mirror B's gesture),
   corrected back to long-press within the same session. See above.
6. **"Always bold" on B tap regardless of selection state.** The very first
   selection-bolding implementation always bolded the selection on tap, with
   a code comment explicitly reasoning that toggling wasn't reliable enough
   to bother with. The user asked for real per-selection toggling anyway
   ("if bold then unbold and vice versa") and it turned out to be
   straightforward via `GET_TEXT_WITH_STYLES` + `TextPaint` resolution — see
   current design above. Don't reintroduce the "always bold" comment/logic.

## Other features from earlier in this session (less iteration, still worth
listing)

- **Bold-text toggle key added** (`KeyAction.BoldToggle`) — the feature that
  eventually became everything in the B-key section above. Originally just
  "toggle bold for future typed text," no selection-awareness at all.
- **Emoji panel expanded** from ~26 curated emoji to ~800 across 9 categories
  (`EmojiData.kt`, new file) — scrollable page pattern
  (`drawEmojiPage`/`handleEmojiTouch`, drag-to-scroll with a small threshold
  before it counts as a drag rather than a tap) reused later for the
  phrasebook rework below.
- **Dedicated undo icon** (↺) on the voice panel, replacing a long-press-mic
  mechanism that had a real bug: its touch surface collided with
  tap-to-start-recording, so an ordinary tap held slightly longer than the
  long-press threshold (e.g. starting to speak before lifting the finger)
  would silently undo the *previous* dictation right as a new recording
  should have started ("a voice output appears and then erases itself").
  Fixed at the root by giving undo its own always-visible glyph instead of
  sharing the mic's touch target.
- **Voice panel backspace repeat bug fixed**: `startBackspaceRepeat()` used
  to call `stopBackspaceRepeat()` internally, which as a side effect reset
  `backspacePressed = false` right after `ACTION_DOWN` had just set it true
  — so `ACTION_UP`'s cancel guard never fired, and the repeat timer ran
  forever after any tap, however brief. Fixed by only cancelling the
  previous `Runnable` directly, not touching the pressed flag.
- **Phrasebook fully reworked** from a fixed 2×3-grid page
  (`phrases.take(6)` — anything beyond 6 was permanently orphaned, no
  delete/edit/copy/typed-add) to: a scrollable directory page on the
  keyboard itself (same drag-to-scroll pattern as emoji) plus a dedicated
  `PhrasebookActivity` (CRUD: add/edit/delete/copy, programmatic views, no
  XML, same style as the pre-existing `SetupActivity`/`BenchmarkActivity`).
  `PhrasebookStore` gained `update()`. Reachable from both panels, creatable
  by voice or by typing.
- **Settings navigation fixed**: `onStartInputView` used to unconditionally
  reset text/voice mode, letters page, and language hint on *every* call, so
  returning from Settings or the Phrasebook screen always dumped the user
  back on plain letters, discarding whatever state they'd left. Fixed via
  field-identity tracking (`lastFieldKey = "${info?.packageName}:${info?.fieldId}"`)
  — resets only fire for a genuinely different field, not a same-field
  return trip. Also removed a "try it" field from Settings per explicit
  request, and added a Settings entry point from the voice panel
  (long-press the privacy lock glyph) since it previously only existed via
  long-press-`?123` on the text keyboard.
- **`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` warning fixed** —
  `shouldThrottleToBase()`'s sticky-broadcast battery peek now uses
  `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`.

## Verification approach this session (and its limits)

Both keyboard surfaces are fully custom `Canvas`-drawn `View`s with no XML
and no accessible widget hierarchy for most of their content (only
`PhrasebookActivity`'s CRUD screen, being a real Activity with real
`TextView`/`LinearLayout`s, shows up in `uiautomator dump`). That means most
of this session's verification was **compile + install on the connected
test device (SM-A356E), not exhaustive live interaction** — every change in
this doc built clean (`:app:compileDebugKotlin`) and was installed
(`:app:installDebug`) before being reported done, but individual gestures
(the double-tap timing windows, the rounded corners rendering, the
AMOLED-black theme, the general bold-span detection across different host
apps) have **not** all been exercised by an actual human tap on the device
within this session.

This is deliberate, not an oversight: earlier in the broader session (before
the work in this doc), blind coordinate-based `adb shell input tap` calls
(scaled from screenshot pixel coordinates) went astray twice — once typing
garbage into the device launcher's search field, once opening the user's
real WhatsApp chat list. Both were backed out immediately without touching
anything further, but the incident is why this session avoided
coordinate-guessing taps entirely and relied on compile success + code
review instead. If a future session needs to visually verify something on
this device, prefer `adb shell am start -n <component>` (deterministic, no
coordinate guessing) and `uiautomator dump` over blind taps; if a tap is
genuinely necessary, screenshot immediately before it and confirm the
target app is actually foregrounded first (`dumpsys activity activities |
grep resumed`).

## Do this next

Real human testing on the device, both panels, ideally across 2-3 different
host apps (a plain `EditText`-based app and something like WhatsApp/Chrome,
since span-preservation behavior on `commitText` genuinely varies by app):

1. **B key, no selection**: tap once, type/dictate a few characters — should
   be instantly bold, no lag. Tap again — should go back to plain.
2. ✅ **Done (2026-08-06)** — **B key, selection**: select some plain text,
   tap B once — should bold immediately. Select it again (now bold), tap B
   again — should un-bold, and repeating this should cycle indefinitely
   (bold → plain → bold → ...), not get stuck after a few taps. Tested for
   real on-device and fixed — see "Session update — 2026-08-06 (continued)"
   above for the two real bugs found (span-coverage detection, and
   `commitText` not clearing a stale span on in-place replace) and their
   fixes. Still only verified in this keyboard's own text fields / standard
   `EditText`-style apps — not yet specifically re-checked in Chrome/WhatsApp
   as this item originally asked, so span-preservation behavior in apps with
   custom text widgets remains an open question.
3. **B key, double-tap with selection**: select text, double-tap B fast —
   should save to phrasebook (toast + check `PhrasebookActivity`/the
   keyboard's own phrasebook page) *and* leave the field's text in whatever
   state it was in *before* the double-tap (not bolded, not un-bolded from
   whatever it started as).
4. **B key, double-tap with no selection**: should just open the phrasebook
   page, no toast about "select text first" flashing awkwardly.
5. **Emoji key**: single tap opens the emoji grid (via text mode from the
   voice panel); long-press opens the phrasebook page instead. Confirm the
   long-press doesn't *also* flash the emoji grid open first.
6. **Rounded corners**: visually confirm both panels' top-left/top-right
   corners are actually rounded and the bottom stays flush/square, in both
   light and dark mode.
7. **Theme cycling**: tap the Settings "Theme" row 4 times, confirm it lands
   on System → Light → Dark → Black → System and that Black is visibly a
   different (pure black) shade from Dark (blue-grey), on both panels.
8. **Symbols page 2**: `?123` → `=\<` → confirm the new brackets/currency/math
   page renders and its own `?123` returns to page 1, and `ABC` from either
   symbols page returns straight to letters.
9. **Layout**: confirm the bottom row reads `?123 | 🎤 | B | ☺ | Space | . |
   , | ⏎` and the voice panel utility row reads `ABC | B | ☺ | ↺ | ⌫`.

None of the ASR/translation "Do this next" items from `docs/STATUS.md` have
been touched or affected by this session's work — that list is still
separately open.

## Files touched this session

| File | What changed |
|---|---|
| `app/src/main/java/dev/privatevoice/app/KeyboardPalette.kt` | Dark-mode color → blue-grey metallic; added `black` param + AMOLED black variant; added `TOP_CORNER_RADIUS_DP` |
| `app/src/main/java/dev/privatevoice/app/KeyboardSettings.kt` | Added `Theme.BLACK`, `isPureBlack()` |
| `app/src/main/java/dev/privatevoice/app/TextKeyboardView.kt` | B key (double-tap, selection toggle, static color, always-bold glyph), emoji long-press→phrasebook (pre-existing, untouched), `showEmojiPage()` added, `MoreSymbols`/second symbols page, mic/comma reordered, rounded-corner background, `boldTypeface`/`toggleBold()`/`onToggleBold`/`onDoubleTapBoldSave` callbacks |
| `app/src/main/java/dev/privatevoice/app/VoiceKeyboardView.kt` | 5-way utility row (ABC/B/☺/↺/⌫), B and ☺ buttons added from scratch with several gesture iterations (see "Superseded designs"), rounded-corner background, removed dead `boldActive` mirror field |
| `app/src/main/java/dev/privatevoice/app/VoiceImeService.kt` | `boldSelectionIfAny()`, `isFullyBold()` (general span detection via `TextPaint`), `saveSelectionToPhrasebook()` (double-tap, with formatting-revert), `applyBoldSpan()` (dictation-path bold, was previously missing entirely — real bug, dictated text never rendered bold before this fix), wiring for all the above on both panels |
| `app/src/main/java/dev/privatevoice/app/EmojiData.kt` | New file, ~800 emoji / 9 categories |
| `app/src/main/java/dev/privatevoice/app/PhrasebookStore.kt` | Added `update()` |
| `app/src/main/java/dev/privatevoice/app/PhrasebookActivity.kt` | New file, full CRUD screen |
| `app/src/main/java/dev/privatevoice/app/SetupActivity.kt` | Removed "try it" field, added "Manage phrasebook" button, `theme_black` label wiring |
| `app/src/main/AndroidManifest.xml` | Added `PhrasebookActivity` entry (`exported="false"`) |
| `app/src/main/res/values/strings.xml` | Phrasebook strings, `theme_black`, bold-selection toast strings, `nothing_to_undo` |

All of the above compiled clean (`:app:compileDebugKotlin`) and were
installed (`:app:installDebug`) on the connected SM-A356E test device as of
the end of this session.
