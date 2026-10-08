package com.universalrouter.mode.helpers

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

/** Pantallas de configuración a las que la app puede enviar al usuario. */
enum class SettingsTarget {
    WIFI_HOTSPOT,
    USB_TETHERING,
    BLUETOOTH,
    BATTERY_SAVER,
    DND_ACCESS,
    WRITE_SETTINGS,
    BACKGROUND_MANAGER,
    APP_DETAILS,
}

/**
 * Construye, para cada [SettingsTarget], una lista ordenada de Intents
 * candidatos según el fabricante. Solo se usan Intents (mecanismo público):
 * acciones documentadas de [Settings] y componentes de ajustes del fabricante.
 * Se intenta cada candidato en orden y se usa el primero que el sistema acepte,
 * terminando siempre en una acción pública genérica.
 */
class OemSettingsNavigator(private val device: DeviceProfile) {

    fun candidates(context: Context, target: SettingsTarget, packageName: String? = null): List<Intent> {
        val pkg = packageName ?: context.packageName
        val list = mutableListOf<Intent>()
        when (target) {
            SettingsTarget.WIFI_HOTSPOT -> {
                // Pantalla directa de la zona WiFi (AOSP 9+, Pixel, Motorola, Samsung, ColorOS).
                list += component(SETTINGS_PKG, "$SETTINGS_PKG.Settings\$WifiTetherSettingsActivity")
                if (device.manufacturer == Manufacturer.XIAOMI) {
                    list += component(SETTINGS_PKG, "$SETTINGS_PKG.Settings\$WifiApSettingsActivity")
                }
                list += tetherSettingsIntents()
            }
            SettingsTarget.USB_TETHERING -> list += tetherSettingsIntents()
            SettingsTarget.BLUETOOTH -> list += Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            SettingsTarget.BATTERY_SAVER -> {
                list += Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                when (device.manufacturer) {
                    Manufacturer.SAMSUNG -> {
                        list += component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
                        list += component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")
                    }
                    Manufacturer.XIAOMI ->
                        list += component("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")
                    Manufacturer.OPPO, Manufacturer.REALME, Manufacturer.ONEPLUS ->
                        list += component("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerControlActivity")
                    Manufacturer.VIVO ->
                        list += component("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity")
                    else -> Unit
                }
                list += Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
            }
            SettingsTarget.DND_ACCESS -> list += Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            SettingsTarget.WRITE_SETTINGS -> {
                list += Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$pkg"))
                list += Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            }
            SettingsTarget.BACKGROUND_MANAGER -> {
                when (device.manufacturer) {
                    Manufacturer.SAMSUNG ->
                        list += component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")
                    Manufacturer.XIAOMI ->
                        list += component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                    Manufacturer.OPPO, Manufacturer.REALME -> {
                        list += component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
                        list += component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                    }
                    Manufacturer.ONEPLUS -> {
                        list += component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
                        list += component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
                    }
                    Manufacturer.VIVO -> {
                        list += component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
                        list += component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
                    }
                    else -> Unit
                }
                list += Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            }
            SettingsTarget.APP_DETAILS ->
                list += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        }
        // Último recurso: la pantalla principal de Ajustes siempre existe.
        list += Intent(Settings.ACTION_SETTINGS)
        return list
    }

    private fun tetherSettingsIntents(): List<Intent> = listOf(
        // Acción expuesta por el Settings de AOSP y la mayoría de ROMs.
        Intent("android.settings.TETHER_SETTINGS"),
        component(SETTINGS_PKG, "$SETTINGS_PKG.Settings\$TetherSettingsActivity"),
        component(SETTINGS_PKG, "$SETTINGS_PKG.TetherSettings"),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
    )

    private fun component(pkg: String, cls: String) =
        Intent().setComponent(ComponentName(pkg, cls))

    /**
     * Lanza el primer candidato que funcione. No consulta el PackageManager
     * (evita depender de la visibilidad de paquetes de Android 11+): el propio
     * startActivity indica si el destino existe y es accesible.
     */
    fun launch(activity: Activity, target: SettingsTarget, packageName: String? = null): Boolean {
        for (intent in candidates(activity, target, packageName)) {
            try {
                activity.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                Log.d(TAG, "No disponible: $intent")
            } catch (e: SecurityException) {
                Log.d(TAG, "No exportado: $intent")
            }
        }
        return false
    }

    private companion object {
        const val TAG = "OemSettings"
        const val SETTINGS_PKG = "com.android.settings"
    }
}
