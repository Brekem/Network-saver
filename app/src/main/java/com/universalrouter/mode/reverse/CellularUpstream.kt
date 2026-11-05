package com.universalrouter.mode.reverse

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Sale a internet por una red concreta (los datos móviles). */
class NetworkUpstream(private val network: Network) : Upstream {
    override fun connectTcp(address: InetSocketAddress, timeoutMs: Int): Socket =
        network.socketFactory.createSocket().apply {
            tcpNoDelay = true
            connect(address, timeoutMs)
        }

    override fun udpSocket(): DatagramSocket = DatagramSocket().also { network.bindSocket(it) }

    override fun resolve(host: String): InetAddress = network.getByName(host)
}

/**
 * Mantiene pedida la red móvil y entrega siempre la actual. Las conexiones
 * del PC salen así por los datos del teléfono aunque haya otra red.
 */
class CellularNetworkTracker(private val cm: ConnectivityManager) {

    @Volatile
    var network: Network? = null
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) {
            network = n
        }

        override fun onLost(n: Network) {
            if (network == n) network = null
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.requestNetwork(request, callback)
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(callback) }
        network = null
    }

    fun upstream(): Upstream = network?.let { NetworkUpstream(it) } ?: DefaultUpstream
}
