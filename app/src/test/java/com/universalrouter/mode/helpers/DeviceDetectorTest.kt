package com.universalrouter.mode.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDetectorTest {

    private fun detect(manufacturer: String, brand: String = manufacturer, sdk: Int = 34) =
        DeviceDetector.detect(manufacturer, brand, "X", sdk, "14")

    @Test
    fun `detecta las marcas soportadas`() {
        assertEquals(Manufacturer.SAMSUNG, detect("samsung").manufacturer)
        assertEquals(Manufacturer.XIAOMI, detect("Xiaomi").manufacturer)
        assertEquals(Manufacturer.XIAOMI, detect("Xiaomi", "Redmi").manufacturer)
        assertEquals(Manufacturer.XIAOMI, detect("Xiaomi", "POCO").manufacturer)
        assertEquals(Manufacturer.MOTOROLA, detect("motorola").manufacturer)
        assertEquals(Manufacturer.GOOGLE, detect("Google").manufacturer)
        assertEquals(Manufacturer.ONEPLUS, detect("OnePlus").manufacturer)
        assertEquals(Manufacturer.OPPO, detect("OPPO").manufacturer)
        assertEquals(Manufacturer.VIVO, detect("vivo").manufacturer)
        assertEquals(Manufacturer.REALME, detect("realme").manufacturer)
        assertEquals(Manufacturer.OTHER, detect("Nothing").manufacturer)
    }

    @Test
    fun `sub-marcas y ROM`() {
        assertEquals("Redmi", detect("Xiaomi", "Redmi").brandLabel)
        assertEquals("Poco", detect("Xiaomi", "POCO").brandLabel)
        assertEquals("HyperOS", detect("Xiaomi", sdk = 34).romLabel)
        assertEquals("MIUI", detect("Xiaomi", sdk = 31).romLabel)
        assertEquals("One UI", detect("samsung").romLabel)
    }

    @Test
    fun `capacidades segun version de Android`() {
        assertTrue(detect("Google", sdk = 30).canToggleBluetoothDirectly)
        assertTrue(detect("Google", sdk = 32).canToggleBluetoothDirectly)
        assertFalse(detect("Google", sdk = 33).canToggleBluetoothDirectly)
        assertFalse(detect("Google", sdk = 30).needsBluetoothConnectPermission)
        assertTrue(detect("Google", sdk = 31).needsBluetoothConnectPermission)
        assertTrue(detect("OnePlus", sdk = 33).usesColorOsSettings)
        assertFalse(detect("OnePlus", sdk = 30).usesColorOsSettings)
    }
}
