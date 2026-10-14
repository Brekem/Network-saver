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
        Regex("^wlan\\d+$"),      // En la lista de tethering activo, cualquier wlan es AP
        Regex("^ap_br_\\w+$"),    // Puente AP dual-band (Samsung One UI 6+/Android 14+)
        Regex("^wigig\\d+$"),
    )

    private val USB_PATTERNS = listOf(
        Regex("^rndis\\d+$"),
        Regex("^usb\\d+$"),
        Regex("^ncm\\d+$"),
    )

    /**
     * Broadcast sticky ConnectivityManager.ACTION_TETHER_STATE_CHANGED (@hide,
     * pero el sistema lo envía sticky a todas las apps). "tetherArray" contiene
     * las interfaces que están compartiendo internet en este momento.
     */
    private const val ACTION_TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"
    private const val EXTRA_ACTIVE_TETHER = "tetherArray"

    private fun activeTetheredInterfaces(context: Context): List<String>? = try {
        context.registerReceiver(null, IntentFilter(ACTION_TETHER_STATE_CHANGED))
            ?.getStringArrayListExtra(EXTRA_ACTIVE_TETHER)
    } catch (e: Exception) {
        null
    }

    fun isUsbConnected(context: Context): Boolean {
        val battery: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return plugged and BatteryManager.BATTERY_PLUGGED_USB != 0
    }

    fun status(context: Context): TetheringStatus {
        val ips = activeIpv4Interfaces().associate { it.name to it.ipv4 }
        val tethered = activeTetheredInterfaces(context)

        fun find(patterns: List<Regex>): TetherInterface? {
            // Fuente fiable: la lista de tethering activo del sistema.
            if (tethered != null) {
                val name = tethered.firstOrNull { n -> patterns.any { it.matches(n) } } ?: return null
                return TetherInterface(name, ips[name] ?: ipv4Of(name) ?: "—")
            }
            // Respaldo: interfaces con IPv4 privada (sin wlan0, que es la WiFi cliente).
            return ips.entries
                .firstOrNull { (n, _) -> n != "wlan0" && patterns.any { it.matches(n) } }
                ?.let { TetherInterface(it.key, it.value) }
        }

        return TetheringStatus(
            usbConnected = isUsbConnected(context),
            wifiHotspot = find(WIFI_AP_PATTERNS),
            usbTethering = find(USB_PATTERNS),
        )
    }

    /** Algunas ROM (Samsung) no listan el AP en getNetworkInterfaces(), pero sí responden por nombre. */
    private fun ipv4Of(name: String): String? = try {
        NetworkInterface.getByName(name)?.inetAddresses?.toList()
            ?.firstOrNull { it is Inet4Address }?.hostAddress
    } catch (e: Exception) {
        null
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
