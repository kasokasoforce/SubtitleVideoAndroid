#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <sstream>
#include <string>
#include <thread>
#include "whisper.h"

#define TAG "SubtitleWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static void throw_java(JNIEnv *env, const char *message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) env->ThrowNew(cls, message);
}

static std::string sanitize(const char *value) {
    std::string text = value ? value : "";
    for (char &c : text) {
        if (c == '\t' || c == '\r' || c == '\n') c = ' ';
    }
    while (!text.empty() && text.front() == ' ') text.erase(text.begin());
    while (!text.empty() && text.back() == ' ') text.pop_back();
    return text;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_oai_subtitlevideo_whisper_WhisperNative_nativeInit(
        JNIEnv *env, jobject, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (ctx == nullptr) {
        throw_java(env, "Whisperモデルを読み込めませんでした");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_oai_subtitlevideo_whisper_WhisperNative_nativeFree(
        JNIEnv *, jobject, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx != nullptr) whisper_free(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_oai_subtitlevideo_whisper_WhisperNative_nativeTranscribe(
        JNIEnv *env,
        jobject,
        jlong handle,
        jfloatArray samples,
        jstring language,
        jint requestedThreads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) {
        throw_java(env, "Whisperコンテキストが無効です");
        return nullptr;
    }

    const jsize count = env->GetArrayLength(samples);
    jfloat *audio = env->GetFloatArrayElements(samples, nullptr);
    const char *langChars = env->GetStringUTFChars(language, nullptr);
    std::string lang(langChars ? langChars : "zh");
    env->ReleaseStringUTFChars(language, langChars);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = lang.c_str();
    const int hw = static_cast<int>(std::thread::hardware_concurrency());
    const int safeThreads = std::max(1, std::min(static_cast<int>(requestedThreads), hw > 0 ? hw : 4));
    params.n_threads = safeThreads;
    params.no_context = false;
    params.single_segment = false;
    params.suppress_blank = true;

    const int result = whisper_full(ctx, params, audio, count);
    env->ReleaseFloatArrayElements(samples, audio, JNI_ABORT);
    if (result != 0) {
        LOGE("whisper_full failed: %d", result);
        throw_java(env, "Whisper文字起こしに失敗しました");
        return nullptr;
    }

    std::ostringstream out;
    const int n = whisper_full_n_segments(ctx);
    for (int i = 0; i < n; ++i) {
        const int64_t t0 = whisper_full_get_segment_t0(ctx, i) * 10;
        const int64_t t1 = whisper_full_get_segment_t1(ctx, i) * 10;
        const std::string text = sanitize(whisper_full_get_segment_text(ctx, i));
        if (!text.empty()) out << t0 << '\t' << t1 << '\t' << text << '\n';
    }
    return env->NewStringUTF(out.str().c_str());
}
