package com.universalrouter.mode.helpers

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.net.Inet4Address
import java.net.NetworkInterface

data class TetherInterface(val name: String, val ipv4: String)

data class TetheringStatus(
    val usbConnected: Boolean,
    val wifiHotspot: TetherInterface?,
    val usbTethering: TetherInterface?,
) {
    val wifiHotspotActive get() = wifiHotspot != null
    val usbTetheringActive get() = usbTethering != null
    val anyActive get() = wifiHotspotActive || usbTetheringActive

    /** Método recomendado: USB si hay cable de datos, si no WiFi. */
    val preferredMethod: TetherMethod get() = if (usbConnected) TetherMethod.USB else TetherMethod.WIFI
}

enum class TetherMethod { WIFI, USB }

/**
 * Detección del estado de tethering con APIs públicas.
 *
 * Android no ofrece a apps normales una API pública para encender la zona WiFi
 * o el USB tethering (TetheringManager#startTethering es @SystemApi), ni para
 * leer el SSID configurado (getSoftApConfiguration también es @SystemApi).
 * Por eso:
 *  - La activación se delega a la pantalla exacta de Ajustes (ver [OemSettingsNavigator]).
 *  - El estado se detecta por las interfaces de red activas (java.net.NetworkInterface),
 *    usando los nombres que asigna el framework/fabricantes.
 *  - "USB conectado" se obtiene del estado de carga (BatteryManager): un cable
 *    conectado a un ordenador/host reporta BATTERY_PLUGGED_USB.
 */
object TetheringHelper {

    private val WIFI_AP_PATTERNS = listOf(
        Regex("^ap\\d+$"),        // Pixel, Motorola, AOSP
        Regex("^swlan\\d+$"),     // Samsung, Xiaomi (Qualcomm)
        Regex("^softap\\d+$"),    // Algunos MediaTek
        Regex("^wlan[1-9]$"),     // Interfaz secundaria usada como AP en muchos SoC
        Regex("^wigig\\d+$"),
    )

    private val USB_PATTERNS = listOf(
        Regex("^rndis\\d+$"),
        Regex("^usb\\d+$"),
        Regex("^ncm\\d+$"),
    )

    fun isUsbConnected(context: Context): Boolean {
        val battery: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return plugged and BatteryManager.BATTERY_PLUGGED_USB != 0
    }

    fun status(context: Context): TetheringStatus {
        val active = activeIpv4Interfaces()
        return TetheringStatus(
            usbConnected = isUsbConnected(context),
            wifiHotspot = active.firstOrNull { iface -> WIFI_AP_PATTERNS.any { it.matches(iface.name) } },
            usbTethering = active.firstOrNull { iface -> USB_PATTERNS.any { it.matches(iface.name) } },
        )
    }

    private fun activeIpv4Interfaces(): List<TetherInterface> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .mapNotNull { nif ->
                val ip = nif.inetAddresses.toList()
                    .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                    ?.hostAddress
                ip?.let { TetherInterface(nif.name, it) }
            }
    } catch (e: Exception) {
        emptyList()
    }
}
