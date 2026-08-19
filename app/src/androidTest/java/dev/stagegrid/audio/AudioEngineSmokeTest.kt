package dev.stagegrid.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioEngineSmokeTest {
    @Test fun nativeLibraryLoadsAndReportsSaneDefaultDiagnostics() {
        NativeAudioEngine().use { engine ->
            val diagnostics = engine.diagnostics()
            assertTrue(diagnostics.sampleRate > 0)
            assertTrue(diagnostics.loadedTracks == 0)
        }
    }
}
