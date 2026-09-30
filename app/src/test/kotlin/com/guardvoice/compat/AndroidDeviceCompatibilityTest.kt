package com.guardvoice.compat

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidDeviceCompatibilityTest {
    @Test
    fun `detects xiaomi family manufacturers`() {
        assertEquals(DeviceFamily.Xiaomi, deviceFamilyFor("Xiaomi"))
        assertEquals(DeviceFamily.Xiaomi, deviceFamilyFor("redmi"))
        assertEquals(DeviceFamily.Xiaomi, deviceFamilyFor("POCO"))
    }

    @Test
    fun `detects common android manufacturer families`() {
        assertEquals(DeviceFamily.HonorHuawei, deviceFamilyFor("Honor"))
        assertEquals(DeviceFamily.HonorHuawei, deviceFamilyFor("huawei"))
        assertEquals(DeviceFamily.OppoRealmeOnePlus, deviceFamilyFor("OPPO"))
        assertEquals(DeviceFamily.OppoRealmeOnePlus, deviceFamilyFor("realme"))
        assertEquals(DeviceFamily.Vivo, deviceFamilyFor("vivo"))
        assertEquals(DeviceFamily.Samsung, deviceFamilyFor("Samsung"))
    }

    @Test
    fun `falls back to generic device family`() {
        assertEquals(DeviceFamily.Generic, deviceFamilyFor(""))
        assertEquals(DeviceFamily.Generic, deviceFamilyFor("Google"))
    }
}
