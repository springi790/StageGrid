#include "NativeAudioEngine.h"
#include "OfflineMixRenderer.h"
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
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSetTrackOutputBus(JNIEnv *, jobject, jlong h, jint i, jint bus) { engine(h)->setTrackOutputBus(i, bus); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSetMasterVolume(JNIEnv *, jobject, jlong h, jfloat v) { engine(h)->setMasterVolume(v); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSetClickEnabled(JNIEnv *, jobject, jlong h, jboolean v) { engine(h)->setClickEnabled(v); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSetGuideEnabled(JNIEnv *, jobject, jlong h, jboolean v) { engine(h)->setGuideEnabled(v); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSetClickVolume(JNIEnv *, jobject, jlong h, jfloat v) { engine(h)->setClickVolume(v); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSetClickSubdivision(JNIEnv *, jobject, jlong h, jint v) { engine(h)->setClickSubdivision(v); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSetClickRoute(JNIEnv *, jobject, jlong h, jint route) { engine(h)->setClickRoute(route); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSetClickOutputBus(JNIEnv *, jobject, jlong h, jint bus) { engine(h)->setClickOutputBus(bus); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSetTempoRatio(JNIEnv *, jobject, jlong h, jfloat ratio) { engine(h)->setTempoRatio(ratio); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSetPitchSemitones(JNIEnv *, jobject, jlong h, jfloat semitones) { engine(h)->setPitchSemitones(semitones); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSetLoop(JNIEnv *, jobject, jlong h, jboolean e, jlong s, jlong end) { engine(h)->setLoop(e, s, end); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeScheduleJump(JNIEnv *, jobject, jlong h, jlong at, jlong target, jboolean disableLoop) { engine(h)->scheduleJump(at, target, disableLoop); }
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeClearScheduledJump(JNIEnv *, jobject, jlong h) { engine(h)->clearScheduledJump(); }
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_stagegrid_audio_NativeAudioEngine_nativeScheduleGuideCue(JNIEnv *env, jobject, jlong h, jfloatArray samples, jlong atMs, jlong suppressUntilMs, jint route, jint bus, jfloat volume) {
    if (!samples) return JNI_FALSE;
    const jsize count = env->GetArrayLength(samples);
    if (count <= 0) return JNI_FALSE;
    std::vector<float> mono(static_cast<size_t>(count));
    env->GetFloatArrayRegion(samples, 0, count, mono.data());
    if (env->ExceptionCheck()) return JNI_FALSE;
    return engine(h)->scheduleGuideCue(mono, atMs, suppressUntilMs, route, bus, volume) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeClearGuideCue(JNIEnv *, jobject, jlong h) { engine(h)->clearGuideCue(); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativePrepareCountIn(JNIEnv *, jobject, jlong h, jlong targetMs, jint bars) { return engine(h)->prepareCountIn(targetMs, bars) ? JNI_TRUE : JNI_FALSE; }
extern "C" JNIEXPORT jlong JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeCountInRemainingMs(JNIEnv *, jobject, jlong h) { return engine(h)->countInRemainingMs(); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSetOutputDevice(JNIEnv *, jobject, jlong h, jint id, jint channels) { return engine(h)->setOutputDevice(id, channels) ? JNI_TRUE : JNI_FALSE; }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeStartOutputTest(JNIEnv *, jobject, jlong h, jint channel, jint durationMs) { return engine(h)->startOutputTest(channel, durationMs) ? JNI_TRUE : JNI_FALSE; }
extern "C" JNIEXPORT jint JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeSampleRate(JNIEnv *, jobject, jlong h) { return engine(h)->sampleRate(); }
extern "C" JNIEXPORT jint JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeFramesPerBurst(JNIEnv *, jobject, jlong h) { return engine(h)->framesPerBurst(); }
extern "C" JNIEXPORT jint JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeOutputChannelCount(JNIEnv *, jobject, jlong h) { return engine(h)->outputChannelCount(); }
extern "C" JNIEXPORT jint JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeRequestedOutputChannelCount(JNIEnv *, jobject, jlong h) { return engine(h)->requestedOutputChannelCount(); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeMultichannelFallback(JNIEnv *, jobject, jlong h) { return engine(h)->multichannelFallback() ? JNI_TRUE : JNI_FALSE; }
extern "C" JNIEXPORT jint JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeOutputDeviceId(JNIEnv *, jobject, jlong h) { return engine(h)->outputDeviceId(); }
extern "C" JNIEXPORT jlong JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeUnderruns(JNIEnv *, jobject, jlong h) { return engine(h)->underruns(); }
extern "C" JNIEXPORT jfloat JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeCpuLoad(JNIEnv *, jobject, jlong h) { return engine(h)->cpuLoad(); }
extern "C" JNIEXPORT jint JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeLoadedTracks(JNIEnv *, jobject, jlong h) { return engine(h)->loadedTracks(); }
extern "C" JNIEXPORT jlong JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativePathSwaps(JNIEnv *, jobject, jlong h) { return engine(h)->pathSwaps(); }
extern "C" JNIEXPORT jlong JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativePathSwapMisses(JNIEnv *, jobject, jlong h) { return engine(h)->pathSwapMisses(); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativePathChangePending(JNIEnv *, jobject, jlong h) { return engine(h)->pathChangePending() ? JNI_TRUE : JNI_FALSE; }
extern "C" JNIEXPORT jfloat JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeTempoRatio(JNIEnv *, jobject, jlong h) { return engine(h)->tempoRatio(); }
extern "C" JNIEXPORT jfloat JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativePitchSemitones(JNIEnv *, jobject, jlong h) { return engine(h)->pitchSemitones(); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeDspActive(JNIEnv *, jobject, jlong h) { return engine(h)->dspActive() ? JNI_TRUE : JNI_FALSE; }
extern "C" JNIEXPORT jfloat JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeDspCpuLoad(JNIEnv *, jobject, jlong h) { return engine(h)->dspCpuLoad(); }
extern "C" JNIEXPORT jint JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeDspLatencyMs(JNIEnv *, jobject, jlong h) { return engine(h)->dspLatencyMs(); }
extern "C" JNIEXPORT jstring JNICALL Java_dev_stagegrid_audio_NativeAudioEngine_nativeLastError(JNIEnv *env, jobject, jlong h) { const auto error = engine(h)->lastError(); return env->NewStringUTF(error.c_str()); }

extern "C" JNIEXPORT jstring JNICALL
Java_dev_stagegrid_audio_NativeAudioEngine_nativeRenderRehearsalMix(
    JNIEnv *env,
    jobject,
    jstring outputPath,
    jobjectArray paths,
    jintArray types,
    jfloatArray volumes,
    jintArray mutes,
    jintArray solos,
    jfloatArray pans,
    jintArray routes,
    jdouble bpm,
    jint beatsPerBar,
    jlong gridOffsetMs,
    jfloat masterVolume,
    jfloat tempoRatio,
    jfloat pitchSemitones,
    jboolean clickEnabled,
    jboolean guideEnabled,
    jint clickSubdivision,
    jint clickRoute
) {
    if (!outputPath || !paths || !types || !volumes || !mutes || !solos || !pans || !routes) {
        return env->NewStringUTF("Invalid export arguments.");
    }
    const jsize count = env->GetArrayLength(paths);
    if (count <= 0 ||
        count != env->GetArrayLength(types) ||
        count != env->GetArrayLength(volumes) ||
        count != env->GetArrayLength(mutes) ||
        count != env->GetArrayLength(solos) ||
        count != env->GetArrayLength(pans) ||
        count != env->GetArrayLength(routes)) {
        return env->NewStringUTF("Export track arrays do not match.");
    }

    std::vector<int> nativeTypes(static_cast<size_t>(count));
    std::vector<float> nativeVolumes(static_cast<size_t>(count));
    std::vector<int> nativeMutes(static_cast<size_t>(count));
    std::vector<int> nativeSolos(static_cast<size_t>(count));
    std::vector<float> nativePans(static_cast<size_t>(count));
    std::vector<int> nativeRoutes(static_cast<size_t>(count));
    env->GetIntArrayRegion(types, 0, count, nativeTypes.data());
    env->GetFloatArrayRegion(volumes, 0, count, nativeVolumes.data());
    env->GetIntArrayRegion(mutes, 0, count, nativeMutes.data());
    env->GetIntArrayRegion(solos, 0, count, nativeSolos.data());
    env->GetFloatArrayRegion(pans, 0, count, nativePans.data());
    env->GetIntArrayRegion(routes, 0, count, nativeRoutes.data());
    if (env->ExceptionCheck()) return env->NewStringUTF("Could not read export track settings.");

    OfflineMixRequest request;
    request.outputPath = jstringToUtf8(env, outputPath);
    request.bpm = bpm;
    request.beatsPerBar = beatsPerBar;
    request.gridOffsetMs = gridOffsetMs;
    request.masterVolume = masterVolume;
    request.tempoRatio = tempoRatio;
    request.pitchSemitones = pitchSemitones;
    request.clickEnabled = clickEnabled == JNI_TRUE;
    request.guideEnabled = guideEnabled == JNI_TRUE;
    request.clickSubdivision = clickSubdivision;
    request.clickRoute = clickRoute;
    request.tracks.reserve(static_cast<size_t>(count));

    for (jsize i = 0; i < count; ++i) {
        auto path = static_cast<jstring>(env->GetObjectArrayElement(paths, i));
        OfflineMixTrackConfig track;
        track.path = jstringToUtf8(env, path);
        track.type = nativeTypes[static_cast<size_t>(i)];
        track.volume = nativeVolumes[static_cast<size_t>(i)];
        track.muted = nativeMutes[static_cast<size_t>(i)] != 0;
        track.solo = nativeSolos[static_cast<size_t>(i)] != 0;
        track.pan = nativePans[static_cast<size_t>(i)];
        track.route = nativeRoutes[static_cast<size_t>(i)];
        request.tracks.push_back(std::move(track));
        env->DeleteLocalRef(path);
    }

    std::string error;
    const bool ok = renderOfflineMix(request, error);
    if (!ok && error.empty()) error = "Rehearsal mix export failed.";
    return env->NewStringUTF(error.c_str());
}
