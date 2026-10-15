package com.universalrouter.mode.shizuku

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.universalrouter.mode.INetworkShell
import kotlinx.coroutines.runBlocking

/**
 * Pasos del Modo Router aplicados con permisos de shell a través de Shizuku,
 * sin abrir pantallas de Ajustes: Bluetooth, ahorro de energía, No Molestar,
 * brillo, apps en segundo plano y zona WiFi / anclaje USB.
 *
 * Todas las llamadas son bloqueantes: usar fuera del hilo principal.
 */
class PrivilegedActions private constructor(private val shell: INetworkShell) {

    private fun exec(command: String): Pair<Boolean, String> {
        val raw = shell.exec(command)
        val ok = raw.startsWith("exit=0")
        val out = raw.substringAfter('\n', "").trim()
        if (!ok) Log.w(TAG, "$command → $raw")
        return ok to out
    }

    private fun run(command: String): Boolean = exec(command).first

    fun setBluetooth(enabled: Boolean): Boolean =
        run("cmd bluetooth_manager ${if (enabled) "enable" else "disable"}")

    /**
     * En Android 12 PowerManager ignora set-mode mientras el teléfono está
     * enchufado (el PC lo carga por USB): en ese caso se cambia directamente
     * Settings.Global.low_power, que el sistema sí aplica. Se verifica al final.
     */
    fun setPowerSave(enabled: Boolean): Boolean {
        val value = if (enabled) 1 else 0
        run("cmd power set-mode $value")
        if (lowPower() == value) return true
        run("settings put global low_power $value && settings put global low_power_sticky $value")
        Thread.sleep(500)
        return lowPower() == value
    }

    private fun lowPower(): Int? = exec("settings get global low_power").second.trim().toIntOrNull()

    /** [filter] = NotificationManager.INTERRUPTION_FILTER_*. */
    fun setDnd(filter: Int): Boolean {
        val arg = when (filter) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
            else -> "off"
        }
        return run("cmd notification set_dnd $arg")
    }

    fun setBrightness(value: Int, mode: Int): Boolean =
        run("settings put system screen_brightness_mode $mode && settings put system screen_brightness $value")

    /**
     * Prohíbe la actividad en segundo plano de [pkg] (como «Restringir» en
     * Ajustes › Batería) y cierra sus procesos. Devuelve true solo si estaba
     * permitida antes, para restaurar únicamente lo que cambió la app.
     */
    fun restrictBackground(pkg: String): Boolean {
        if (!PKG.matches(pkg)) return false
        val (_, before) = exec("cmd appops get $pkg RUN_ANY_IN_BACKGROUND")
        if (before.contains("ignore")) return false
        val ok = run("cmd appops set $pkg RUN_ANY_IN_BACKGROUND ignore")
        run("am set-standby-bucket $pkg restricted")
        run("am kill $pkg")
        return ok
    }

    fun allowBackground(pkg: String): Boolean {
        if (!PKG.matches(pkg)) return false
        val ok = run("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")
        run("am set-standby-bucket $pkg active")
        return ok
    }

    /** [type]: [TETHER_WIFI] o [TETHER_USB]. Devuelve null si fue bien, o el error. */
    fun setTethering(type: Int, enabled: Boolean): String? {
        val result = shell.setTethering(type, enabled)
        if (result != "OK") Log.w(TAG, "setTethering($type, $enabled) → $result")
        return if (result == "OK") null else result
    }

    companion object {
        private const val TAG = "PrivilegedActions"
        private val PKG = Regex("^[A-Za-z0-9_.]+$")

        const val TETHER_WIFI = 0
        const val TETHER_USB = 1

        /** Conecta con el servicio de Shizuku; null si Shizuku no está listo. */
        fun connect(context: Context): PrivilegedActions? {
            if (ShizukuManager.state(context) != ShizukuState.READY) return null
            return try {
                PrivilegedActions(runBlocking { ShizukuManager.shell(context) })
            } catch (e: Exception) {
                Log.w(TAG, "Shizuku no disponible", e)
                null
            }
        }
    }
}
