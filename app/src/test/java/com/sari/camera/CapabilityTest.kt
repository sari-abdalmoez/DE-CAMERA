package com.sari.camera

import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityTest {
    @Test fun profileLimitsAreBounded() {
        assertTrue(HardwareProfile.LOW_END.maxBurst < HardwareProfile.MID_RANGE.maxBurst)
        assertTrue(HardwareProfile.MID_RANGE.maxBurst < HardwareProfile.FLAGSHIP.maxBurst)
        assertTrue(HardwareProfile.LOW_END.maxPixels < HardwareProfile.FLAGSHIP.maxPixels)
        assertTrue(HardwareProfile.LOW_END.workerThreads <= 2)
    }
}
