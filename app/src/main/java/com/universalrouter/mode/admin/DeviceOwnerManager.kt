package com.universalrouter.mode.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.UserManager
import android.provider.Settings
import android.util.Log

/** Nivel de privilegios detectado automáticamente. */
enum class PermissionLevel { STANDARD, DEVICE_ADMIN, DEVICE_OWNER }

/**
 * Funciones exclusivas de Device Owner mediante [DevicePolicyManager].
 * Todas las llamadas comprueban [isDeviceOwner] y nunca lanzan excepciones
 * hacia la UI: devuelven false si la política no se pudo aplicar.
 */
class DeviceOwnerManager(private val context: Context) {

    private val dpm = requireNotNull(context.getSystemService(DevicePolicyManager::class.java))
    private val admin: ComponentName = RouterDeviceAdminReceiver.component(context)

    val isDeviceOwner: Boolean get() = dpm.isDeviceOwnerApp(context.packageName)

    val level: PermissionLevel
        get() = when {
            isDeviceOwner -> PermissionLevel.DEVICE_OWNER
            dpm.isAdminActive(admin) -> PermissionLevel.DEVICE_ADMIN
            else -> PermissionLevel.STANDARD
        }

    // ---------- Kiosco ----------

    /** Autoriza el modo kiosco (lock task) para esta app y la fija como launcher. */
    fun prepareKiosk(): Boolean = guard("prepareKiosk") {
        dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
        // Solo información del sistema y menú de apagado: sin notificaciones,
        // sin inicio, sin recientes.
        dpm.setLockTaskFeatures(
            admin,
            DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS,
        )
        setAsHome(true)
    }

    fun releaseKiosk(): Boolean = guard("releaseKiosk") {
        setAsHome(false)
        dpm.setLockTaskPackages(admin, emptyArray())
    }

    fun isKioskPermitted(): Boolean = dpm.isLockTaskPermitted(context.packageName)

    private fun setAsHome(enable: Boolean) {
        val alias = ComponentName(context.packageName, "${context.packageName}.KioskHomeAlias")
        context.packageManager.setComponentEnabledSetting(
            alias,
            if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        if (enable) {
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            // El componente preferido debe ser el que declara el filtro HOME (el alias).
            dpm.addPersistentPreferredActivity(admin, filter, alias)
        } else {
            dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
        }
    }

    // ---------- Aplicaciones ----------

    fun setAppsHidden(packages: Collection<String>, hidden: Boolean): Int = countOk(packages) {
        dpm.setApplicationHidden(admin, it, hidden)
    }

    /** Suspende (impide ejecutar) las apps. Devuelve cuántas se suspendieron. */
    fun setAppsSuspended(packages: Collection<String>, suspended: Boolean): Int {
        if (!isDeviceOwner || packages.isEmpty()) return 0
        return try {
            val failed = dpm.setPackagesSuspended(admin, packages.toTypedArray(), suspended)
            packages.size - failed.size
        } catch (e: Exception) {
            Log.w(TAG, "setPackagesSuspended", e)
            0
        }
    }

    // ---------- Restricciones ----------

    fun setRestrictions(restrictions: Collection<String>, enabled: Boolean): Boolean =
        guard("restrictions") {
            restrictions.forEach {
                if (enabled) dpm.addUserRestriction(admin, it) else dpm.clearUserRestriction(admin, it)
            }
        }

    // ---------- Energía ----------

    /** Device Owner puede escribir brillo y tiempo de pantalla sin WRITE_SETTINGS. */
    fun setBrightness(value: Int): Boolean = guard("brightness") {
        dpm.setSystemSetting(
            admin, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL.toString(),
        )
        dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS, value.toString())
    }

    fun setBrightnessMode(mode: Int): Boolean = guard("brightnessMode") {
        dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS_MODE, mode.toString())
    }

    fun setScreenOffTimeout(millis: Int): Boolean = guard("screenOffTimeout") {
        dpm.setSystemSetting(admin, Settings.System.SCREEN_OFF_TIMEOUT, millis.toString())
    }

    /** 0 = la pantalla se apaga aunque el teléfono esté cargando. */
    fun setStayOnWhilePluggedIn(value: Int): Boolean = guard("stayOn") {
        dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, value.toString())
    }

    fun stayOnWhilePluggedIn(): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0)

    /** Quita los privilegios de Device Owner (la app vuelve a modo estándar). */
    @Suppress("DEPRECATION")
    fun clearDeviceOwner(): Boolean = guard("clearDeviceOwner") {
        dpm.clearDeviceOwnerApp(context.packageName)
    }

    private inline fun guard(name: String, block: () -> Unit): Boolean {
        if (!isDeviceOwner) return false
        return try {
            block()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Política no aplicada: $name", e)
            false
        }
    }

    private inline fun countOk(packages: Collection<String>, op: (String) -> Boolean): Int {
        if (!isDeviceOwner) return 0
        return packages.count {
            try {
                op(it)
            } catch (e: Exception) {
                Log.w(TAG, "Paquete $it", e)
                false
            }
        }
    }

    companion object {
        private const val TAG = "DeviceOwner"

        /** Bloqueo de configuraciones no necesarias para compartir internet. */
        val SETTINGS_RESTRICTIONS = listOf(
            UserManager.DISALLOW_CONFIG_BLUETOOTH,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
            UserManager.DISALLOW_CONFIG_LOCATION,
            UserManager.DISALLOW_MODIFY_ACCOUNTS,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_CONFIG_CREDENTIALS,
            UserManager.DISALLOW_SAFE_BOOT,
        )

        /** Restricción de instalación de aplicaciones. */
        val INSTALL_RESTRICTIONS = listOf(
            UserManager.DISALLOW_INSTALL_APPS,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
        )

        /** Apaga Bluetooth y lo mantiene apagado (ahorro de energía). */
        val POWER_RESTRICTIONS = listOf(UserManager.DISALLOW_BLUETOOTH)
    }
}
