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
#include <stdlib.h>
#include <string.h>
#include <sys/sysinfo.h>
#include "whisper.h"
#include "ggml.h"

#define UNUSED(x) (void)(x)
#define TAG "WhisperCppJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

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
        jfloatArray audio_data, jstring language_str, jboolean translate) {
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

    const char *language = NULL;
    if (language_str != NULL) {
        language = (*env)->GetStringUTFChars(env, language_str, NULL);
        params.language = language;
    } else {
        // NULL asks whisper.cpp to auto-detect from the first window.
        params.language = NULL;
    }

    whisper_reset_timings(context);
    const int rc = whisper_full(context, params, audio, n_samples);

    if (language != NULL) {
        (*env)->ReleaseStringUTFChars(env, language_str, language);
    }
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio, JNI_ABORT);

    if (rc != 0) {
        LOGW("whisper_full failed with %d", rc);
        return NULL;
    }

    const int n_segments = whisper_full_n_segments(context);
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
