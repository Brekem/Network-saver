package com.universalrouter.mode.reverse

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetAddress
import kotlin.coroutines.resume

data class GroupInfo(val networkName: String, val passphrase: String, val ownerAddress: InetAddress)

/**
 * Red WiFi Direct propia (API pública WifiP2pManager). El PC se conecta a
 * ella como a cualquier WiFi. No es la Zona WiFi del sistema: no hay
 * reenvío de paquetes, solo el proxy de la app da internet.
 */
@SuppressLint("MissingPermission") // Los permisos se piden en la Activity antes de iniciar.
class WifiDirectGroup(context: Context) {

    private val manager = requireNotNull(context.getSystemService(WifiP2pManager::class.java))
    private val channel: WifiP2pManager.Channel = manager.initialize(context, Looper.getMainLooper(), null)

    suspend fun create(networkName: String, passphrase: String): GroupInfo {
        // Si quedó un grupo anterior (de otra sesión o de otra app), se elimina.
        if (groupInfo() != null) {
            removeGroup()
            delay(1_000)
        }
        val bands = listOf(
            WifiP2pConfig.GROUP_OWNER_BAND_5GHZ, // menos interferencias y lag
            WifiP2pConfig.GROUP_OWNER_BAND_AUTO,
        )
        var lastError = 0
        for (band in bands) {
            val config = WifiP2pConfig.Builder()
                .setNetworkName(networkName)
                .setPassphrase(passphrase)
                .setGroupOperatingBand(band)
                .build()
            lastError = action { manager.createGroup(channel, config, it) }
            if (lastError == OK) break
        }
        if (lastError != OK) throw IllegalStateException("No se pudo crear la red WiFi Direct (${reason(lastError)})")
        repeat(20) {
            val group = groupInfo()
            val owner = connectionInfo()?.groupOwnerAddress
            if (group != null && owner != null) {
                return GroupInfo(group.networkName, group.passphrase ?: passphrase, owner)
            }
            delay(500)
        }
        throw IllegalStateException("La red WiFi Direct no obtuvo dirección IP")
    }

    suspend fun isAlive(): Boolean = groupInfo() != null

    suspend fun clientCount(): Int = groupInfo()?.clientList?.size ?: 0

    suspend fun removeGroup() {
        action { manager.removeGroup(channel, it) }
    }

    private suspend fun groupInfo(): WifiP2pGroup? = suspendCancellableCoroutine { c ->
        manager.requestGroupInfo(channel) { c.resume(it) }
    }

    private suspend fun connectionInfo(): WifiP2pInfo? = suspendCancellableCoroutine { c ->
        manager.requestConnectionInfo(channel) { c.resume(it) }
    }

    private suspend fun action(block: (WifiP2pManager.ActionListener) -> Unit): Int =
        suspendCancellableCoroutine { c ->
            block(object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (c.isActive) c.resume(OK)
                }

                override fun onFailure(reason: Int) {
                    if (c.isActive) c.resume(reason)
                }
            })
        }

    private fun reason(code: Int) = when (code) {
        WifiP2pManager.P2P_UNSUPPORTED -> "WiFi Direct no soportado"
        WifiP2pManager.BUSY -> "WiFi ocupado: apaga la Zona WiFi del sistema"
        WifiP2pManager.ERROR -> "error interno, ¿el WiFi está encendido?"
        else -> "código $code"
    }

    private companion object {
        const val OK = -1
    }
}
