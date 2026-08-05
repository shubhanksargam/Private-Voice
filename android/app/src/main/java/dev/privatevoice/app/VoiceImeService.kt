package dev.privatevoice.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.privatevoice.engine.AsrEngineHolder
import dev.privatevoice.engine.AudioRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The keyboard: a real QWERTY typing surface with a mic key that opens a
 * hold-to-talk voice view, and an "ABC" key on the voice view to come back.
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

    /** Whether the current field wants TYPE_TEXT_FLAG_CAP_SENTENCES honoured. */
    private var autoCapSentences = false

    override fun onCreateInputView(): View {
        val tv = TextKeyboardView(this)
        val vv = VoiceKeyboardView(this)
        textView = tv
        voiceView = vv

        tv.onKey = { handleTextKey(it) }
        tv.onLongPressSpace = { showSystemImePicker() }

        vv.onHoldStart = { beginRecording() }
        vv.onHoldEnd = { finishRecording() }
        vv.onCancel = { cancelRecording() }
        vv.onSwitchToText = { switchToTextMode() }
        vv.onBackspace = { currentInputConnection?.deleteSurroundingText(1, 0) }
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

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)

        // Always start on the typing surface for a freshly focused field —
        // voice is opted into per-field, not remembered across them.
        switchToTextMode()
        textView?.resetToLetters()
        configureAutoCap(info)

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
        when (action) {
            is TextKeyboardView.KeyAction.Letter -> ic?.commitText(action.char.toString(), 1)
            is TextKeyboardView.KeyAction.Symbol -> ic?.commitText(action.char.toString(), 1)
            is TextKeyboardView.KeyAction.Space -> ic?.commitText(" ", 1)
            is TextKeyboardView.KeyAction.Backspace -> ic?.deleteSurroundingText(1, 0)
            is TextKeyboardView.KeyAction.Enter -> performEnterAction()
            is TextKeyboardView.KeyAction.Mic -> switchToVoiceMode()
            // Shift and the letters/symbols page toggle are fully handled
            // inside TextKeyboardView — they never reach onKey.
            is TextKeyboardView.KeyAction.Shift, is TextKeyboardView.KeyAction.SymbolsToggle -> Unit
        }
        updateAutoCap()
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
        mode = Mode.TEXT
        voiceView?.visibility = View.GONE
        textView?.visibility = View.VISIBLE
    }

    private fun switchToVoiceMode() {
        mode = Mode.VOICE
        textView?.visibility = View.GONE
        voiceView?.visibility = View.VISIBLE
    }

    /**
     * Vocabulary hint keyed off which app owns the focused field.
     *
     * [EditorInfo.packageName] is the real, always-available signal for this —
     * it is not the same thing as reading the host app's screen content, which
     * Android's IME sandboxing genuinely does not allow. This is a light nudge
     * on ambiguous audio (near-homophones like "WhatsApp" / "what's up"), not
     * a hard vocabulary constraint, so a category that guesses wrong just
     * loses the nudge rather than breaking anything.
     *
     * Package matching is a best-effort heuristic, not an exhaustive app
     * directory — unrecognised or regional apps fall through to no hint.
     */
    private fun vocabHintForCurrentField(): String? {
        val pkg = currentInputEditorInfo?.packageName?.lowercase() ?: return null
        val info = currentInputEditorInfo
        val isSearchAction = ((info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEARCH

        return when {
            pkg.contains("launcher") ||
                pkg == "com.google.android.googlequicksearchbox" ||
                pkg == "com.android.settings" && isSearchAction ->
                "Common apps: WhatsApp, Instagram, YouTube, Gmail, Chrome, Camera, Photos, Maps, " +
                    "Amazon, Flipkart, Paytm, PhonePe, Spotify, Netflix, Zoom, Uber, Settings."

            pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("messag") ||
                pkg.contains("chat") || pkg == "com.facebook.orca" || pkg == "com.instagram.android" ->
                "Casual chat: hey, okay, sure, thanks, lol, omg, see you, call me, " +
                    "on my way, sounds good, no worries, WhatsApp, Instagram."

            pkg == "com.android.settings" ->
                "Phone settings: Wi-Fi, Bluetooth, brightness, notifications, battery, " +
                    "storage, privacy, permissions, accessibility, display."

            pkg.contains("chrome") || pkg.contains("browser") || pkg.contains("firefox") || isSearchAction ->
                "Web search: search, website, .com, login, sign in, download."

            else -> null
        }
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

    private fun cancelRecording() {
        if (!recorder.isRecording) return
        recorder.cancel()
        voiceView?.showIdle()
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
        voiceView?.showTranscribing()
        val hint = vocabHintForCurrentField()

        scope.launch {
            val text = withContext(Dispatchers.Default) {
                runCatching {
                    val engine = AsrEngineHolder.getOrLoad(this@VoiceImeService)
                        ?: return@runCatching null
                    engine.transcribe(samples, AudioRecorder.SAMPLE_RATE, null, hint).text
                }.onFailure { Log.e(TAG, "Transcription failed", it) }.getOrNull()
            }

            busy = false
            when {
                text == null -> voiceView?.showBlocked(getString(R.string.transcription_failed))
                text.isBlank() -> voiceView?.showIdle()
                else -> {
                    commitTranscript(text)
                    voiceView?.showIdle()
                    updateAutoCap()
                }
            }
        }
    }

    private fun commitTranscript(text: String) {
        val ic = currentInputConnection ?: return
        val existing = ic.getTextBeforeCursor(1, 0)
        // Separate from preceding text, but don't open a field with a space.
        val needsSpace = !existing.isNullOrEmpty() && !existing.last().isWhitespace()
        ic.commitText(if (needsSpace) " $text" else text, 1)
    }

    private fun openPermissionScreen() {
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
    }
}
