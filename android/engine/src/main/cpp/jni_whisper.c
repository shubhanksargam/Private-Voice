// JNI bridge for whisper.cpp.
//
// Adapted from whisper.cpp's examples/whisper.android, with deliberate changes:
//
//   1. `language` is a parameter, not hardcoded. Upstream's example pins
//      params.language = "en", which silently mistranslates non-English audio
//      into fluent-but-wrong English rather than transcribing it. That exact
//      bug already cost this project one full benchmark run on the previous
//      backend — see docs/M0_RESULTS.md.
//   2. Realtime/timestamp printing is off. Upstream prints every segment to
//      logcat as it decodes, which distorts the latency this project measures.
//   3. Adds fullTranscribeToString, returning the joined text in one JNI call
//      instead of making callers loop over segment getters across the boundary.
//
// whisper.cpp is NOT thread-safe: a whisper_context must be touched from one
// thread at a time. That invariant is enforced on the Kotlin side by confining
// every call to a single-threaded dispatcher (see WhisperCppEngine).

#include <jni.h>
#include <android/log.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <sys/sysinfo.h>
#include <time.h>
#include "whisper.h"
#include "ggml.h"

#define UNUSED(x) (void)(x)
#define TAG "WhisperCppJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static double now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1.0e6;
}

struct deadline { double expires_at_ms; bool fired; };

// Set by requestCancel(), polled by abort_on_deadline() during decode.
//
// A single process-wide flag, not per-context, is deliberate: every decode
// in this app runs on a single-thread-confined executor per WhisperCppEngine
// instance, and the UI only ever has one recording/transcription in flight
// at a time (gated by VoiceImeService's `busy` flag) even though two engine
// instances — base and small — can exist simultaneously. A per-context flag
// would be more "correct" for a hypothetical concurrent-decode case that
// doesn't actually happen here.
static volatile bool g_cancel_requested = false;

// whisper.cpp polls this during decoding; returning true aborts the run.
static bool abort_on_deadline(void *user_data) {
    struct deadline *d = (struct deadline *) user_data;
    if (g_cancel_requested) {
        if (!d->fired) {
            d->fired = true;
            LOGW("decode cancelled by user, aborting");
        }
        return true;
    }
    if (d->expires_at_ms <= 0.0) return false;   // unbounded
    if (now_ms() >= d->expires_at_ms) {
        if (!d->fired) {
            d->fired = true;
            LOGW("decode exceeded budget, aborting");
        }
        return true;
    }
    return false;
}

JNIEXPORT void JNICALL
Java_dev_privatevoice_engine_WhisperLib_requestCancel(JNIEnv *env, jobject thiz) {
    UNUSED(env);
    UNUSED(thiz);
    g_cancel_requested = true;
}

JNIEXPORT jlong JNICALL
Java_dev_privatevoice_engine_WhisperLib_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    UNUSED(thiz);
    struct whisper_context_params cparams = whisper_context_default_params();
    const char *model_path = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    struct whisper_context *context = whisper_init_from_file_with_params(model_path, cparams);
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path);
    if (context == NULL) {
        LOGW("whisper_init_from_file_with_params returned NULL");
        return 0;
    }
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_dev_privatevoice_engine_WhisperLib_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    if (context_ptr == 0) return;
    whisper_free((struct whisper_context *) context_ptr);
}

// Runs one decode and returns all segments joined. Returns NULL on failure so
// the Kotlin side can raise rather than silently yielding empty text.
JNIEXPORT jstring JNICALL
Java_dev_privatevoice_engine_WhisperLib_fullTranscribeToString(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads,
        jfloatArray audio_data, jstring language_str, jboolean translate,
        jint timeout_ms, jstring initial_prompt_str) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    if (context == NULL) return NULL;

    // Clear any stale cancel from a previous call — without this, a cancel
    // that arrived just after the last decode already finished on its own
    // would silently abort this new, unrelated one instantly.
    g_cancel_requested = false;

    jfloat *audio = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize n_samples = (*env)->GetArrayLength(env, audio_data);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = (translate == JNI_TRUE);
    params.n_threads = num_threads;
    params.offset_ms = 0;
    // no_context: don't carry decoder state between utterances. Dictation
    // utterances are independent, and carrying context lets one bad decode
    // poison the next.
    params.no_context = true;
    params.single_segment = false;

    // NOTE: temperature fallback is deliberately LEFT ENABLED (the default).
    //
    // It looks like an obvious latency win to disable: when a decode trips
    // whisper.cpp's entropy/logprob thresholds it retries at successively
    // higher temperatures, up to six full decodes, which on ggml-small-q8_0
    // turned ~4.3s utterances into 14.3s and sometimes past a 30s budget.
    //
    // Setting temperature_inc = 0.0f was tried and made things strictly
    // worse: even ggml-base — previously a clean 1447ms median — then timed
    // out during warm-up. The fallback chain is also an escape hatch from
    // decodes that fail to advance whisper.cpp's seek position, so removing
    // it trades an occasional slow decode for an occasional non-terminating
    // one. Keep the default and bound the work with abort_callback instead.

    // Bound the decode by wall-clock, not by token count.
    //
    // params.max_tokens was tried first and is actively harmful here: it caps
    // tokens *per segment*, and if the cap lands before whisper.cpp emits a
    // timestamp token, the decoder's seek position never advances and it
    // re-decodes the same window forever. That converted an occasional slow
    // utterance into a hard hang with four threads pegged indefinitely.
    //
    // abort_callback is the mechanism intended for this: whisper.cpp polls it
    // during decoding and unwinds cleanly when it returns true. Whatever text
    // was decoded before the abort is still retrievable, so a timeout degrades
    // to a partial result rather than an error.
    struct deadline dl = {
        .expires_at_ms = (timeout_ms > 0) ? now_ms() + (double) timeout_ms : 0.0,
        .fired = false,
    };
    params.abort_callback = abort_on_deadline;
    params.abort_callback_user_data = &dl;

    const char *language = NULL;
    if (language_str != NULL) {
        language = (*env)->GetStringUTFChars(env, language_str, NULL);
        params.language = language;
    } else {
        // NULL asks whisper.cpp to auto-detect from the first window.
        params.language = NULL;
    }

    // Vocabulary hint: text conditioning, not literal dictation. Whisper's
    // language-model prior otherwise favours the common phrase over an
    // unusual-but-correct proper noun on near-homophones — "WhatsApp" decoded
    // as "what's up" was the case that motivated this. Standard technique for
    // this failure mode; see WhisperCppEngine.kt for the actual hint text.
    const char *initial_prompt = NULL;
    if (initial_prompt_str != NULL) {
        initial_prompt = (*env)->GetStringUTFChars(env, initial_prompt_str, NULL);
        params.initial_prompt = initial_prompt;
    }

    whisper_reset_timings(context);
    const int rc = whisper_full(context, params, audio, n_samples);

    if (language != NULL) {
        (*env)->ReleaseStringUTFChars(env, language_str, language);
    }
    if (initial_prompt != NULL) {
        (*env)->ReleaseStringUTFChars(env, initial_prompt_str, initial_prompt);
    }
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio, JNI_ABORT);

    const int n_segments = whisper_full_n_segments(context);

    // A timed-out decode reports failure but leaves the segments it already
    // produced intact, so prefer returning partial text over nothing. Only a
    // failure with no segments at all is a real error.
    if (rc != 0 && !(dl.fired && n_segments > 0)) {
        LOGW("whisper_full failed with %d (segments=%d)", rc, n_segments);
        return NULL;
    }
    size_t total = 1;
    for (int i = 0; i < n_segments; i++) {
        total += strlen(whisper_full_get_segment_text(context, i));
    }
    char *joined = (char *) calloc(total, 1);
    if (joined == NULL) {
        LOGW("calloc of %zu bytes failed", total);
        return NULL;
    }
    for (int i = 0; i < n_segments; i++) {
        strcat(joined, whisper_full_get_segment_text(context, i));
    }

    // NewStringUTF expects modified-UTF8; whisper.cpp emits standard UTF-8.
    // They agree for everything in the BMP, which covers Devanagari and Latin.
    jstring result = (*env)->NewStringUTF(env, joined);
    free(joined);
    return result;
}

// Below WORD_CONFIDENCE_THRESHOLD (whisper.cpp's per-token softmax
// probability), a word gets flagged for fullTranscribeWithConfidence. Same
// status as HINDI_PROB_THRESHOLD on the Kotlin side: a starting guess, not
// a tuned value. A word's confidence is the MIN over its sub-word tokens —
// one shaky token is enough to flag the whole word, since a wrong sub-token
// usually means the whole word reads wrong.
#define WORD_CONFIDENCE_THRESHOLD 0.5f

// Appends `word` to a growable array of malloc'd strings, growing by
// doubling. Best-effort: on allocation failure, silently drops the word
// rather than aborting the decode over a purely cosmetic feature.
static void push_low_conf_word(char ***words, int *count, int *cap, const char *word) {
    if (*count >= *cap) {
        const int new_cap = (*cap == 0) ? 8 : (*cap * 2);
        char **grown = (char **) realloc(*words, (size_t) new_cap * sizeof(char *));
        if (grown == NULL) return;
        *words = grown;
        *cap = new_cap;
    }
    char *copy = strdup(word);
    if (copy == NULL) return;
    (*words)[*count] = copy;
    (*count)++;
}

// Same decode as fullTranscribeToString above, but additionally walks each
// segment's tokens to flag low-confidence words. A deliberate sibling
// function rather than a shared refactor of fullTranscribeToString: that
// one is already the path every real dictation goes through, and copying
// its setup here keeps this feature's risk contained to a function nothing
// else calls, instead of touching the one every existing call site depends
// on.
//
// Returns [joinedText, lowConfWord0, lowConfWord1, ...] — joinedText is
// byte-for-byte the same string fullTranscribeToString would have
// returned for the same input. The low-confidence entries are the literal
// token text (including whisper's leading-space word-boundary marker,
// where present) for each flagged word, in decode order; duplicates are
// possible and intentional (a repeated word gets flagged each time it's
// low-confidence). The Kotlin side sequentially searches joinedText for
// each one to recover a position, rather than this function computing
// byte offsets itself — simpler and safer than getting UTF-8-byte-vs-Java-
// char offset math right on this side of the boundary.
JNIEXPORT jobjectArray JNICALL
Java_dev_privatevoice_engine_WhisperLib_fullTranscribeWithConfidence(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads,
        jfloatArray audio_data, jstring language_str, jboolean translate,
        jint timeout_ms, jstring initial_prompt_str) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    if (context == NULL) return NULL;

    g_cancel_requested = false;

    jfloat *audio = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize n_samples = (*env)->GetArrayLength(env, audio_data);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = (translate == JNI_TRUE);
    params.n_threads = num_threads;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;

    struct deadline dl = {
        .expires_at_ms = (timeout_ms > 0) ? now_ms() + (double) timeout_ms : 0.0,
        .fired = false,
    };
    params.abort_callback = abort_on_deadline;
    params.abort_callback_user_data = &dl;

    const char *language = NULL;
    if (language_str != NULL) {
        language = (*env)->GetStringUTFChars(env, language_str, NULL);
        params.language = language;
    } else {
        params.language = NULL;
    }

    const char *initial_prompt = NULL;
    if (initial_prompt_str != NULL) {
        initial_prompt = (*env)->GetStringUTFChars(env, initial_prompt_str, NULL);
        params.initial_prompt = initial_prompt;
    }

    whisper_reset_timings(context);
    const int rc = whisper_full(context, params, audio, n_samples);

    if (language != NULL) {
        (*env)->ReleaseStringUTFChars(env, language_str, language);
    }
    if (initial_prompt != NULL) {
        (*env)->ReleaseStringUTFChars(env, initial_prompt_str, initial_prompt);
    }
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio, JNI_ABORT);

    const int n_segments = whisper_full_n_segments(context);
    if (rc != 0 && !(dl.fired && n_segments > 0)) {
        LOGW("whisper_full failed with %d (segments=%d)", rc, n_segments);
        return NULL;
    }

    size_t total = 1;
    for (int i = 0; i < n_segments; i++) {
        total += strlen(whisper_full_get_segment_text(context, i));
    }
    char *joined = (char *) calloc(total, 1);
    if (joined == NULL) {
        LOGW("calloc of %zu bytes failed", total);
        return NULL;
    }
    for (int i = 0; i < n_segments; i++) {
        strcat(joined, whisper_full_get_segment_text(context, i));
    }

    char **low_conf_words = NULL;
    int low_conf_count = 0;
    int low_conf_cap = 0;
    const whisper_token eot = whisper_token_eot(context);

    for (int i = 0; i < n_segments; i++) {
        const int n_tok = whisper_full_n_tokens(context, i);
        char word_buf[1024];
        word_buf[0] = '\0';
        float word_min_p = 1.0f;
        bool word_has_tokens = false;

        for (int j = 0; j < n_tok; j++) {
            const whisper_token id = whisper_full_get_token_id(context, i, j);
            if (id >= eot) continue; // special/timestamp token, not real text

            const char *tok_text = whisper_full_get_token_text(context, i, j);
            const float p = whisper_full_get_token_p(context, i, j);
            // A token starting with whisper's word-boundary space marks the
            // start of a new word, UNLESS it's the first token this segment
            // has seen at all (nothing to flush yet).
            const bool starts_new_word = word_has_tokens && tok_text[0] == ' ';

            if (starts_new_word) {
                if (word_min_p < WORD_CONFIDENCE_THRESHOLD) {
                    push_low_conf_word(&low_conf_words, &low_conf_count, &low_conf_cap, word_buf);
                }
                word_buf[0] = '\0';
                word_min_p = 1.0f;
            }

            strncat(word_buf, tok_text, sizeof(word_buf) - strlen(word_buf) - 1);
            if (p < word_min_p) word_min_p = p;
            word_has_tokens = true;
        }
        if (word_has_tokens && word_min_p < WORD_CONFIDENCE_THRESHOLD) {
            push_low_conf_word(&low_conf_words, &low_conf_count, &low_conf_cap, word_buf);
        }
    }

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, low_conf_count + 1, stringClass, NULL);
    (*env)->SetObjectArrayElement(env, result, 0, (*env)->NewStringUTF(env, joined));
    for (int k = 0; k < low_conf_count; k++) {
        (*env)->SetObjectArrayElement(env, result, k + 1, (*env)->NewStringUTF(env, low_conf_words[k]));
        free(low_conf_words[k]);
    }
    free(low_conf_words);
    free(joined);
    return result;
}

// Cheap language identification: one encoder pass + a single decode step
// to read the language-token logits, not a full autoregressive
// transcription. whisper_lang_auto_detect() runs the encoder internally —
// only the mel spectrogram needs to be prepared first. Used to route
// AUTO-mode dictation to the right model tier without paying a full
// `small` decode just to find out what language was spoken.
//
// Returns [topLanguageCode, englishProb, hindiProb] rather than just the
// top pick: whisper.cpp's language set has no "code-switched"/"Hinglish"
// category (it's not one of the ~99 languages it was trained on), so that
// can't be a literal output — but a Hindi probability that's meaningfully
// non-trivial even when English wins the top slot is a good proxy for
// code-switched audio, and this project already knows `small` handles that
// correctly (see docs/STATUS.md) where `base` mistranslates it. The Kotlin
// side turns these two probabilities into that routing decision.
JNIEXPORT jobjectArray JNICALL
Java_dev_privatevoice_engine_WhisperLib_detectLanguage(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads,
        jfloatArray audio_data) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    if (context == NULL) return NULL;

    jfloat *audio = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize n_samples = (*env)->GetArrayLength(env, audio_data);

    if (whisper_pcm_to_mel(context, audio, n_samples, num_threads) != 0) {
        LOGW("whisper_pcm_to_mel failed for language detection");
        (*env)->ReleaseFloatArrayElements(env, audio_data, audio, JNI_ABORT);
        return NULL;
    }

    const int max_id = whisper_lang_max_id();
    float *lang_probs = (float *) calloc((size_t) max_id + 1, sizeof(float));
    const int lang_id = (lang_probs != NULL)
        ? whisper_lang_auto_detect(context, 0, num_threads, lang_probs)
        : -1;

    (*env)->ReleaseFloatArrayElements(env, audio_data, audio, JNI_ABORT);

    if (lang_id < 0) {
        free(lang_probs);
        return NULL;
    }

    const int en_id = whisper_lang_id("en");
    const int hi_id = whisper_lang_id("hi");
    const float en_prob = (en_id >= 0 && en_id <= max_id) ? lang_probs[en_id] : 0.0f;
    const float hi_prob = (hi_id >= 0 && hi_id <= max_id) ? lang_probs[hi_id] : 0.0f;
    free(lang_probs);

    char en_buf[16];
    char hi_buf[16];
    snprintf(en_buf, sizeof(en_buf), "%.4f", (double) en_prob);
    snprintf(hi_buf, sizeof(hi_buf), "%.4f", (double) hi_prob);

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, 3, stringClass, NULL);
    (*env)->SetObjectArrayElement(env, result, 0, (*env)->NewStringUTF(env, whisper_lang_str(lang_id)));
    (*env)->SetObjectArrayElement(env, result, 1, (*env)->NewStringUTF(env, en_buf));
    (*env)->SetObjectArrayElement(env, result, 2, (*env)->NewStringUTF(env, hi_buf));
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_privatevoice_engine_WhisperLib_getSystemInfo(
        JNIEnv *env, jobject thiz) {
    UNUSED(thiz);
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}
