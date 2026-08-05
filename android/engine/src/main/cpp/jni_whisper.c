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

// whisper.cpp polls this during decoding; returning true aborts the run.
static bool abort_on_deadline(void *user_data) {
    struct deadline *d = (struct deadline *) user_data;
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

JNIEXPORT jstring JNICALL
Java_dev_privatevoice_engine_WhisperLib_getSystemInfo(
        JNIEnv *env, jobject thiz) {
    UNUSED(thiz);
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}
