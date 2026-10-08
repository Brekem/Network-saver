package com.universalrouter.mode.helpers

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

class BluetoothHelper(private val context: Context) {

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    val isAvailable: Boolean get() = adapter != null

    /** isEnabled no requiere BLUETOOTH_CONNECT. */
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Intenta apagar Bluetooth con la API oficial.
     * - Android 11-12: funciona (con BLUETOOTH_CONNECT en 12).
     * - Android 13+: el sistema lo ignora y devuelve false, salvo Device Owner.
     * Devuelve true si Bluetooth queda (o ya estaba) apagado.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun tryDisable(): Boolean {
        val a = adapter ?: return true
        if (!a.isEnabled) return true
        if (!hasConnectPermission()) return false
        return try {
            a.disable()
        } catch (e: SecurityException) {
            false
        }
    }

    /** Intento directo de encendido (Android 11-12 o Device Owner). */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun tryEnable(): Boolean {
        val a = adapter ?: return false
        if (a.isEnabled) return true
        if (!hasConnectPermission()) return false
        return try {
            a.enable()
        } catch (e: SecurityException) {
            false
        }
    }
}
