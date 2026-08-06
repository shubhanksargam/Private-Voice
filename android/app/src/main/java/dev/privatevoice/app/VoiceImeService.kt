package dev.privatevoice.app

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.ContactsContract
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.privatevoice.engine.AsrEngine
import dev.privatevoice.engine.AsrEngineHolder
import dev.privatevoice.engine.AudioRecorder
import dev.privatevoice.engine.DevanagariTransliterator
import dev.privatevoice.engine.EnglishLoanwordCorrector
import dev.privatevoice.engine.LatinToDevanagariTransliterator
import dev.privatevoice.engine.TranslationEngineHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The keyboard: a real QWERTY typing surface with a mic key that opens a
 * tap-to-talk voice view, and an "ABC" key on the voice view to come back.
 *
 * Voice is a mode within this one IME, not a separate app the user switches
 * away to — that's why system-IME switching (for a genuinely different
 * keyboard) lives on the text surface as a long-press-space, the conventional
 * place for it, rather than as a dedicated key competing for space here.
 *
 * Everything runs on-device. The app holds no INTERNET permission at all — a
 * Gradle task fails the build if one ever appears in the merged manifest — so
 * audio cannot leave the phone even if something here were compromised.
 */
class VoiceImeService : InputMethodService() {

    private enum class Mode { TEXT, VOICE }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recorder = AudioRecorder()

    private var textView: TextKeyboardView? = null
    private var voiceView: VoiceKeyboardView? = null
    private var mode = Mode.TEXT
    private var busy = false

    /** True while the in-flight recording is for [PhrasebookStore], not the target field. */
    private var recordingForPhrase = false

    /** Whether the current field wants TYPE_TEXT_FLAG_CAP_SENTENCES honoured. */
    private var autoCapSentences = false

    override fun onCreateInputView(): View {
        val tv = TextKeyboardView(this)
        val vv = VoiceKeyboardView(this)
        textView = tv
        voiceView = vv

        tv.onKey = { handleTextKey(it) }
        tv.onLongPressSpace = { showSystemImePicker() }
        tv.onLongPressSettings = { openSettingsScreen() }
        tv.onToggleBold = { boldSelectionIfAny() }
        tv.onDoubleTapBoldSave = { saveSelectionToPhrasebook() }
        tv.setPhrases(PhrasebookStore.list(this))

        vv.onHoldStart = { beginRecording() }
        vv.onHoldEnd = { finishRecording() }
        vv.onCancel = { cancelTranscription() }
        vv.onCancelRecording = { cancelRecording() }
        vv.onSwitchToText = { switchToTextMode() }
        vv.onOpenPhrasebook = {
            switchToTextMode()
            textView?.setPhrases(PhrasebookStore.list(this))
            textView?.showPhrasebookPage()
        }
        vv.onOpenEmojiPanel = {
            switchToTextMode()
            textView?.showEmojiPage()
        }
        vv.onOpenSettings = { openSettingsScreen() }
        vv.onToggleBold = {
            textView?.toggleBold()
            boldSelectionIfAny()
        }
        vv.onDoubleTapBoldSave = { saveSelectionToPhrasebook() }
        vv.onBackspace = {
            currentInputConnection?.deleteSurroundingText(1, 0)
            lastVoiceCommitLength = 0
        }
        vv.onUndoLastDictation = {
            val undone = undoLastDictation()
            if (!undone) voiceView?.showTransientBlocked(getString(R.string.nothing_to_undo))
            undone
        }
        vv.visibility = View.GONE

        // Warm the model on a background thread as soon as the keyboard is
        // built, so the first press doesn't pay a multi-hundred-ms load.
        scope.launch(Dispatchers.Default) { AsrEngineHolder.getOrLoad(this@VoiceImeService) }

        return FrameLayout(this).apply {
            addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(vv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            // Apps targeting API 35+ get edge-to-edge by default, and that
            // applies to IME windows too — without this, the keyboard's own
            // bottom row draws underneath the 3-button navigation bar instead
            // of above it. Reading the actual navigationBars() inset (rather
            // than hardcoding a bar height) is what keeps this correct on
            // gesture-navigation devices too, where that inset is near zero.
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                v.setPadding(0, 0, 0, navBar.bottom)
                insets
            }
        }
    }

    /**
     * Identity of whatever field last had [onStartInputView] called for
     * it — package + field id is stable for "the same EditText in the
     * same app," which is exactly what distinguishes "genuinely switched
     * to a different field" from "briefly left and came back to this same
     * one" (e.g. a trip to Settings/Phrasebook and Back). Only the former
     * should reset text/voice mode, script toggle, language hint, etc. —
     * see [onStartInputView].
     */
    private var lastFieldKey: String? = null

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)

        val fieldKey = "${info?.packageName}:${info?.fieldId}"
        val sameField = fieldKey == lastFieldKey
        lastFieldKey = fieldKey

        if (!sameField) {
            // Only for a genuinely different field — voice is opted into
            // per-field, not remembered across them. Returning to the same
            // field (e.g. after visiting Settings or the Phrasebook screen)
            // should reveal the keyboard exactly as it was left, not reset it.
            switchToTextMode()
            textView?.resetToLetters()
            lastVoiceCommitLength = 0
            // A quick HI override for one message on the voice panel
            // shouldn't silently keep applying to everything typed after —
            // only the settings-screen default persists across sessions.
            KeyboardSettings.setLanguageHint(this, KeyboardSettings.defaultLanguageHint(this))
        }
        configureAutoCap(info)
        // Refreshed unconditionally (not just on a field change) — a trip
        // to PhrasebookActivity via the "Manage" action can add/edit/delete
        // phrases, and the keyboard's own phrasebook page should show that
        // the moment it's visible again, same-field-return or not.
        textView?.setPhrases(PhrasebookStore.list(this))

        val sensitive = isSensitiveField(info)
        textView?.setMicEnabled(hasMicPermission() && AsrEngineHolder.hasModel(this) && !sensitive)

        // Pre-set the voice view's message so it's correct the instant the
        // user taps the mic key, rather than flashing idle-then-blocked.
        voiceView?.let { v ->
            when {
                sensitive -> v.showBlocked(getString(R.string.disabled_for_password))
                !hasMicPermission() -> v.showBlocked(getString(R.string.needs_mic_permission))
                !AsrEngineHolder.hasModel(this) -> v.showBlocked(getString(R.string.no_model_installed))
                else -> v.showIdle()
            }
        }
    }

    // --- typing ---

    private fun handleTextKey(action: TextKeyboardView.KeyAction) {
        val ic = currentInputConnection
        // Any manual edit invalidates the undo-last-dictation target — it's
        // only meaningful when still literally the text right before the
        // cursor. Mic/RecordPhrase are exempt: both just switch to the
        // voice panel without touching the field at all.
        if (action !is TextKeyboardView.KeyAction.Mic && action != TextKeyboardView.KeyAction.RecordPhrase) {
            lastVoiceCommitLength = 0
        }
        when (action) {
            is TextKeyboardView.KeyAction.Letter -> ic?.commitText(styledText(action.char.toString()), 1)
            is TextKeyboardView.KeyAction.Symbol -> ic?.commitText(styledText(action.char.toString()), 1)
            is TextKeyboardView.KeyAction.Space -> ic?.commitText(styledText(" "), 1)
            is TextKeyboardView.KeyAction.SpacePeriod -> {
                // The first tap already committed a plain space; swap it
                // for ". " rather than appending, so the result is "word. "
                // not "word . ".
                ic?.deleteSurroundingText(1, 0)
                ic?.commitText(styledText(". "), 1)
            }
            is TextKeyboardView.KeyAction.Backspace -> ic?.deleteSurroundingText(1, 0)
            is TextKeyboardView.KeyAction.Enter -> performEnterAction()
            is TextKeyboardView.KeyAction.Mic -> switchToVoiceMode()
            is TextKeyboardView.KeyAction.Emoji -> ic?.commitText(action.glyph, 1)
            is TextKeyboardView.KeyAction.Phrase -> ic?.commitText(styledText(action.text), 1)
            is TextKeyboardView.KeyAction.RecordPhrase -> {
                // Records into PhrasebookStore instead of the field — see
                // the recordingForPhrase branch in finishRecording().
                recordingForPhrase = true
                switchToVoiceMode()
            }
            is TextKeyboardView.KeyAction.DeletePhrase -> {
                PhrasebookStore.remove(this, action.id)
                textView?.setPhrases(PhrasebookStore.list(this))
            }
            is TextKeyboardView.KeyAction.ManagePhrasebook -> {
                startActivity(
                    Intent(this, PhrasebookActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            // Shift and the letters/symbols/emoji/bold toggles are fully
            // handled inside TextKeyboardView — they never reach onKey.
            // Bold's *state* (boldActive) is still read directly, just not
            // through this dispatch.
            is TextKeyboardView.KeyAction.Shift,
            is TextKeyboardView.KeyAction.SymbolsToggle,
            is TextKeyboardView.KeyAction.EmojiToggle,
            is TextKeyboardView.KeyAction.BoldToggle,
            is TextKeyboardView.KeyAction.MoreSymbols,
            -> Unit
        }
        updateAutoCap()
    }

    /**
     * Wraps [text] in a bold [android.text.style.StyleSpan] when the
     * bold-toggle key is active, otherwise returns it unchanged.
     * `commitText` accepting a styled [CharSequence] instead of a plain
     * `String` is the same standard-IME technique already used for
     * confidence-underlining (see `annotateLowConfidenceWords`) — a host
     * app that doesn't preserve spans on commit just shows plain text, no
     * regression either way.
     */
    private fun styledText(text: String): CharSequence {
        if (textView?.boldActive != true) return text
        return android.text.SpannableString(text).apply {
            setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                0,
                length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun performEnterAction() {
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val multiline = (info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        val noEnterAction = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        val hasRealAction = action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED

        if (hasRealAction && !multiline && !noEnterAction) {
            currentInputConnection?.performEditorAction(action)
        } else {
            currentInputConnection?.commitText("\n", 1)
        }
    }

    private fun configureAutoCap(info: EditorInfo?) {
        val type = info?.inputType ?: 0
        autoCapSentences = (type and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0
        val capChars = (type and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0
        textView?.setShiftState(
            when {
                capChars -> TextKeyboardView.ShiftState.LOCKED
                autoCapSentences -> TextKeyboardView.ShiftState.ONE_SHOT
                else -> TextKeyboardView.ShiftState.NONE
            }
        )
    }

    /**
     * Re-arm shift after a sentence boundary. Runs after every key rather than
     * only after punctuation, since backspace can undo one too — recomputing
     * from the actual text beats tracking it incrementally.
     */
    private fun updateAutoCap() {
        val tv = textView ?: return
        if (!autoCapSentences || tv.shiftLocked) return
        val before = currentInputConnection?.getTextBeforeCursor(6, 0)?.toString().orEmpty()
        val trimmed = before.trimEnd(' ')
        val shouldCap = trimmed.isEmpty() || trimmed.last() in ".!?"
        tv.setShiftState(if (shouldCap) TextKeyboardView.ShiftState.ONE_SHOT else TextKeyboardView.ShiftState.NONE)
    }

    private fun showSystemImePicker() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    // --- mode switching ---

    private fun switchToTextMode() {
        if (recorder.isRecording) recorder.cancel()
        // Backing out of the voice panel (mic never actually tapped) should
        // not leave a stale flag that redirects some later, unrelated
        // dictation into the phrasebook instead of the field.
        recordingForPhrase = false
        mode = Mode.TEXT
        voiceView?.visibility = View.GONE
        textView?.visibility = View.VISIBLE
    }

    private fun switchToVoiceMode() {
        mode = Mode.VOICE
        textView?.visibility = View.GONE
        voiceView?.visibility = View.VISIBLE
    }

    private enum class VocabCategory { APP_SEARCH, CHAT, SETTINGS, WEB }

    /**
     * Category keyed off which app owns the focused field.
     *
     * [EditorInfo.packageName] is the real, always-available signal for this —
     * it is not the same thing as reading the host app's screen content, which
     * Android's IME sandboxing genuinely does not allow.
     *
     * Package matching is a best-effort heuristic, not an exhaustive app
     * directory — unrecognised or regional apps fall through to no category.
     */
    private fun vocabCategoryForCurrentField(): VocabCategory? {
        val pkg = currentInputEditorInfo?.packageName?.lowercase() ?: return null
        val info = currentInputEditorInfo
        val isSearchAction = ((info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEARCH

        return when {
            pkg.contains("launcher") ||
                pkg == "com.google.android.googlequicksearchbox" ||
                pkg == "com.android.settings" && isSearchAction ->
                VocabCategory.APP_SEARCH

            pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("messag") ||
                pkg.contains("chat") || pkg == "com.facebook.orca" || pkg == "com.instagram.android" ||
                pkg.contains("dialer") || pkg.contains("contacts") ->
                VocabCategory.CHAT

            pkg == "com.android.settings" -> VocabCategory.SETTINGS

            pkg.contains("chrome") || pkg.contains("browser") || pkg.contains("firefox") || isSearchAction ->
                VocabCategory.WEB

            else -> null
        }
    }

    /**
     * Text conditioning passed to the decoder as `initial_prompt`. This is a
     * soft nudge, not a constraint — Whisper's language-model prior can still
     * favour a common phrase over an unusual-but-correct proper noun on a
     * near-homophone, especially on a single short word decoded by `base`
     * (the model this project ships per the M0 latency gate). See
     * [correctForCategory] for the deterministic backstop that actually fixes
     * the specific case that motivated this — the prompt hint alone was not
     * enough on-device.
     */
    private fun promptHintFor(category: VocabCategory?): String? {
        val fragments = mutableListOf<String>()

        // First, not last: whisper.cpp truncates an over-length prompt by
        // keeping only its final tokens, dropping from the start. The
        // proper-noun list is long (and its internal order doesn't matter
        // much), so it should be the one that absorbs truncation — the
        // short, structured category vocabulary below is worth protecting
        // by placing it where it's guaranteed to survive.
        properNounHint(devanagari = false)?.let { fragments += it }

        when (category) {
            VocabCategory.APP_SEARCH ->
                fragments += "Common apps: WhatsApp, Instagram, YouTube, Gmail, Chrome, Camera, Photos, Maps, " +
                    "Amazon, Flipkart, Paytm, PhonePe, Spotify, Netflix, Zoom, Uber, Settings."
            VocabCategory.CHAT -> {
                fragments += "Casual chat: hey, okay, sure, thanks, lol, omg, see you, call me, " +
                    "on my way, sounds good, no worries, WhatsApp, Instagram."
                contactNameHint()?.let { fragments += it }
            }
            VocabCategory.SETTINGS ->
                fragments += "Phone settings: Wi-Fi, Bluetooth, brightness, notifications, battery, " +
                    "storage, privacy, permissions, accessibility, display."
            VocabCategory.WEB ->
                fragments += "Web search: search, website, .com, login, sign in, download."
            null -> Unit
        }

        return fragments.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /**
     * The speaker's own name plus curated lists of well-known personalities
     * (Indian, world political leaders) and major Indian cities — proper
     * nouns worth hinting regardless of category, same reasoning as the
     * WhatsApp fix (Whisper's language-model prior favours common words
     * over correct-but-less-common proper nouns). Global non-political
     * celebrities were dropped from this list — cities come up far more
     * often in everyday dictation (travel, addresses) than actor/athlete
     * names do.
     *
     * [devanagari]`=true` renders this in Devanagari instead of English —
     * used for the Hindi-forced decode path, which otherwise gets no
     * prompt hint at all (an English-language hint was found to actively
     * fight a Hindi-forced decode; see [correctForCategory]'s sibling note
     * in docs/STATUS.md). A short list of *transliterated proper nouns* is
     * a different, more targeted signal than the full-sentence English
     * style hints that caused that regression, but this specific
     * direction — sending any hint at all on the Hindi path — is untested
     * on real speech. If Hindi dictation quality regresses after this,
     * that's the first thing to suspect.
     *
     * The combined name list has grown past whisper's prompt budget
     * (~224 tokens) on its own — [promptHintFor] places this fragment
     * first specifically so truncation (which drops from the *start* of
     * the combined prompt) eats into this list rather than the shorter,
     * structured category vocabulary, but it does mean names early in
     * this list are the least likely to actually reach the decoder.
     * Expect inconsistent recognition across the list rather than uniform
     * coverage until this is either trimmed or split per-category.
     */
    private fun properNounHint(devanagari: Boolean): String? {
        val names = mutableListOf<String>()
        KeyboardSettings.userName(this)?.takeIf { it.isNotBlank() }?.let { names += it }
        names += INDIAN_PERSONALITIES
        names += WORLD_LEADERS
        names += INDIAN_CITIES
        if (names.isEmpty()) return null
        val joined = names.joinToString(", ")
        val rendered = if (devanagari) LatinToDevanagariTransliterator.toDevanagari(joined) else joined
        return "Names: $rendered."
    }

    /**
     * Deterministic backstop for known near-homophones, scoped to the
     * categories where the correction is actually right — "what's up" is a
     * real thing to type in [VocabCategory.CHAT], just not when the audio was
     * a one-word app name. `initial_prompt` conditioning alone proved too
     * weak to fix "WhatsApp" -> "what's up" reliably on-device on `base`, so
     * this replaces the known-bad output outright rather than only hoping the
     * decoder biases away from it.
     */
    private fun correctForCategory(text: String, category: VocabCategory?): String {
        var result = text
        if (category == VocabCategory.APP_SEARCH || category == VocabCategory.CHAT) {
            result = WHATSAPP_HOMOPHONE.replace(result, "WhatsApp")
        }
        if (category == VocabCategory.APP_SEARCH) {
            // Whisper treats a short utterance as a complete sentence and
            // appends terminal punctuation on its own — fine for dictation,
            // wrong for a search query ("WhatsApp." doesn't match anything).
            result = result.trimEnd().trimEnd('.', '!', '?')
        }
        return result
    }

    private fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Contact display names as a vocabulary hint, so Indian names — exactly
     * the proper-noun case `base` struggles with, same failure mode as the
     * WhatsApp/"what's up" bug — get recognised reliably in chat/dialer
     * fields. Opt-in (READ_CONTACTS is requested from Setup, never assumed);
     * silently returns nothing if not granted rather than prompting from
     * here, since a mid-dictation permission dialog would be worse than just
     * missing the hint for that utterance.
     */
    private fun contactNameHint(): String? {
        if (!hasContactsPermission()) return null
        val names = queryContactNames()
        if (names.isEmpty()) return null
        return "Contacts: " + names.joinToString(", ") + "."
    }

    private fun queryContactNames(limit: Int = 15): List<String> {
        val names = LinkedHashSet<String>()
        val nameCol = ContactsContract.Contacts.DISPLAY_NAME

        // Starred/frequently-contacted first — most likely to actually be
        // dictated, and keeps the hint short (whisper's prompt budget is
        // small, so this can't just be "every contact").
        runCatching {
            contentResolver.query(
                ContactsContract.Contacts.CONTENT_STREQUENT_URI,
                arrayOf(nameCol), null, null, null,
            )?.use { c ->
                val col = c.getColumnIndex(nameCol)
                while (c.moveToNext() && names.size < limit) {
                    c.getString(col)?.takeIf { it.isNotBlank() }?.let { names.add(it) }
                }
            }
        }
        if (names.size < limit) {
            runCatching {
                contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(nameCol), null, null,
                    "${ContactsContract.Contacts.TIMES_CONTACTED} DESC",
                )?.use { c ->
                    val col = c.getColumnIndex(nameCol)
                    while (c.moveToNext() && names.size < limit) {
                        c.getString(col)?.takeIf { it.isNotBlank() }?.let { names.add(it) }
                    }
                }
            }
        }
        return names.toList()
    }

    // --- voice (unchanged behaviour from the voice-only build) ---

    /**
     * Never dictate into a password or similar. Speaking a credential aloud is
     * the user's business, but silently routing it through a speech engine is
     * not something a keyboard should do by default. Typing still works in
     * these fields — only the mic is withheld.
     */
    private fun isSensitiveField(info: EditorInfo?): Boolean {
        val type = info?.inputType ?: return false
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val textPassword = cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        val numberPassword = cls == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        return textPassword || numberPassword
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun beginRecording() {
        if (busy || recorder.isRecording) return
        if (!hasMicPermission()) {
            voiceView?.showBlocked(getString(R.string.needs_mic_permission))
            openPermissionScreen()
            return
        }
        try {
            recorder.start()
            // Surfaced now (rather than only silently changing behaviour at
            // commit time) so it's obvious *before* speaking that this
            // dictation is going to replace the selected text, not insert
            // alongside it — a selection that quietly vanishes on commit
            // would read as a bug.
            voiceView?.replacingSelection = !currentInputConnection?.getSelectedText(0).isNullOrEmpty()
            voiceView?.recordingPhrase = recordingForPhrase
            voiceView?.showListening()
            pumpAmplitude()
        } catch (t: Throwable) {
            Log.e(TAG, "Could not start recording", t)
            voiceView?.showBlocked(t.message ?: getString(R.string.mic_unavailable))
        }
    }

    /**
     * Feed live mic level to the view while recording. The view animates
     * continuously on its own; this only supplies the value it reacts to, so a
     * modest poll rate is plenty and keeps the main thread quiet.
     */
    private fun pumpAmplitude() {
        scope.launch {
            while (recorder.isRecording) {
                voiceView?.amplitude = recorder.amplitude
                delay(AMPLITUDE_POLL_MS)
            }
            voiceView?.amplitude = 0f
        }
    }

    /**
     * The engine currently mid-[AsrEngine.transcribe], if any — published by
     * [finishRecording] so a mic tap during `TRANSCRIBING` has something to
     * cancel. `small`'s decode can run 7+ seconds; before this there was no
     * way to back out of that wait.
     */
    @Volatile
    private var activeEngine: AsrEngine? = null

    private fun cancelTranscription() {
        activeEngine?.cancel()
    }

    /**
     * Backspace-during-[VoiceKeyboardView.State.LISTENING] handler — discards
     * the in-progress recording without ever handing it to the ASR engine,
     * distinct from [cancelTranscription] which aborts a decode already
     * running. Mirrors the discard the "ABC" glyph already does via
     * [switchToTextMode], just without leaving the voice panel.
     */
    private fun cancelRecording() {
        if (!recorder.isRecording) return
        recorder.cancel()
        voiceView?.showIdle()
    }

    /**
     * True when the device is under enough battery or thermal pressure that
     * routing should favour `base` over `small` regardless of what
     * language-based routing would otherwise pick. Read fresh per-utterance
     * (no caching) — both signals can change between one dictation and the
     * next, and this is a cheap check either way (a sticky-broadcast peek
     * and a system-service call, no I/O).
     */
    private fun shouldThrottleToBase(): Boolean {
        // Plain registerReceiver(null, filter) — the sticky-broadcast-peek
        // idiom, no actual receiver ever gets registered — still trips
        // Android 13+'s "must specify RECEIVER_EXPORTED or
        // RECEIVER_NOT_EXPORTED" requirement on some OEM builds and logs a
        // DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION warning even though
        // nothing is actually broken. ContextCompat.registerReceiver makes
        // the flag explicit (NOT_EXPORTED: nothing outside this process
        // could target this call anyway) and silences it, back-compatible
        // to API 26.
        val battery = ContextCompat.registerReceiver(
            this, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val lowBattery = pct in 0..LOW_BATTERY_PCT && !charging

        val thermalElevated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager
            (pm?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE) >= PowerManager.THERMAL_STATUS_MODERATE
        } else {
            false
        }

        return lowBattery || thermalElevated
    }

    private fun finishRecording() {
        if (!recorder.isRecording) return
        val samples = recorder.stop()

        // Ignore taps and stray presses rather than sending a fraction of a
        // second of noise to the model and committing whatever it invents.
        if (samples.size < MIN_SAMPLES) {
            voiceView?.showIdle()
            return
        }

        busy = true
        // Checked once up front rather than only where `small` would've
        // been picked, so the indicator reflects the device's actual
        // current state honestly, even for utterances that would've used
        // `base` anyway.
        val deviceThrottled = shouldThrottleToBase()
        voiceView?.batterySaver = deviceThrottled
        voiceView?.showTranscribing()
        val category = vocabCategoryForCurrentField()
        val languageHint = KeyboardSettings.languageHint(this)
        scope.launch {
            val outcome = withContext(Dispatchers.Default) {
                runCatching {
                    // AUTO: a full decode's own language auto-detect is
                    // what caused Hindi to come out mistranslated in the
                    // first place (base commits to a language guess deep
                    // inside a decode that's already running — see
                    // docs/STATUS.md). Run a standalone, cheap LID pass on
                    // base first instead (one encoder pass + one decode
                    // step, not a full transcription) and route to the
                    // tier that actually matches what was said, rather
                    // than either always paying small's cost or trusting
                    // base's in-decode guess.
                    // EN uses Whisper's translate task (auto-detect the
                    // actual source language, then translate it to
                    // English) rather than forcing language="en" — forcing
                    // "en" with translate off on non-English audio is a
                    // mismatched instruction (decode as English text when
                    // the audio isn't English) and produces garbage, not a
                    // translation. This restores the original "Hindi
                    // spoken while in English mode gets translated"
                    // behaviour properly instead of via that mismatch.
                    // Set by the HINDI branch below when the utterance is
                    // routed through clean-English ASR + MT instead of a
                    // forced-Hindi decode — read after transcribe() to
                    // decide whether the raw text still needs the MT pass.
                    var mtTranslateToHindi = false
                    val (forcedLanguage, tier, translate) = when (languageHint) {
                        KeyboardSettings.LanguageHint.ENGLISH ->
                            Triple(null, AsrEngineHolder.Tier.BASE, true)
                        KeyboardSettings.LanguageHint.HINDI -> {
                            // Mirror of the AUTO branch's cheap LID pass
                            // below: HI is forced, but if the speaker
                            // actually said English, a forced-Hindi decode
                            // just phonetically mangles it into nonsense
                            // Devanagari rather than translating anything
                            // (whisper's own translate task only ever goes
                            // *into* English, never out of it — see
                            // docs/STATUS.md). Detect that case and instead
                            // transcribe cleanly as English, then run a
                            // dedicated EN->HI MT model over the result.
                            val baseEngine = AsrEngineHolder.getOrLoad(this@VoiceImeService, AsrEngineHolder.Tier.BASE)
                            val detection = baseEngine?.detectLanguage(samples, AudioRecorder.SAMPLE_RATE)
                            // NOT topLanguage == "en": whisper's single top-1
                            // guess is unreliable on short, Indian-accented
                            // English audio — it sometimes reports "hi" (or a
                            // third language entirely, e.g. "ur") outright
                            // rather than "en" with a merely-elevated Hindi
                            // probability. Gating on the top pick alone let
                            // real English utterances fall through to the old
                            // forced-Hindi decode, which hallucinates nonsense
                            // Devanagari on non-Hindi audio — confirmed live:
                            // "how is the weather today" -> "aur visdbaadar
                            // tore". Comparing the two probabilities directly
                            // catches those cases too.
                            val looksEnglish = detection != null && detection.englishProb >= detection.hindiProb
                            Log.d(
                                TAG,
                                "HI-forced LID: top=${detection?.topLanguage} " +
                                    "en=${detection?.englishProb} hi=${detection?.hindiProb} " +
                                    "-> looksEnglish=$looksEnglish",
                            )
                            if (looksEnglish && TranslationEngineHolder.hasModel(this@VoiceImeService)) {
                                mtTranslateToHindi = true
                                Triple("en", AsrEngineHolder.Tier.BASE, false)
                            } else {
                                Triple("hi", AsrEngineHolder.Tier.SMALL, false)
                            }
                        }
                        KeyboardSettings.LanguageHint.AUTO -> {
                            val baseEngine = AsrEngineHolder.getOrLoad(this@VoiceImeService, AsrEngineHolder.Tier.BASE)
                            val detection = baseEngine?.detectLanguage(samples, AudioRecorder.SAMPLE_RATE)
                            // whisper has no "Hinglish" language token — it
                            // can only report one top pick — but a Hindi
                            // probability that's meaningfully non-trivial
                            // even when English wins the top slot is a
                            // reasonable proxy for code-switched audio.
                            // Route to small+Hindi on either signal, since
                            // small has been confirmed to transcribe
                            // code-switched sentences correctly where base
                            // translates them outright.
                            val looksHindi = detection != null &&
                                (detection.topLanguage == "hi" || detection.hindiProb >= HINDI_PROB_THRESHOLD)
                            if (looksHindi) {
                                Triple("hi", AsrEngineHolder.Tier.SMALL, false)
                            } else {
                                Triple(detection?.topLanguage ?: "en", AsrEngineHolder.Tier.BASE, false)
                            }
                        }
                    }
                    // Category vocabulary hints are written in English —
                    // sent as whisper.cpp's initial_prompt while forcing
                    // Hindi, an English-language prompt actively fights the
                    // language setting (prompt conditioning biases the
                    // decoder's language/style, not just vocabulary), so
                    // those are skipped entirely for a forced-Hindi decode.
                    // Proper nouns (names) still matter for Hindi dictation
                    // though, so send those alone, transliterated into
                    // Devanagari to match rather than fight the decode
                    // language. This direction (any hint at all on the
                    // Hindi path) is untested on real speech — if Hindi
                    // quality regresses, this is the first thing to revert.
                    val hint = if (forcedLanguage == "hi") properNounHint(devanagari = true) else promptHintFor(category)

                    // `small` is meaningfully heavier than `base` (saga #10 in
                    // docs/STATUS.md); on a hot/low-battery device that cost
                    // is worth trading away rather than pushing further into
                    // either. `base` is always an acceptable fallback here —
                    // every branch above already treats it as the default
                    // tier for non-Hindi content.
                    val effectiveTier = if (deviceThrottled && tier == AsrEngineHolder.Tier.SMALL) {
                        AsrEngineHolder.Tier.BASE
                    } else {
                        tier
                    }
                    val engine = AsrEngineHolder.getOrLoad(this@VoiceImeService, effectiveTier)
                        ?: return@runCatching null
                    // Published before the (potentially many-second) decode
                    // call so a mic tap on the main thread during
                    // TRANSCRIBING has something to cancel.
                    activeEngine = engine
                    val asrResult = engine.transcribe(samples, AudioRecorder.SAMPLE_RATE, forcedLanguage, hint, translate)
                    val asrText = asrResult.text
                    // MT output is already genuine Devanagari Hindi text
                    // (opus-mt-en-hi's vocab is Devanagari, not romanized),
                    // same shape as a forced-Hindi whisper decode's raw
                    // output — feed it through the exact same script
                    // handling below rather than a separate path. Falls
                    // back to the untranslated English text if the model
                    // isn't loaded (shouldn't happen: hasModel() was
                    // already checked before choosing this branch above).
                    val raw = if (mtTranslateToHindi) {
                        TranslationEngineHolder.getOrLoad(this@VoiceImeService)?.translate(asrText) ?: asrText
                    } else {
                        asrText
                    }
                    // Devanagari output only applies when HI is explicitly
                    // forced — EN and Auto always render Latin regardless
                    // of the script toggle. Devanagari rendering only ever
                    // makes sense for Hindi content, and Auto's own
                    // routing above already decides per-utterance whether
                    // this is Hindi; overloading the script toggle to also
                    // steer routing (an earlier version did this) doubled
                    // up two independent settings into one.
                    val scripted = if (languageHint == KeyboardSettings.LanguageHint.HINDI &&
                        KeyboardSettings.devanagariMode(this@VoiceImeService)
                    ) {
                        LatinToDevanagariTransliterator.toDevanagari(raw)
                    } else {
                        // Forcing Hindi commits the whole decode to Hindi
                        // phonetics, so an English loanword ("vacation")
                        // comes out as Devanagari approximating the sound
                        // rather than being recognised as English — fix up
                        // the common cases after romanizing. Only applies
                        // to Latin output: correcting toward an English
                        // spelling inside Devanagari output wouldn't mean
                        // anything. Skipped for MT output: opus-mt-en-hi
                        // already produces natural Hindi word choice, and
                        // forcedLanguage is "en" on this path anyway (the
                        // condition below only fires for the whisper-Hindi
                        // decode path).
                        val latin = DevanagariTransliterator.toLatin(raw)
                        if (forcedLanguage == "hi") EnglishLoanwordCorrector.correct(latin) else latin
                    }
                    // lowConfidenceWords come from whisper's raw output —
                    // they're only reliably findable inside `scripted` when
                    // nothing between the two rewrote the actual words
                    // (English/Latin passthrough). Devanagari
                    // transliteration, loanword correction, and MT
                    // translation all change the text enough that a raw
                    // token substring generally won't match anymore; rather
                    // than tracking positions through each of those
                    // transforms, commitTranscript's search-and-mark is
                    // fail-open — a word that can't be found just doesn't
                    // get flagged, so this degrades silently to "no
                    // markup" on those paths instead of marking the wrong
                    // thing.
                    TranscriptionOutcome(correctForCategory(scripted, category), asrResult.lowConfidenceWords)
                }.onFailure { Log.e(TAG, "Transcription failed", it) }.getOrNull()
            }

            activeEngine = null
            busy = false
            if (recordingForPhrase) {
                // Never touches the target field at all — the result is a
                // saved phrase, not dictated text, regardless of success or
                // failure.
                recordingForPhrase = false
                voiceView?.recordingPhrase = false
                if (!outcome?.text.isNullOrBlank()) {
                    PhrasebookStore.add(this@VoiceImeService, outcome!!.text)
                    textView?.setPhrases(PhrasebookStore.list(this@VoiceImeService))
                }
                switchToTextMode()
                voiceView?.showIdle()
                return@launch
            }
            when {
                outcome == null -> voiceView?.showTransientBlocked(getString(R.string.transcription_failed))
                outcome.text.isBlank() -> voiceView?.showIdle()
                else -> {
                    commitTranscript(outcome.text, outcome.lowConfidenceWords)
                    voiceView?.showIdle()
                    updateAutoCap()
                }
            }
        }
    }

    /** [text] plus, for [commitTranscript]'s best-effort underline pass, whichever raw ASR words whisper itself was least sure about. */
    private data class TranscriptionOutcome(val text: String, val lowConfidenceWords: List<String>)

    /**
     * Length (including any leading separator space) of the most recent
     * voice commit, so [undoLastDictation] can remove exactly that span
     * rather than guessing. Zeroed by anything that mutates the field
     * afterward — see call sites below — since the tracked span is only
     * meaningful if it's still literally what's immediately before the
     * cursor.
     */
    private var lastVoiceCommitLength = 0

    /**
     * Wall-clock time of the last voice commit — [undoLastDictation] only
     * fires within [UNDO_WINDOW_MS] of this. Root cause of a real bug
     * (text committing then silently vanishing "in a lot of cases"): the
     * long-press-mic-for-undo gesture used the exact same touch surface as
     * tap-mic-to-start-recording, and a completely normal tap — someone
     * naturally starting to speak before lifting their finger — routinely
     * exceeds the long-press threshold. Without a time bound, that
     * long-press silently undid whatever was *previously* dictated, right
     * as the next recording should have started. The time window means a
     * long-press only ever does something within a few seconds of an
     * actual dictation — an unrelated long-press against an old or absent
     * commit is just a slightly slow tap, and starts recording normally.
     */
    private var lastVoiceCommitAtMs = 0L

    private fun commitTranscript(text: String, lowConfidenceWords: List<String> = emptyList()) {
        val ic = currentInputConnection ?: return
        // Re-checked here rather than trusting the flag captured at
        // recording-start: nothing should have touched the field in
        // between, but this is the one place correctness actually matters.
        // commitText already replaces a selection on its own, but the
        // smart-leading-space heuristic below is meant for appending after
        // existing dictated text — applying it to a replace would measure
        // spacing against whatever precedes the *selection*, not the
        // replacement, and could inject an unwanted space into what should
        // be a clean swap.
        val hasSelection = !ic.getSelectedText(0).isNullOrEmpty()
        val body = annotateLowConfidenceWords(text, lowConfidenceWords)
        val toCommit: CharSequence = if (hasSelection) {
            body
        } else {
            val existing = ic.getTextBeforeCursor(1, 0)
            // Separate from preceding text, but don't open a field with a space.
            val needsSpace = !existing.isNullOrEmpty() && !existing.last().isWhitespace()
            if (needsSpace) android.text.SpannableStringBuilder(" ").append(body) else body
        }
        val styled = applyBoldSpan(toCommit)
        ic.commitText(styled, 1)
        lastVoiceCommitLength = styled.length
        lastVoiceCommitAtMs = System.currentTimeMillis()
    }

    /**
     * Bolds [text] in place when the bold toggle is active — the dictation
     * counterpart to [styledText], which only covers typed text. Unlike
     * [styledText] this takes a [CharSequence], not a [String]: [text] may
     * already carry [annotateLowConfidenceWords]'s underline spans, and
     * wrapping it in a fresh [android.text.SpannableStringBuilder] (or
     * reusing one if it already is one) adds the bold span across the same
     * range without discarding those.
     */
    private fun applyBoldSpan(text: CharSequence): CharSequence {
        if (textView?.boldActive != true) return text
        val sb = text as? android.text.SpannableStringBuilder ?: android.text.SpannableStringBuilder(text)
        sb.setSpan(
            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            0,
            sb.length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return sb
    }

    /**
     * Best-effort underline under words whisper itself flagged as
     * low-confidence (see [dev.privatevoice.engine.AsrResult.lowConfidenceWords]
     * and `jni_whisper.c`'s `fullTranscribeWithConfidence`) — a visual cue
     * for which word to double-check or re-dictate, not a correctness
     * guarantee. `commitText` accepting a styled [CharSequence] is standard
     * IME practice (e.g. spell-check squiggles that persist after commit);
     * whether a given host app actually renders the span is up to that
     * app, so this degrades to plain text harmlessly if not.
     *
     * Matching is a sequential substring search, not position tracking
     * through the transliteration/correction pipeline — see the call site
     * in [finishRecording] for why that's the right tradeoff here. A word
     * that can't be found in [text] is silently skipped rather than
     * guessed at.
     */
    private fun annotateLowConfidenceWords(text: String, lowConfidenceWords: List<String>): CharSequence {
        if (lowConfidenceWords.isEmpty()) return text
        val sb = android.text.SpannableStringBuilder(text)
        var searchFrom = 0
        for (raw in lowConfidenceWords) {
            val word = raw.trim()
            if (word.isEmpty() || searchFrom >= text.length) continue
            val idx = text.indexOf(word, searchFrom)
            if (idx < 0) continue
            sb.setSpan(
                android.text.style.UnderlineSpan(),
                idx,
                idx + word.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            searchFrom = idx + word.length
        }
        return sb
    }

    /**
     * Dedicated undo-glyph handler (utility row, centre third) — removes
     * exactly the last voice commit, once, if it's still within
     * [UNDO_WINDOW_MS]. @return true if a dictation was actually undone;
     * the caller shows "Nothing to undo" when it isn't.
     */
    private fun undoLastDictation(): Boolean {
        if (lastVoiceCommitLength <= 0) return false
        if (System.currentTimeMillis() - lastVoiceCommitAtMs > UNDO_WINDOW_MS) return false
        currentInputConnection?.deleteSurroundingText(lastVoiceCommitLength, 0)
        lastVoiceCommitLength = 0
        voiceView?.showTransientBlocked(getString(R.string.undo_done))
        return true
    }

    /**
     * The plain text of the selection [boldSelectionIfAny] most recently
     * touched — cached for [saveSelectionToPhrasebook], since bolding (or
     * un-bolding) *is* a commit that replaces the selection with freshly
     * inserted (now unselected) text; by the time a following double-tap
     * fires, [currentInputConnection] no longer has anything selected to
     * read. Reset to null once read.
     */
    private var lastBoldedSelectionText: String? = null

    /**
     * The exact [CharSequence] (spans included) that was selected right
     * before [boldSelectionIfAny] replaced it — kept so
     * [saveSelectionToPhrasebook] can restore the field to precisely its
     * prior formatting (bold or not) when double-tap-B turns out to be a
     * phrasebook save rather than a real formatting request. Same lifetime
     * as [lastBoldedSelectionText].
     */
    private var lastSelectionBeforeBolding: CharSequence? = null

    /**
     * Fired after a confirmed single tap of "B" on either panel — toggles
     * bold on the field's current text selection in place, if there is
     * one, so pressing B with text selected formats it immediately rather
     * than only affecting text typed/dictated afterward (which
     * [styledText] already handles via [TextKeyboardView.boldActive]).
     * Genuinely toggles per-selection (bold → plain, not-bold → bold)
     * rather than always bolding: requesting the selection with
     * [android.view.inputmethod.InputConnection.GET_TEXT_WITH_STYLES] asks
     * the host app to include any spans it preserved on the text this
     * keyboard itself committed earlier, which is what makes "select
     * already-bold text and press B again" read as un-bold instead of a
     * no-op re-bold.
     */
    private fun boldSelectionIfAny() {
        val ic = currentInputConnection
        val selected = ic?.getSelectedText(
            android.view.inputmethod.InputConnection.GET_TEXT_WITH_STYLES,
        )
        if (ic == null || selected.isNullOrEmpty()) {
            lastBoldedSelectionText = null
            lastSelectionBeforeBolding = null
            return
        }
        lastBoldedSelectionText = selected.toString()
        lastSelectionBeforeBolding = selected
        val toCommit: CharSequence = if (isFullyBold(selected)) {
            // Un-bold: a plain String carries no spans, which is exactly
            // what dropping the existing bold formatting means here.
            selected.toString()
        } else {
            android.text.SpannableString(selected.toString()).apply {
                setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    0,
                    length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        // Delete the selection and insert the replacement as two separate
        // edits, rather than one commitText() that replaces it in place.
        // InputConnection.commitText() over a selection is an
        // Editable.replace(start, end, text) under the hood, and Android's
        // Editable does NOT clear spans pinned to that position range just
        // because the replacement CharSequence carries none of its own —
        // for same-length replacement text the old span's bounds are simply
        // re-adjusted to still cover it. That left a stale StyleSpan
        // sitting on "un-bolded" text, which every later read then saw and
        // reported as still bold, single-commitText un-bolding permanently
        // (confirmed via logcat: identical StyleSpan[0,9] on the selection
        // both before AND after committing a plain-String replacement).
        // Deleting first collapses the old span to zero width; being
        // SPAN_EXCLUSIVE_EXCLUSIVE, it does not re-expand to cover text
        // inserted afterward at that same point in a separate edit.
        ic.beginBatchEdit()
        ic.commitText("", 1)
        ic.commitText(toCommit, 1)
        ic.endBatchEdit()
    }

    /**
     * Whether [text] renders bold across its entire length — checked by
     * actually resolving every character-style span against a
     * [android.text.TextPaint], not by looking for this keyboard's own
     * [android.text.style.StyleSpan] specifically. That covers *any* way a
     * host app represents bold (a plain `StyleSpan`, a custom
     * `TypefaceSpan`, a bold-weight `Typeface`, etc.), so text made bold by
     * some other editor/keyboard un-bolds correctly too, not just text this
     * keyboard bolded itself. A partially-bold selection is treated as "not
     * bold" so a tap bolds the whole thing rather than stripping the part
     * that already was.
     *
     * Walks span-transition boundaries and checks boldness position by
     * position, rather than requiring a *single* span to cover the whole
     * range: text typed while bold is armed ([applyBoldSpan]/[styledText])
     * commits one key at a time, so a bold word ends up as several adjacent
     * one-character [android.text.style.StyleSpan]s, none of which alone
     * spans the full selection even though every character in it is bold.
     * Requiring one full-coverage span made that case (the common one, in
     * practice) always read as "not bold" and silently re-bold instead of
     * un-bolding.
     */
    private fun isFullyBold(text: CharSequence): Boolean {
        val spanned = text as? android.text.Spanned ?: return false
        if (spanned.isEmpty()) return false
        var pos = 0
        while (pos < spanned.length) {
            val paint = android.text.TextPaint()
            for (span in spanned.getSpans(pos, pos + 1, android.text.style.CharacterStyle::class.java)) {
                span.updateDrawState(paint)
            }
            if (!(paint.isFakeBoldText || paint.typeface?.isBold == true)) return false
            val next = spanned.nextSpanTransition(pos, spanned.length, android.text.style.CharacterStyle::class.java)
            pos = if (next > pos) next else pos + 1
        }
        return true
    }

    /**
     * Double-tap "B" on either panel — saves the text selection from that
     * tap's own [boldSelectionIfAny] call ([lastBoldedSelectionText]) as a
     * new phrasebook entry. @return true if something was actually saved;
     * both callers (TextKeyboardView.onDoubleTapBoldSave,
     * VoiceKeyboardView.onDoubleTapBoldSave) fall back to just opening the
     * phrasebook page when this returns false (nothing selected). A Toast,
     * not [VoiceKeyboardView.showTransientBlocked], since this needs to
     * work identically from the text keyboard, which has no message
     * surface of its own.
     *
     * Also reverts the field's own text back to [lastSelectionBeforeBolding]
     * — the exact original, formatting included: the single tap that
     * started this double-tap already bolded-or-un-bolded the selection in
     * place ([boldSelectionIfAny]) as a side effect of capturing it, but
     * double-tap-B's actual job is *saving to the phrasebook*, not
     * formatting the field — a save shouldn't leave a visible, unrequested
     * formatting change behind. The just-committed text is still exactly
     * [lastBoldedSelectionText] characters before the cursor (nothing else
     * can have touched the field in the ~[VoiceKeyboardView.DOUBLE_TAP_MS]
     * between the two taps), so swapping it back is safe.
     */
    private fun saveSelectionToPhrasebook(): Boolean {
        // Untrimmed — this is exactly what boldSelectionIfAny committed, so
        // its length is what deleteSurroundingText below must remove. The
        // phrasebook entry itself still uses the trimmed version.
        val committedLength = lastBoldedSelectionText?.length
        val restoreTo = lastSelectionBeforeBolding
        val trimmed = lastBoldedSelectionText?.trim()
        lastBoldedSelectionText = null
        lastSelectionBeforeBolding = null
        if (committedLength == null || restoreTo == null || trimmed.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.phrasebook_select_text_first), Toast.LENGTH_SHORT).show()
            return false
        }
        currentInputConnection?.let { ic ->
            ic.deleteSurroundingText(committedLength, 0)
            ic.commitText(restoreTo, 1)
        }
        PhrasebookStore.add(this, trimmed)
        textView?.setPhrases(PhrasebookStore.list(this))
        Toast.makeText(this, getString(R.string.phrasebook_saved_selection), Toast.LENGTH_SHORT).show()
        return true
    }

    private fun openPermissionScreen() {
        startActivity(
            Intent(this, SetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Reached from both modes: long-press ?123/ABC on the text keyboard, or
     * long-press the privacy glyph on the voice panel. Reuses the first-run
     * screen. [lastFieldKey] means coming back via Back returns to this
     * exact field in whichever mode it was left in, not a reset text page.
     */
    private fun openSettingsScreen() {
        startActivity(
            Intent(this, SetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (recorder.isRecording) recorder.cancel()
    }

    override fun onDestroy() {
        if (recorder.isRecording) recorder.cancel()
        scope.cancel()
        // Deliberately NOT releasing the engine: the RecognitionService in this
        // same process may still be using it, and reloading costs the user
        // latency. It goes when the process does.
        super.onDestroy()
    }

    private companion object {
        const val TAG = "VoiceIme"

        /** ~0.3s at 16kHz. Below this it's a stray tap, not speech. */
        const val MIN_SAMPLES = 4_800

        const val AMPLITUDE_POLL_MS = 40L

        /**
         * Hindi-probability floor for treating AUTO-mode audio as
         * Hindi/code-switched even when English wins the top language
         * slot. Not derived from measurement — a starting point pending
         * real-world tuning; lower it if code-switched audio still lands
         * on `base`, raise it if pure-English audio starts routing to the
         * slower `small` tier unnecessarily.
         */
        const val HINDI_PROB_THRESHOLD = 0.15f

        /** Battery percentage at/below which routing favours `base` over `small`, unless charging. */
        const val LOW_BATTERY_PCT = 15

        /** How long after a voice commit a mic long-press is still allowed to undo it. */
        const val UNDO_WINDOW_MS = 4_000L

        /**
         * Curated, not exhaustive — historical, political, sports,
         * film, and business figures widely referenced in everyday Indian
         * speech. Grows on request rather than trying to be complete.
         */
        val INDIAN_PERSONALITIES = listOf(
            // Freedom movement / historical
            "Mahatma Gandhi", "Sardar Patel", "Subhas Chandra Bose", "Bhagat Singh",
            "B R Ambedkar", "Rabindranath Tagore", "Swami Vivekananda",
            // Prime Ministers
            "Jawaharlal Nehru", "Lal Bahadur Shastri", "Indira Gandhi", "Morarji Desai",
            "Charan Singh", "Rajiv Gandhi", "V P Singh", "Chandra Shekhar",
            "P V Narasimha Rao", "Atal Bihari Vajpayee", "Deve Gowda", "I K Gujral",
            "Manmohan Singh", "Narendra Modi",
            // Politics (current/recent, non-PM)
            "Rahul Gandhi", "Amit Shah", "Arvind Kejriwal", "Mamata Banerjee",
            // Sports
            "Sachin Tendulkar", "Virat Kohli", "MS Dhoni", "Rohit Sharma", "Kapil Dev",
            "Sourav Ganguly", "Rahul Dravid", "PV Sindhu", "Saina Nehwal",
            "Neeraj Chopra", "Mary Kom",
            // Film / entertainment
            "Amitabh Bachchan", "Shah Rukh Khan", "Salman Khan", "Aamir Khan",
            "Akshay Kumar", "Hrithik Roshan", "Ranbir Kapoor", "Deepika Padukone",
            "Priyanka Chopra", "Alia Bhatt", "Kareena Kapoor", "Ranveer Singh",
            "Rajinikanth", "Lata Mangeshkar", "A R Rahman",
            // Science / business
            "APJ Abdul Kalam", "C V Raman", "Homi Bhabha", "Vikram Sarabhai",
            "Ratan Tata", "Mukesh Ambani", "Gautam Adani", "Sundar Pichai",
            "Narayana Murthy",
        )

        /**
         * Curated, not exhaustive — heads of state/government and other
         * globally significant political figures, current and historical.
         * Grows on request rather than trying to be complete.
         */
        val WORLD_LEADERS = listOf(
            // US Presidents
            "George Washington", "Abraham Lincoln", "Franklin D Roosevelt",
            "John F Kennedy", "Ronald Reagan", "Bill Clinton", "George W Bush",
            "Barack Obama", "Donald Trump", "Joe Biden",
            // UK Prime Ministers
            "Winston Churchill", "Margaret Thatcher", "Tony Blair", "Boris Johnson",
            "Rishi Sunak", "Keir Starmer",
            // Other current/recent world leaders
            "Vladimir Putin", "Xi Jinping", "Angela Merkel", "Emmanuel Macron",
            "Justin Trudeau", "Volodymyr Zelenskyy", "Benjamin Netanyahu",
            // Historical figures of global significance
            "Nelson Mandela", "Martin Luther King Jr", "Mao Zedong", "Joseph Stalin",
            "Adolf Hitler", "Napoleon Bonaparte", "Julius Caesar",
        )

        /**
         * Curated, not exhaustive — globally recognised figures outside
         * politics: sports, entertainment, business/tech, and science.
         * Grows on request rather than trying to be complete.
         */
        /**
         * Curated, not exhaustive — major Indian cities, likely to come up
         * in everyday conversation (travel, addresses, "I'm in ...") far
         * more often than celebrity names. Grows on request.
         */
        val INDIAN_CITIES = listOf(
            "Mumbai", "Delhi", "New Delhi", "Bangalore", "Bengaluru", "Kolkata",
            "Chennai", "Hyderabad", "Pune", "Ahmedabad", "Jaipur", "Lucknow",
            "Kanpur", "Nagpur", "Indore", "Bhopal", "Patna", "Surat",
            "Chandigarh", "Guwahati", "Kochi", "Coimbatore", "Visakhapatnam",
            "Varanasi", "Amritsar", "Ludhiana", "Nashik", "Vadodara", "Agra",
            "Meerut", "Rajkot", "Ranchi", "Jodhpur", "Gurgaon", "Gurugram",
            "Noida", "Thiruvananthapuram", "Mysore", "Shimla", "Dehradun",
            "Srinagar", "Bhubaneswar",
        )

        /** Matches "what's up" / "whats up" / "whatsup" as a whole phrase, not inside "whatsapp". */
        val WHATSAPP_HOMOPHONE = Regex("""\bwhat'?s\s*up\b""", RegexOption.IGNORE_CASE)
    }
}
