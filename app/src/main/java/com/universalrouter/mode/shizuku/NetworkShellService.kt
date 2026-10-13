package com.universalrouter.mode.shizuku

import android.os.IBinder
import android.telephony.TelephonyManager
import com.universalrouter.mode.INetworkShell
import kotlin.system.exitProcess

/**
 * Se ejecuta en un proceso aparte que Shizuku arranca con los permisos de
 * shell (los mismos que ADB, sin root). Ese UID tiene MODIFY_PHONE_STATE, el
 * permiso necesario para cambiar los tipos de red permitidos (5G/4G).
 *
 * Llama a ITelephony#getAllowedNetworkTypesForReason / setAllowedNetworkTypesForReason,
 * lo mismo que hace Ajustes › Conexiones › Redes móviles › Modo de red.
 */
class NetworkShellService : INetworkShell.Stub() {

    private val telephony: Any by lazy {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "phone") as IBinder
        Class.forName("com.android.internal.telephony.ITelephony\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun getAllowedNetworkTypes(subId: Int): Long =
        call("getAllowedNetworkTypesForReason", subId, REASON_USER) as Long

    override fun setAllowedNetworkTypes(subId: Int, types: Long): Boolean =
        call("setAllowedNetworkTypesForReason", subId, REASON_USER, types) as? Boolean ?: true

    private fun call(name: String, vararg args: Any): Any? {
        val method = telephony.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == args.size
        } ?: throw UnsupportedOperationException("$name no existe en esta versión de Android")
        return try {
            method.invoke(telephony, *args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // Propaga el error real (p. ej. SecurityException) con un mensaje legible.
            throw IllegalStateException(e.targetException?.toString() ?: e.toString())
        }
    }

    private companion object {
        const val REASON_USER = TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER
    }
}
