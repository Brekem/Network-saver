package com.universalrouter.mode.helpers

import android.os.Build

/** Familias de fabricante soportadas. Redmi y Poco se agrupan en XIAOMI. */
enum class Manufacturer(val displayName: String, val romName: String) {
    SAMSUNG("Samsung", "One UI"),
    XIAOMI("Xiaomi", "HyperOS / MIUI"),
    MOTOROLA("Motorola", "My UX"),
    GOOGLE("Google Pixel", "Pixel Android"),
    ONEPLUS("OnePlus", "OxygenOS"),
    OPPO("Oppo", "ColorOS"),
    VIVO("Vivo", "Funtouch OS / OriginOS"),
    REALME("Realme", "Realme UI"),
    OTHER("Android", "Android");
}

data class DeviceProfile(
    val manufacturer: Manufacturer,
    val rawManufacturer: String,
    val brand: String,
    val model: String,
    val sdkInt: Int,
    val release: String,
) {
    /** Nombre comercial de la sub-marca (Redmi/Poco/iQOO) si aplica. */
    val brandLabel: String
        get() = when {
            brand.equals("redmi", true) -> "Redmi"
            brand.equals("poco", true) -> "Poco"
            brand.equals("iqoo", true) -> "iQOO"
            else -> manufacturer.displayName
        }

    val romLabel: String
        get() = when {
            // HyperOS sustituye a MIUI a partir de Android 14.
            manufacturer == Manufacturer.XIAOMI && sdkInt >= 34 -> "HyperOS"
            manufacturer == Manufacturer.XIAOMI -> "MIUI"
            // OxygenOS se basa en ColorOS desde Android 12, misma familia de ajustes.
            else -> manufacturer.romName
        }

    /**
     * Android 13+ ya no permite a apps normales encender/apagar Bluetooth
     * (salvo Device Owner / Profile Owner).
     */
    val canToggleBluetoothDirectly: Boolean get() = sdkInt < Build.VERSION_CODES.TIRAMISU

    /** Android 12+ necesita el permiso runtime BLUETOOTH_CONNECT. */
    val needsBluetoothConnectPermission: Boolean get() = sdkInt >= Build.VERSION_CODES.S

    /** OxygenOS 12+ (y Realme) comparten la base de ajustes de ColorOS. */
    val usesColorOsSettings: Boolean
        get() = manufacturer == Manufacturer.OPPO ||
            manufacturer == Manufacturer.REALME ||
            (manufacturer == Manufacturer.ONEPLUS && sdkInt >= Build.VERSION_CODES.S)

    /** Nombre aproximado del interruptor de USB tethering en la ROM. */
    val usbTetheringLabel: String
        get() = when (manufacturer) {
            Manufacturer.SAMSUNG -> "Anclaje a red USB"
            Manufacturer.XIAOMI -> "Compartir conexión por USB"
            Manufacturer.OPPO, Manufacturer.REALME, Manufacturer.ONEPLUS -> "Compartir conexión por USB"
            Manufacturer.VIVO -> "Anclaje USB"
            else -> "Compartir conexión por USB"
        }

    /** Nombre aproximado del interruptor de la zona WiFi en la ROM. */
    val hotspotLabel: String
        get() = when (manufacturer) {
            Manufacturer.SAMSUNG -> "Zona Wi-Fi móvil"
            Manufacturer.XIAOMI -> "Zona Wi-Fi portátil"
            Manufacturer.GOOGLE, Manufacturer.MOTOROLA -> "Zona Wi-Fi"
            Manufacturer.OPPO, Manufacturer.REALME, Manufacturer.ONEPLUS -> "Zona Wi-Fi personal"
            Manufacturer.VIVO -> "Zona Wi-Fi personal"
            Manufacturer.OTHER -> "Zona Wi-Fi"
        }

    val summary: String
        get() = "$brandLabel $model · Android $release (API $sdkInt) · $romLabel"
}

object DeviceDetector {

    fun detect(): DeviceProfile = detect(
        manufacturer = Build.MANUFACTURER.orEmpty(),
        brand = Build.BRAND.orEmpty(),
        model = Build.MODEL.orEmpty(),
        sdkInt = Build.VERSION.SDK_INT,
        release = Build.VERSION.RELEASE.orEmpty(),
    )

    fun detect(
        manufacturer: String,
        brand: String,
        model: String,
        sdkInt: Int,
        release: String,
    ): DeviceProfile {
        val m = manufacturer.lowercase()
        val b = brand.lowercase()
        val family = when {
            m.contains("samsung") -> Manufacturer.SAMSUNG
            m.contains("xiaomi") || b in setOf("xiaomi", "redmi", "poco") -> Manufacturer.XIAOMI
            m.contains("motorola") || b == "motorola" -> Manufacturer.MOTOROLA
            m == "google" -> Manufacturer.GOOGLE
            m.contains("oneplus") || b == "oneplus" -> Manufacturer.ONEPLUS
            b == "realme" || m.contains("realme") -> Manufacturer.REALME
            m.contains("oppo") || b == "oppo" -> Manufacturer.OPPO
            m.contains("vivo") || b == "vivo" || b == "iqoo" -> Manufacturer.VIVO
            else -> Manufacturer.OTHER
        }
        return DeviceProfile(family, manufacturer, brand, model, sdkInt, release)
    }
}
