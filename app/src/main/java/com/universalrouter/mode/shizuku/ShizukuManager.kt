package com.universalrouter.mode.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.universalrouter.mode.BuildConfig
import com.universalrouter.mode.INetworkShell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

enum class ShizukuState { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

/** Estado de Shizuku y conexión con [NetworkShellService]. */
object ShizukuManager {

    const val PACKAGE = "moe.shizuku.privileged.api"
    const val PERMISSION_REQUEST_CODE = 4242

    @Volatile
    private var shell: INetworkShell? = null
    private var pending: CompletableDeferred<INetworkShell>? = null

    fun state(context: Context): ShizukuState = when {
        !isInstalled(context) -> ShizukuState.NOT_INSTALLED
        !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> ShizukuState.NOT_RUNNING
        Shizuku.isPreV11() -> ShizukuState.NOT_RUNNING
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuState.NO_PERMISSION
        else -> ShizukuState.READY
    }

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
    }

    private fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun args(context: Context) = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, NetworkShellService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                val s = INetworkShell.Stub.asInterface(binder)
                shell = s
                pending?.complete(s)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shell = null
        }
    }

    /** Devuelve el servicio con permisos de shell, arrancándolo si hace falta. */
    suspend fun shell(context: Context): INetworkShell {
        shell?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        check(state(context) == ShizukuState.READY) { "Shizuku no está activo o sin permiso" }
        val deferred = CompletableDeferred<INetworkShell>()
        pending = deferred
        Shizuku.bindUserService(args(context), connection)
        return withTimeout(15_000) { deferred.await() }
    }

    fun unbind(context: Context) {
        runCatching { Shizuku.unbindUserService(args(context), connection, true) }
        shell = null
    }
}
