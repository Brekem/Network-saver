package com.universalrouter.mode.shizuku

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyManager
import android.util.Log
import com.universalrouter.mode.INetworkShell
import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlin.system.exitProcess

/**
 * Se ejecuta en un proceso aparte que Shizuku arranca con los permisos de
 * shell (los mismos que ADB, sin root). Ese UID tiene MODIFY_PHONE_STATE y
 * READ_PRIVILEGED_PHONE_STATE, necesarios para cambiar los tipos de red
 * permitidos (5G/4G), lo mismo que Ajustes › Conexiones › Redes móviles › Modo de red.
 *
 * Vía principal: API pública TelephonyManager#setAllowedNetworkTypesForReason
 * (Android 12+), con el Context que Shizuku pasa al constructor.
 * Respaldo: ITelephony por reflexión, desbloqueando las APIs ocultas (en
 * Android 14+ la reflexión sin desbloquear no ve los métodos y fallaba con
 * "no existe en esta versión de Android").
 */
class NetworkShellService(private val context: Context?) : INetworkShell.Stub() {

    /** Shizuku < 13 usa el constructor sin argumentos. */
    constructor() : this(null)

    init {
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
            .onFailure { Log.w(TAG, "HiddenApiBypass no disponible", it) }
        initTelephonyFramework()
    }

    /**
     * Un proceso app_process (como el de Shizuku) no pasa por la inicialización
     * de SystemServiceRegistry: sin esto TelephonyManager lanza NullPointerException
     * en getTelephonyServiceRegisterer().
     */
    @SuppressLint("BlockedPrivateApi", "DiscouragedPrivateApi", "PrivateApi", "SoonBlockedPrivateApi")
    private fun initTelephonyFramework() {
        runCatching {
            val initializer = Class.forName("android.telephony.TelephonyFrameworkInitializer")
            val current = initializer.getDeclaredField("sTelephonyServiceManager")
                .apply { isAccessible = true }
                .get(null)
            if (current == null) {
                val tsmClass = Class.forName("android.os.TelephonyServiceManager")
                val tsm = tsmClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                initializer.getMethod("setTelephonyServiceManager", tsmClass).invoke(null, tsm)
            }
        }.onFailure { Log.w(TAG, "No se pudo inicializar telephony", it) }
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun getAllowedNetworkTypes(subId: Int): Long {
        publicApi(subId)?.let { tm ->
            runCatching { return tm.getAllowedNetworkTypesForReason(REASON_USER) }
                .onFailure { Log.w(TAG, "API pública get falló, uso reflexión", it) }
        }
        return call("getAllowedNetworkTypesForReason", subId, REASON_USER) as Long
    }

    override fun setAllowedNetworkTypes(subId: Int, types: Long): Boolean {
        publicApi(subId)?.let { tm ->
            runCatching {
                tm.setAllowedNetworkTypesForReason(REASON_USER, types)
                return true
            }.onFailure { Log.w(TAG, "API pública set falló, uso reflexión", it) }
        }
        return call("setAllowedNetworkTypesForReason", subId, REASON_USER, types) as? Boolean ?: true
    }

    private fun publicApi(subId: Int): TelephonyManager? = context
        ?.getSystemService(TelephonyManager::class.java)
        ?.createForSubscriptionId(subId)

    private val telephony: Any by lazy {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "phone") as IBinder
        Class.forName("com.android.internal.telephony.ITelephony\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
    }

    private fun call(name: String, vararg args: Any): Any? {
        val clazz = telephony.javaClass
        val candidates = clazz.methods.toList() +
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { HiddenApiBypass.getDeclaredMethods(clazz).filterIsInstance<java.lang.reflect.Method>() }
                    .getOrDefault(emptyList())
            } else emptyList()
        val method = candidates.firstOrNull { it.name == name && it.parameterTypes.size == args.size }
            ?: throw UnsupportedOperationException(
                "ITelephony.$name no accesible (Android ${Build.VERSION.RELEASE}); disponibles: " +
                    candidates.filter { it.name == name }.joinToString { m ->
                        m.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.simpleName }
                    }.ifEmpty { "ninguno" },
            )
        method.isAccessible = true
        return try {
            method.invoke(telephony, *args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // Propaga el error real (p. ej. SecurityException) con un mensaje legible.
            throw IllegalStateException(e.targetException?.toString() ?: e.toString())
        }
    }

    private companion object {
        const val TAG = "NetworkShell"
        const val REASON_USER = TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER
    }
}
