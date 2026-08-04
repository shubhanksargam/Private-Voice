package dev.privatevoice.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import dev.privatevoice.engine.AsrEngineHolder
import dev.privatevoice.engine.AudioRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The voice keyboard.
 *
 * Hold the mic to record, release to transcribe and commit. Deliberately not a
 * full keyboard: it declares no letter keys and is meant to sit alongside a
 * typing keyboard (HeliBoard or similar), which the user switches back to.
 *
 * Everything runs on-device. The app holds no INTERNET permission at all — a
 * Gradle task fails the build if one ever appears in the merged manifest — so
 * audio cannot leave the phone even if something here were compromised.
 */
class VoiceImeService : InputMethodService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recorder = AudioRecorder()

    private var view: VoiceKeyboardView? = null
    private var busy = false

    override fun onCreateInputView(): View {
        val v = VoiceKeyboardView(this)
        view = v

        v.onHoldStart = { beginRecording() }
        v.onHoldEnd = { finishRecording() }
        v.onCancel = { cancelRecording() }
        v.onSwitchKeyboard = { switchAwayFromSelf() }
        v.onBackspace = { currentInputConnection?.deleteSurroundingText(1, 0) }

        // Warm the model on a background thread as soon as the keyboard is
        // built, so the first press doesn't pay a multi-hundred-ms load.
        scope.launch(Dispatchers.Default) { AsrEngineHolder.getOrLoad(this@VoiceImeService) }
        return v
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val v = view ?: return

        when {
            !hasMicPermission() -> v.showBlocked(getString(R.string.needs_mic_permission))
            isSensitiveField(info) -> v.showBlocked(getString(R.string.disabled_for_password))
            !AsrEngineHolder.hasModel(this) -> v.showBlocked(getString(R.string.no_model_installed))
            else -> v.showIdle()
        }
    }

    /**
     * Never dictate into a password or similar. Speaking a credential aloud is
     * the user's business, but silently routing it through a speech engine is
     * not something a keyboard should do by default.
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
            view?.showBlocked(getString(R.string.needs_mic_permission))
            openPermissionScreen()
            return
        }
        try {
            recorder.start()
            view?.showListening()
            pumpAmplitude()
        } catch (t: Throwable) {
            Log.e(TAG, "Could not start recording", t)
            view?.showBlocked(t.message ?: getString(R.string.mic_unavailable))
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
                view?.amplitude = recorder.amplitude
                kotlinx.coroutines.delay(AMPLITUDE_POLL_MS)
            }
            view?.amplitude = 0f
        }
    }

    private fun cancelRecording() {
        if (!recorder.isRecording) return
        recorder.cancel()
        view?.showIdle()
    }

    private fun finishRecording() {
        if (!recorder.isRecording) return
        val samples = recorder.stop()

        // Ignore taps and stray presses rather than sending a fraction of a
        // second of noise to the model and committing whatever it invents.
        if (samples.size < MIN_SAMPLES) {
            view?.showIdle()
            return
        }

        busy = true
        view?.showTranscribing()

        scope.launch {
            val text = withContext(Dispatchers.Default) {
                runCatching {
                    val engine = AsrEngineHolder.getOrLoad(this@VoiceImeService)
                        ?: return@runCatching null
                    engine.transcribe(samples, AudioRecorder.SAMPLE_RATE, null).text
                }.onFailure { Log.e(TAG, "Transcription failed", it) }.getOrNull()
            }

            busy = false
            when {
                text == null -> view?.showBlocked(getString(R.string.transcription_failed))
                text.isBlank() -> view?.showIdle()
                else -> {
                    commit(text)
                    view?.showIdle()
                }
            }
        }
    }

    private fun commit(text: String) {
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

    /** Hand back to the user's typing keyboard. */
    private fun switchAwayFromSelf() {
        if (!switchToPreviousInputMethod()) {
            // No previous IME (e.g. this is the only one enabled); fall back to
            // the system picker rather than trapping the user here.
            switchToNextInputMethod(false)
        }
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
