package dev.stagegrid.ui

import dev.stagegrid.audio.AudioEngineController

/** Keeps pre-0.4 call sites source-compatible; new UI supplies an explicit negotiated channel count. */
fun AudioEngineController.setOutputDevice(deviceId: Int) {
    setOutputDevice(deviceId, 2)
}
