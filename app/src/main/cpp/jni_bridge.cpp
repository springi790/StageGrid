#include "NativeAudioEngine.h"
#include <jni.h>
#include <memory>
#include <string>
#include <vector>

namespace {
NativeAudioEngine *engine(jlong handle) { return reinterpret_cast<NativeAudioEngine *>(handle); }
std::string jstringToUtf8(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_stagegrid_audio_NativeAudioEngine_nativeCreate(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(new NativeAudioEngine());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_stagegrid_audio_NativeAudioEngine_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    delete engine(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_stagegrid_audio_NativeAudioEngine_nativeLoadSong(JNIEnv *env, jobject, jlong handle, jobjectArray paths, jintArray types, jdouble bpm, jint beatsPerBar, jlong gridOffsetMs) {
    const jsize count = env->GetArrayLength(paths);
    if (count != env->GetArrayLength(types)) return JNI_FALSE;
    std::vector<std::string> nativePaths;
    nativePaths.reserve(count);
    for (jsize i = 0; i < count; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(paths, i));
        nativePaths.push_back(jstringToUtf8(env, item));
        env->DeleteLocalRef(item);
    }
    std::vector<int> nativeTypes(count);
    env->GetIntArrayRegion(types, 0, count, nativeTypes.data());
    return engine(handle)->loadSong(nativePaths, nativeTypes, bpm, beatsPerBar, gridOffsetMs) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeUnloadSong(JNIEnv *, jobject, jlong h) { engine(h)->unloadSong(); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativePlay(JNIEnv *, jobject, jlong h) { return engine(h)->play() ? JNI_TRUE : JNI_FALSE; }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativePause(JNIEnv *, jobject, jlong h) { engine(h)->pause(); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeStop(JNIEnv *, jobject, jlong h) { engine(h)->stop(); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSeekToMs(JNIEnv *, jobject, jlong h, jlong ms) { engine(h)->seekToMs(ms); }
extern "C" JNIEXPORT jlong JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativePositionMs(JNIEnv *, jobject, jlong h) { return engine(h)->positionMs(); }
extern "C" JNIEXPORT jlong JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeDurationMs(JNIEnv *, jobject, jlong h) { return engine(h)->durationMs(); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeIsPlaying(JNIEnv *, jobject, jlong h) { return engine(h)->isPlaying() ? JNI_TRUE : JNI_FALSE; }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSetTrackVolume(JNIEnv *, jobject, jlong h, jint i, jfloat v) { engine(h)->setTrackVolume(i, v); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSetTrackMute(JNIEnv *, jobject, jlong h, jint i, jboolean v) { engine(h)->setTrackMute(i, v); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSetTrackSolo(JNIEnv *, jobject, jlong h, jint i, jboolean v) { engine(h)->setTrackSolo(i, v); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSetTrackPan(JNIEnv *, jobject, jlong h, jint i, jfloat v) { engine(h)->setTrackPan(i, v); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSetTrackOutputRoute(JNIEnv *, jobject, jlong h, jint i, jint route) { engine(h)->setTrackOutputRoute(i, route); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSetMasterVolume(JNIEnv *, jobject, jlong h, jfloat v) { engine(h)->setMasterVolume(v); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSetClickEnabled(JNIEnv *, jobject, jlong h, jboolean v) { engine(h)->setClickEnabled(v); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSetGuideEnabled(JNIEnv *, jobject, jlong h, jboolean v) { engine(h)->setGuideEnabled(v); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSetClickVolume(JNIEnv *, jobject, jlong h, jfloat v) { engine(h)->setClickVolume(v); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSetClickSubdivision(JNIEnv *, jobject, jlong h, jint v) { engine(h)->setClickSubdivision(v); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSetClickRoute(JNIEnv *, jobject, jlong h, jint route) { engine(h)->setClickRoute(route); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSetLoop(JNIEnv *, jobject, jlong h, jboolean e, jlong s, jlong end) { engine(h)->setLoop(e, s, end); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeScheduleJump(JNIEnv *, jobject, jlong h, jlong at, jlong target, jboolean disableLoop) { engine(h)->scheduleJump(at, target, disableLoop); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeClearScheduledJump(JNIEnv *, jobject, jlong h) { engine(h)->clearScheduledJump(); }
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_stagegrid_audio_NativeAudioEngine_nativeScheduleGuideCue(JNIEnv *env, jobject, jlong h, jfloatArray samples, jlong atMs, jlong suppressUntilMs, jint route, jfloat volume) {
    if (!samples) return JNI_FALSE;
    const jsize count = env->GetArrayLength(samples);
    if (count <= 0) return JNI_FALSE;
    std::vector<float> mono(static_cast<size_t>(count));
    env->GetFloatArrayRegion(samples, 0, count, mono.data());
    if (env->ExceptionCheck()) return JNI_FALSE;
    return engine(h)->scheduleGuideCue(mono, atMs, suppressUntilMs, route, volume) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeClearGuideCue(JNIEnv *, jobject, jlong h) { engine(h)->clearGuideCue(); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativePrepareCountIn(JNIEnv *, jobject, jlong h, jlong targetMs, jint bars) { return engine(h)->prepareCountIn(targetMs, bars) ? JNI_TRUE : JNI_FALSE; }
extern "C" JNIEXPORT jlong JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeCountInRemainingMs(JNIEnv *, jobject, jlong h) { return engine(h)->countInRemainingMs(); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSetOutputDevice(JNIEnv *, jobject, jlong h, jint id) { return engine(h)->setOutputDevice(id) ? JNI_TRUE : JNI_FALSE; }
extern "C" JNIEXPORT jint JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSampleRate(JNIEnv *, jobject, jlong h) { return engine(h)->sampleRate(); }
extern "C" JNIEXPORT jint JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeFramesPerBurst(JNIEnv *, jobject, jlong h) { return engine(h)->framesPerBurst(); }
extern "C" JNIEXPORT jlong JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeUnderruns(JNIEnv *, jobject, jlong h) { return engine(h)->underruns(); }
extern "C" JNIEXPORT jfloat JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeCpuLoad(JNIEnv *, jobject, jlong h) { return engine(h)->cpuLoad(); }
extern "C" JNIEXPORT jint JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeLoadedTracks(JNIEnv *, jobject, jlong h) { return engine(h)->loadedTracks(); }
extern "C" JNIEXPORT jlong JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativePathSwaps(JNIEnv *, jobject, jlong h) { return engine(h)->pathSwaps(); }
extern "C" JNIEXPORT jlong JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativePathSwapMisses(JNIEnv *, jobject, jlong h) { return engine(h)->pathSwapMisses(); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativePathChangePending(JNIEnv *, jobject, jlong h) { return engine(h)->pathChangePending() ? JNI_TRUE : JNI_FALSE; }
extern "C" JNIEXPORT jstring JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeLastError(JNIEnv *env, jobject, jlong h) { const auto error = engine(h)->lastError(); return env->NewStringUTF(error.c_str()); }
