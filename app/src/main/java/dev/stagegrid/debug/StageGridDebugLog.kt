package dev.stagegrid.debug

import android.util.Log
import dev.stagegrid.BuildConfig

/**
 * Central debug event stream for live StageGrid development.
 *
 * Logcat filter: `StageGrid`
 *
 * Keep events discrete: user actions, meaningful state transitions, engine operations and failures.
 * Do not log the transport position poll or PCM/audio-buffer activity.
 */
object StageGridDebugLog {
    private const val TAG = "StageGrid"

    fun action(area: String, message: String) = debug("ACTION", area, message)
    fun state(area: String, message: String) = debug("STATE", area, message)
    fun audio(message: String) = debug("AUDIO", "ENGINE", message)
    fun dsp(message: String) = debug("DSP", "ENGINE", message)
    fun io(area: String, message: String) = debug("IO", area, message)
    fun warning(area: String, message: String) {
        if (BuildConfig.DEBUG) Log.w(TAG, format("WARN", area, message))
    }

    fun error(area: String, message: String, throwable: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        val text = format("ERROR", area, message)
        if (throwable != null) Log.e(TAG, text, throwable) else Log.e(TAG, text)
    }

    private fun debug(kind: String, area: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, format(kind, area, message))
    }

    private fun format(kind: String, area: String, message: String): String =
        "[$kind][$area][${Thread.currentThread().name}] $message"
}
