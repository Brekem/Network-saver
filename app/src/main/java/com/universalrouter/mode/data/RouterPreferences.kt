package com.universalrouter.mode.data

import android.content.Context
import androidx.core.content.edit

/** Estado del dispositivo antes de activar el Modo Router (para restaurarlo). */
data class PreviousState(
    val brightness: Int,
    val brightnessMode: Int,
    val bluetoothEnabled: Boolean,
    val dndFilter: Int,
    val screenOffTimeout: Int,
    val stayOnWhilePluggedIn: Int,
)

/** Opciones que solo se aplican cuando la app es Device Owner. */
data class DeviceOwnerOptions(
    val kiosk: Boolean = true,
    val hideApps: Boolean = false,
    val suspendApps: Boolean = true,
    val blockSettings: Boolean = true,
    val blockInstall: Boolean = true,
    val powerPolicies: Boolean = true,
)

class RouterPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("router_mode", Context.MODE_PRIVATE)

    var routerActive: Boolean
        get() = prefs.getBoolean(KEY_ACTIVE, false)
        set(value) = prefs.edit { putBoolean(KEY_ACTIVE, value) }

    var selectedPackages: Set<String>
        get() = prefs.getStringSet(KEY_SELECTED, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit { putStringSet(KEY_SELECTED, value) }

    /** Paquetes realmente suspendidos/ocultos, para deshacer exactamente lo aplicado. */
    var suspendedPackages: Set<String>
        get() = prefs.getStringSet(KEY_SUSPENDED, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit { putStringSet(KEY_SUSPENDED, value) }

    var hiddenPackages: Set<String>
        get() = prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit { putStringSet(KEY_HIDDEN, value) }

    var previousState: PreviousState?
        get() {
            if (!prefs.getBoolean(KEY_HAS_SNAPSHOT, false)) return null
            return PreviousState(
                brightness = prefs.getInt(KEY_BRIGHTNESS, 128),
                brightnessMode = prefs.getInt(KEY_BRIGHTNESS_MODE, 0),
                bluetoothEnabled = prefs.getBoolean(KEY_BT, false),
                dndFilter = prefs.getInt(KEY_DND, 1),
                screenOffTimeout = prefs.getInt(KEY_TIMEOUT, 30_000),
                stayOnWhilePluggedIn = prefs.getInt(KEY_STAY_ON, 0),
            )
        }
        set(value) = prefs.edit {
            if (value == null) {
                putBoolean(KEY_HAS_SNAPSHOT, false)
            } else {
                putBoolean(KEY_HAS_SNAPSHOT, true)
                putInt(KEY_BRIGHTNESS, value.brightness)
                putInt(KEY_BRIGHTNESS_MODE, value.brightnessMode)
                putBoolean(KEY_BT, value.bluetoothEnabled)
                putInt(KEY_DND, value.dndFilter)
                putInt(KEY_TIMEOUT, value.screenOffTimeout)
                putInt(KEY_STAY_ON, value.stayOnWhilePluggedIn)
            }
        }

    var deviceOwnerOptions: DeviceOwnerOptions
        get() {
            val d = DeviceOwnerOptions()
            return DeviceOwnerOptions(
                kiosk = prefs.getBoolean(KEY_DO_KIOSK, d.kiosk),
                hideApps = prefs.getBoolean(KEY_DO_HIDE, d.hideApps),
                suspendApps = prefs.getBoolean(KEY_DO_SUSPEND, d.suspendApps),
                blockSettings = prefs.getBoolean(KEY_DO_SETTINGS, d.blockSettings),
                blockInstall = prefs.getBoolean(KEY_DO_INSTALL, d.blockInstall),
                powerPolicies = prefs.getBoolean(KEY_DO_POWER, d.powerPolicies),
            )
        }
        set(value) = prefs.edit {
            putBoolean(KEY_DO_KIOSK, value.kiosk)
            putBoolean(KEY_DO_HIDE, value.hideApps)
            putBoolean(KEY_DO_SUSPEND, value.suspendApps)
            putBoolean(KEY_DO_SETTINGS, value.blockSettings)
            putBoolean(KEY_DO_INSTALL, value.blockInstall)
            putBoolean(KEY_DO_POWER, value.powerPolicies)
        }

    private companion object {
        const val KEY_ACTIVE = "router_active"
        const val KEY_SELECTED = "selected_packages"
        const val KEY_SUSPENDED = "suspended_packages"
        const val KEY_HIDDEN = "hidden_packages"
        const val KEY_HAS_SNAPSHOT = "has_snapshot"
        const val KEY_BRIGHTNESS = "prev_brightness"
        const val KEY_BRIGHTNESS_MODE = "prev_brightness_mode"
        const val KEY_BT = "prev_bluetooth"
        const val KEY_DND = "prev_dnd"
        const val KEY_TIMEOUT = "prev_timeout"
        const val KEY_STAY_ON = "prev_stay_on"
        const val KEY_DO_KIOSK = "do_kiosk"
        const val KEY_DO_HIDE = "do_hide"
        const val KEY_DO_SUSPEND = "do_suspend"
        const val KEY_DO_SETTINGS = "do_settings"
        const val KEY_DO_INSTALL = "do_install"
        const val KEY_DO_POWER = "do_power"
    }
}
