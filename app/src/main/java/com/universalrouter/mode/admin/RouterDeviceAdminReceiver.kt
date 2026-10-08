package com.universalrouter.mode.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receptor de administración del dispositivo. Para habilitar el modo
 * Device Owner (dispositivo recién restablecido, sin cuentas):
 *
 *   adb shell dpm set-device-owner com.universalrouter.mode/.admin.RouterDeviceAdminReceiver
 */
class RouterDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Administrador habilitado")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "Administrador deshabilitado")
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        Log.i(TAG, "Kiosco activo: $pkg")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        Log.i(TAG, "Kiosco finalizado")
    }

    companion object {
        private const val TAG = "RouterAdmin"

        fun component(context: Context) =
            ComponentName(context.applicationContext, RouterDeviceAdminReceiver::class.java)
    }
}
