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

**A third session (2026-08-07) added a further round of work on top of
everything below**: a shared hand-drawn vector icon set replacing every raw
emoji glyph on both panels, a from-scratch synthesized sound-effect system
(key taps, Enter, recording-start/accept/failure chimes, with a settings
toggle), a new in-app how-to-use guide screen, unified lock-icon behavior
across both panels, "return to the panel you came from" navigation memory,
voice mode as the keyboard's default landing panel, and further bottom-row/
utility-row reorders. **Fully documented in "Session update — 2026-08-07"
below** — read it before touching icon drawing, the sound-effect code in
`VoiceImeService`, or either panel's utility/bottom row layout, since the
layouts described in "Current design" further down predate it in a few
places (flagged inline where they do).

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

**Superseded — see "Session update — 2026-08-07" below for the current
7-way layout (ABC | B | ↺ | ☺ | ⌫ | ? | ⏎).** As of this (earlier) session it
was 5-way (`width / 5f`), left to right: **ABC | B | ☺ | ↺ (undo) | ⌫
(backspace)**. Was 3-way (ABC | undo | backspace) at the very start of this
session; B and ☺ were added as new dedicated buttons partway through,
mirroring the text keyboard's B/emoji keys instead of relying on
mic-long-press tricks (see "Superseded/rejected designs" below for what was
tried and abandoned before landing here). The later session added a Guide
("?") key and an Enter key to this row, changing it from 5-way to 7-way and
moving ↺/☺ to a new order — described below, not here.

### Text keyboard bottom row layout

**Superseded — see "Session update — 2026-08-07" below for the current
order (?123 | 🔒 | 🎤 | ☺ | Space | B | . | , | ⏎), which added the lock key
to this row and moved B from before Space to after it.** As of this (earlier)
session the order was: **?123 | 🎤 | B | ☺ | Space | . | , | ⏎**. Notable
moves in *this* session, each a separate explicit user request:
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
9. **Layout**: **superseded** — the bottom row and utility row layouts
   described here predate the 2026-08-07 session's changes (added the lock
   key to the bottom row, grew the utility row from 5-way to 7-way). See
   "Session update — 2026-08-07"'s "Current layouts" section for the
   layouts to actually verify, and that section's "Verification status" for
   what's still outstanding there.

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

---

## Session update — 2026-08-07: icons, sound design, guide screen, panel memory

A third session, on top of everything above. Five separate pieces of work,
each iterated multiple rounds against real on-device feedback (this session
did install-and-test on the connected device throughout, not just
compile-verification — see each subsection for what was actually confirmed
by ear/eye vs. what's still only compile-verified).

### 1. Shared vector icon set (`KeyboardIcons.kt`, new file)

Every raw emoji glyph either panel used to draw as text (🎤, ☺, 🔒, plus the
hand-drawn backspace/arrow shapes each panel drew separately) is now a
shared, hand-drawn `Canvas` vector in one object, `KeyboardIcons`. Rationale:
emoji render inconsistently across devices/font sets and read as a mismatched
style against the rest of the keyboard's flat monochrome icon language.
Zero-allocation by convention (same as the rest of both views) — every
function takes the caller's own scratch `Path`/`RectF`/`Paint` fields rather
than owning any state.

- **`drawMic`** — soft rounded capsule + curved cradle + stem. Used only for
  `VoiceKeyboardView`'s large central mic button (kept soft/rounded per
  explicit approval: "the mic icon in the voice panel was great").
- **`drawMicAngular`** — same idea but with a curved dome top and sharp,
  straight-edged body/stand below, for `TextKeyboardView`'s small
  utility-row mic — matches that row's other flat-edged glyphs (backspace,
  lock) instead of reusing the soft voice-panel shape.
- **`drawEmoji`** — a closed book with a tiny smiley on the cover, replacing
  the plain "☺". The book shape reflects the *other* half of what this key
  does (long-press → phrasebook) rather than being a face with no visual tie
  to that gesture.
- **`drawArrow`** — a shaft + open chevron head, rotatable via `angleDeg`
  (`Canvas.rotate` convention). One shape shared by `TextKeyboardView`'s
  Shift/Caps key (rotated to point up) and both panels' Enter key (pointing
  right).
- **`drawBackspace`** — the classic arrow-shaped-outline-with-an-"×"-inside
  glyph, replacing two separately hand-drawn pentagon-and-lines versions
  (one per panel) with one shared shape. Both the outline and the cross are
  drawn in the same single `color` (not two-tone) — an explicit late
  correction ("The red and white in the backspace should be of same color as
  bold b").
- **`drawLock`** — shackle arc + body + a dim keyhole dot, replacing "🔒" on
  both panels' privacy-info glyph.

**Colors, after several rounds of "try X, revert, try Y" feedback (final
state only — see git history if the intermediate attempts matter)**:
`VoiceKeyboardView`'s central mic is blue (`micAccent`, `#0A84FF`) — was red
originally, changed on explicit request ("The mic icon in voice panel should
have blue instead of red waves and mic bg"). Both panels' Enter key is solid
green. Caps/Shift's arrow is blue when capitals are active, otherwise muted
(matching B's "no color change unless meaningfully stateful" convention) —
**never** the accent-blue overlap that a naive "just recolor the whole
glyph" version produced; getting this right took a dedicated correction
("the caps arrow should not show the blue overlap in the arrow and should
replicate the enter key style"). The emoji/book glyph is orange. Backspace
and Shift's *muted* state both resolve to the same B-matching muted tone, not
independently chosen colors, so a future palette change to B's muted color
automatically keeps these in sync. The transcribing-arc spinner on the mic
(drawn separately in `VoiceKeyboardView`, not part of `KeyboardIcons`) was
also recolored blue during this pass, to match the mic itself, from an
earlier red.

**A late fit-and-finish request** ("Ensure that all the icons in the bottom
of the voice panel follow a straight line at their respective centers")
found that the utility row's glyphs were being vertically centered by
several different, slightly inconsistent methods per glyph. Fixed by having
every glyph in `drawUtilityGlyphs()` share one `centerY` and use real font
metrics (`Paint.FontMetrics`, `-(ascent+descent)/2` baseline offset) for the
text-drawn glyphs, so text and vector glyphs land on the exact same visual
center line rather than each being eyeballed separately.

### 2. Sound design system (`VoiceImeService.kt`)

A from-scratch synthesized sound-effect layer — no sample assets, no
external audio library, just Kotlin math (`sin`, phase accumulation for
click-free frequency glides, one-pole lowpass filtering, squared-attack /
exponential-decay envelopes) rendered once into cached `ShortArray` PCM
buffers (`by lazy`, computed once per process) and played via
`android.media.AudioTrack` (`MODE_STATIC`, `AudioAttributes.USAGE_
ASSISTANCE_SONIFICATION` / `CONTENT_TYPE_SONIFICATION`).

**What plays, and when:**
- **`playStartChime()`** — a short (190ms) tone, fires right as recording
  starts (`beginRecording()`).
- **`playKeyTick()`** — a very short (30ms), quiet tone on every letter/
  symbol/space/backspace tap on the text keyboard (`handleTextKey()`).
- **`playEnterChime()`** — fires from `performEnterAction()`, shared by both
  panels' Enter keys.
- **`playAcceptChime()`** — a longer (2.5s), fading "arrival" tone on a
  *successful* transcription landing in the field.
- **`playFailureChime()`** — a shorter, descending-pitch variant, fires when
  a transcription attempt fails (`outcome == null` branch of
  `finishRecording()`).

All five share one underlying synthesis primitive, `cinematicSwellPcm()` — a
small detuned unison "trio" (frequency ratios `[1.0, 0.994, 1.006]`) plus a
sub-octave layer and a quiet overtone, mixed at different per-layer
amplitudes, with a squared (not linear) attack for a soft onset and either a
linear or exponential release depending on the specific chime — the shared
building block behind what the user asked for as a "Hans Zimmer" — deep,
layered, detuned, swelling — sound identity rather than a flat single-tone
beep (`ToneGenerator`, tried first, was explicitly rejected as sounding like
a DTMF dial tone). The accept chime (`genieAppearPcm()`) is a distinct,
longer variant of the same idea — layered detuned tones with pitch held flat
through the attack and only rising during the fade, tuned over several
rounds of "too loud," "should be a fade not a burst," "remove the swoosh,"
"remove the sparkle" feedback down to *just* the layered tone itself, no
noise/percussive elements at all in the final version despite several being
tried and removed (see "Design iteration notes" below).

**One switch controls all of it**: `KeyboardSettings.soundEnabled()` /
`setSoundEnabled()`, a new "Key sound" row in `SetupActivity`, mirroring how
`hapticEnabled` already covers every vibration. `playPcm()` checks it first
and no-ops entirely if off — no chimes are computed *or* played when
disabled (the `by lazy` PCM buffers still get computed once on first access,
but `AudioTrack` playback itself is skipped).

**A real bug found and fixed**: recording immediately after the start chime
played was intermittently transcribed as a hallucinated "bell" — the
chime's own playback was leaking into the mic input. Fixed with
`CHIME_TRIM_MS = 300`: `finishRecording()` now trims the leading 300ms of
raw samples (`rawSamples.copyOfRange(trimSamples, rawSamples.size)`) before
the minimum-length check and before handing audio to the ASR engine, so the
chime's own tail never reaches the model. **Confirmed fixed** — user
explicitly reported the fix worked ("THe tests at the previous step look
good").

**A second real bug, unrelated to audio content**: the deep bass tones used
in early iterations (fundamentals in the 78–99Hz range) were essentially
inaudible on the test device's phone speaker, which rolls off steeply below
roughly 150–200Hz. Fixed by shifting every fundamental up an octave (into
the 130–220Hz range) — not a code bug, a physical-speaker-response gotcha
worth remembering before tuning any future low-frequency sound on this
class of hardware.

**Design iteration notes** (what was tried and walked back, so it isn't
reintroduced by accident): a wind-noise "swoosh" layer (filtered white
noise) was added to the accept chime, then explicitly removed ("Remove the
swoosh from the genie appearing sound"); a short plucked/arpeggiated
"Zimmer guitar" element (Karplus-Strong delay-line synthesis) was tried and
fully removed, code and all; a percussive "sparkle" layer was added then
also explicitly removed ("Remove the sparkle sound"). The final accept
chime is the plain layered-tone swell alone — resist the urge to add texture
back in without being asked again, each addition was tried once and
rejected once already.

**How this was verified without being able to hear it directly**: since
sound quality can't be judged by reading code, every round of feedback here
was install → user listens on-device → report back → adjust — roughly 15
rounds total across the whole arc from "add a slight sound effect" through
the final tuned version. The one sound that couldn't be triggered by normal
use (`playFailureChime()` — genuine ASR failures aren't reproducible on
demand) got a **temporary, clearly-marked debug hook**: a long-press
gesture on the voice panel's "?" (help) key that called `playFailureChime()`
directly instead of opening the guide, built, installed, confirmed by the
user ("This looks cool"), then **fully removed again** — the `downAtMs`
field, the long-press branch, the `onDebugTestFailureSound` callback, and
its wiring in `VoiceImeService` are all gone; "?" is back to plain-tap-only.
If a similarly hard-to-trigger sound needs testing again in the future, this
same pattern (temporary debug hook on an existing key, confirm by ear,
delete completely) is the one already validated for this codebase — don't
leave a debug hook in place "just in case."

### 3. How-to-use guide screen (`GuideActivity.kt`, new file)

A single scrollable, zero-XML guide screen (same programmatic-views style as
`SetupActivity`/`PhrasebookActivity`) covering getting-started, voice
dictation, the B key, emoji/phrasebook, the typing keyboard, tips, and
privacy — written to surface the gestures a first-time user would never
discover by tapping around (double-tap-B, long-press-emoji, undo). Reachable
two ways: a "How to use →" row in `SetupActivity`, and a dedicated "?" key
in the voice panel's utility row (`onOpenGuide`).

### 4. Lock/privacy behavior unified across both panels

Previously the two panels' privacy-info affordances didn't match: the text
keyboard reached settings via a long-press on `?123`, while the voice panel's
lock glyph opened the privacy-info toast directly on tap. Both now behave
identically: **tap the lock glyph → open Settings; long-press it → show the
privacy-info toast** (`KeyboardSettings.privacyProofText()`, the live
`PackageManager`-permissions readout, moved into `KeyboardSettings` so both
panels share the exact same logic rather than each computing it separately).
The old long-press-`?123`-for-settings gesture is gone.

### 5. Panel-return memory, and voice mode as the default landing panel

**Voice mode is now the keyboard's default landing state** (`VoiceImeService`'s
`mode` field defaults to `Mode.VOICE`, and `resetToLetters()`/field-change
handling route back to voice, not letters, on a genuinely new field) — a
deliberate flip from the previous default of landing on the typing keyboard.

**Navigating to Settings, the Guide, the emoji panel, or the phrasebook from
either panel now returns to *that same panel* afterward**, rather than
always landing back on text/letters regardless of where the user actually
came from. Implemented via `VoiceImeService.modeBeforeDetour: Mode?` — set
to `Mode.VOICE` right before switching away for one of these detours (e.g.
inside `vv.onOpenPhrasebook`/`vv.onOpenEmojiPanel`), and read/cleared inside
`TextKeyboardView.onExitOverlayPage` (a new callback fired when an
in-keyboard overlay page like emoji or phrasebook is closed) to decide
whether to switch back to voice mode or stay on text. In-keyboard overlay
pages (emoji grid, phrasebook page) now show a vector back-chevron
(`KeyboardIcons.drawArrow(..., 180f)`, bottom-left) instead of a "←" text
glyph, matching the rest of this session's icon-language shift; real
`Activity` screens (`SetupActivity`, `GuideActivity`, `PhrasebookActivity`)
each got an explicit plain-text "←" back affordance instead, since they have
no in-keyboard overlay concept to mirror.

### Current layouts (superseding the two sections earlier in this file)

- **Voice panel utility row**, 7-way (`width / 7f`): **ABC | B | ↺ (undo) |
  ☺ (emoji/phrasebook) | ⌫ (backspace) | ? (guide) | ⏎ (Enter, green)**. Grew
  from the previous 5-way row (ABC/B/☺/↺/⌫) by adding the Guide key and an
  Enter key, and reordering ↺/☺ (undo now comes right after B, emoji moved
  right of undo — the earlier "switch the location of notebook and undo"
  requests, applied more than once across this and the prior session, so
  don't assume the previous doc's 5-way order still holds).
- **Text keyboard bottom row**: **?123 | 🔒 (lock/settings) | 🎤 (mic) | ☺
  (emoji) | Space | B (bold) | . | , | ⏎ (Enter)** — added the lock key to
  this row (previously only reachable via long-press-`?123`, now also its
  own explicit key) and moved B from before Space to after it.

### Verification status for this session's work

Compiled clean and installed on the connected test device after every
change, same as prior sessions. Beyond that baseline:
- **Confirmed by the user, on-device**: the bold selection fix (carried over
  from the prior session, re-verified still working), the failure chime's
  sound (via the temporary debug hook, since removed).
- **Not independently re-confirmed by ear per individual chime** beyond the
  overall "This looks cool" — the accept/start/enter/key-tick chimes were
  each iterated against real listening feedback during their respective
  design rounds (see "Design iteration notes" above), but there was no
  single final pass explicitly re-confirming all five chimes together after
  the very last round of tuning.
- **Not yet human-tap-verified**: the new 7-way utility row's hit-target
  boundaries (particularly Guide vs. Enter, the two newest columns), the
  panel-return-memory behavior across all four detour paths (Settings,
  Guide, emoji, phrasebook) from both starting panels, and the lock key's
  tap-vs-long-press split on the *text* keyboard specifically (the voice
  panel's version of this was already working before this session; the text
  keyboard's is new this session).

## Files touched this session (2026-08-07)

| File | What changed |
|---|---|
| `app/src/main/java/dev/privatevoice/app/KeyboardIcons.kt` | New file — shared vector icon set (mic ×2 variants, emoji/book, arrow, backspace, lock) |
| `app/src/main/java/dev/privatevoice/app/GuideActivity.kt` | New file — how-to-use guide screen |
| `app/src/main/java/dev/privatevoice/app/VoiceImeService.kt` | Sound-design system (`cinematicSwellPcm`/`genieAppearPcm`, `playPcm`, five chime call sites), `CHIME_TRIM_MS` mic-bleed fix, `modeBeforeDetour`/panel-return memory, `openGuideScreen()`, voice-mode-as-default, lock tap/long-press wiring |
| `app/src/main/java/dev/privatevoice/app/TextKeyboardView.kt` | Icon-based rendering for backspace/Enter/Shift/Mic/EmojiToggle/PrivacyInfo (was text/emoji), lock key added to bottom row + tap/long-press split, `onExitOverlayPage` callback, vector back-chevron on overlay pages, bottom-row reorder (B moved after Space) |
| `app/src/main/java/dev/privatevoice/app/VoiceKeyboardView.kt` | Utility row grew 5-way → 7-way (added Guide "?" and Enter), icon-based mic/backspace/emoji/Enter rendering, mic recolored blue, shared `centerY`/font-metrics alignment across the row, lock tap/long-press swapped to match text keyboard |
| `app/src/main/java/dev/privatevoice/app/KeyboardSettings.kt` | Added `soundEnabled()`/`setSoundEnabled()`; `privacyProofText()` moved here from `VoiceKeyboardView` so both panels share one implementation |
| `app/src/main/java/dev/privatevoice/app/SetupActivity.kt` | Added "Key sound" settings row, back arrow, "How to use →" button |
| `app/src/main/java/dev/privatevoice/app/PhrasebookActivity.kt` | Added back arrow |
| `app/src/main/res/values/strings.xml` | `setting_sound`, `guide_title`, `guide_tagline`, `action_open_guide`, related strings |
| `app/src/main/AndroidManifest.xml` | Added `GuideActivity` entry (`exported="false"`) |

All of the above compiled clean and were installed on the connected test
device; the failure chime and the bold-selection fix were confirmed by the
user directly, the rest per the verification status above.
