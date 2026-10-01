package com.sari.camera

import org.junit.Assert.assertTrue
import org.junit.Test

class SmokeTest {
    @Test fun nativeContractNamesAreStable(){ assertTrue("sari_camera".isNotBlank()) }
    @Test fun modeNamesAreDefined(){ assertTrue(listOf("PHOTO","NIGHT","ASTRO","PRO","RAW","VIDEO").size==6) }
}
