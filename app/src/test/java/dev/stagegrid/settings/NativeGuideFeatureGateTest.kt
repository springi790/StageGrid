package dev.stagegrid.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeGuideFeatureGateTest {
    @Test
    fun gateCanBeDisabledAndEnabledExplicitly() {
        try {
            NativeGuideFeatureGate.update(false)
            assertFalse(NativeGuideFeatureGate.enabled)

            NativeGuideFeatureGate.update(true)
            assertTrue(NativeGuideFeatureGate.enabled)
        } finally {
            NativeGuideFeatureGate.update(false)
        }
    }
}
