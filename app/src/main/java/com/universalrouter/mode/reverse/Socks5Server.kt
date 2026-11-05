package com.universalrouter.mode.reverse

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Por dónde sale el tráfico hacia internet (en Android: la red móvil). */
interface Upstream {
    fun connectTcp(address: InetSocketAddress, timeoutMs: Int): Socket
    fun udpSocket(): DatagramSocket
    fun resolve(host: String): InetAddress
}

/** Red por defecto del sistema (se usa en tests y como respaldo). */
object DefaultUpstream : Upstream {
    override fun connectTcp(address: InetSocketAddress, timeoutMs: Int): Socket =
        Socket().apply {
            tcpNoDelay = true
            connect(address, timeoutMs)
        }

    override fun udpSocket(): DatagramSocket = DatagramSocket()
    override fun resolve(host: String): InetAddress = InetAddress.getByName(host)
}

class TrafficCounter {
    val uploadBytes = AtomicLong()
    val downloadBytes = AtomicLong()
    val activeTcp = AtomicInteger()
    val activeUdp = AtomicInteger()
    val clients: MutableSet<InetAddress> = ConcurrentHashMap.newKeySet()
}

/**
 * Servidor SOCKS5 (RFC 1928) con CONNECT (TCP) y UDP ASSOCIATE (UDP, juegos).
 *
 * Es el núcleo del tether invertido: las conexiones del PC se abren aquí,
 * desde la app, así que salen a internet como tráfico normal del teléfono
 * (TTL y APN del propio teléfono) y no como tráfico de hotspot.
 *
 * Usa sockets bloqueantes con un hilo por sentido: simple y con la menor
 * latencia posible para juegos.
 */
class Socks5Server(
    private val bindAddress: InetAddress,
    private val port: Int,
    private val upstream: () -> Upstream,
    val counter: TrafficCounter = TrafficCounter(),
) {
    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "socks5").apply { isDaemon = true }
    }
    @Volatile
    private var server: ServerSocket? = null
    private val open: MutableSet<AutoCloseableHolder> = ConcurrentHashMap.newKeySet()

    val localPort: Int get() = server?.localPort ?: port

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(bindAddress, port), 128)
        server = ss
        executor.execute {
            while (!ss.isClosed) {
                val client = try {
                    ss.accept()
                } catch (e: IOException) {
                    break
                }
                executor.execute { handle(client) }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        open.forEach { it.close() }
        open.clear()
        executor.shutdownNow()
    }

    // ------------------------------------------------------------------

    private fun handle(client: Socket) {
        val holder = AutoCloseableHolder(client).also { open += it }
        try {
            client.tcpNoDelay = true
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()

            // Saludo: VER NMETHODS METHODS...
            if (input.readUnsignedByte() != VERSION) return
            val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
            if (methods.none { it.toInt() == NO_AUTH }) {
                output.write(byteArrayOf(VERSION.toByte(), 0xFF.toByte()))
                return
            }
            output.write(byteArrayOf(VERSION.toByte(), NO_AUTH.toByte()))

            // Petición: VER CMD RSV ATYP DST.ADDR DST.PORT
            if (input.readUnsignedByte() != VERSION) return
            val cmd = input.readUnsignedByte()
            input.readUnsignedByte()
            val target = readAddress(input)

            counter.clients += client.inetAddress
            when (cmd) {
                CMD_CONNECT -> connect(client, input, output, target)
                CMD_UDP_ASSOCIATE -> udpAssociate(client, input, output, holder)
                else -> reply(output, REP_COMMAND_NOT_SUPPORTED)
            }
        } catch (e: IOException) {
            // Cliente desconectado o petición inválida.
        } finally {
            holder.close()
            open -= holder
        }
    }

    private fun connect(client: Socket, input: InputStream, output: OutputStream, target: Target) {
        val remote = try {
            val address = target.address ?: upstream().resolve(target.host!!)
            upstream().connectTcp(InetSocketAddress(address, target.port), CONNECT_TIMEOUT_MS)
        } catch (e: UnknownHostException) {
            reply(output, REP_HOST_UNREACHABLE); return
        } catch (e: NoRouteToHostException) {
            reply(output, REP_NETWORK_UNREACHABLE); return
        } catch (e: ConnectException) {
            reply(output, REP_CONNECTION_REFUSED); return
        } catch (e: IOException) {
            reply(output, REP_GENERAL_FAILURE); return
        }
        counter.activeTcp.incrementAndGet()
        try {
            remote.tcpNoDelay = true
            reply(output, REP_SUCCEEDED)
            client.soTimeout = 0
            val down = executor.submit {
                pump(remote.getInputStream(), output, counter.downloadBytes)
                runCatching { client.shutdownOutput() }
            }
            pump(input, remote.getOutputStream(), counter.uploadBytes)
            runCatching { remote.shutdownOutput() }
            runCatching { down.get() }
        } finally {
            counter.activeTcp.decrementAndGet()
            runCatching { remote.close() }
        }
    }

    private fun udpAssociate(client: Socket, input: InputStream, output: OutputStream, holder: AutoCloseableHolder) {
        // Socket hacia el PC (misma IP local que la conexión de control) y socket hacia internet.
        val relay = DatagramSocket(InetSocketAddress(client.localAddress, 0))
        val out = try {
            upstream().udpSocket()
        } catch (e: IOException) {
            relay.close()
            reply(output, REP_GENERAL_FAILURE); return
        }
        holder.extra += relay
        holder.extra += out
        counter.activeUdp.incrementAndGet()
        try {
            reply(output, REP_SUCCEEDED, relay.localAddress, relay.localPort)
            client.soTimeout = 0
            val clientEndpoint = AtomicReference<SocketAddress?>()
            val dnsCache = ConcurrentHashMap<String, InetAddress>()

            // PC -> internet
            executor.execute {
                val buf = ByteArray(UDP_BUFFER)
                val packet = DatagramPacket(buf, buf.size)
                while (!relay.isClosed) {
                    try {
                        packet.setData(buf)
                        relay.receive(packet)
                        if (packet.address != client.inetAddress) continue
                        clientEndpoint.set(packet.socketAddress)
                        val parsed = parseUdpHeader(buf, packet.length) ?: continue
                        val dest = parsed.target.address
                            ?: dnsCache.getOrPut(parsed.target.host!!) { upstream().resolve(parsed.target.host) }
                        val len = packet.length - parsed.headerLength
                        out.send(DatagramPacket(buf, parsed.headerLength, len, dest, parsed.target.port))
                        counter.uploadBytes.addAndGet(len.toLong())
                    } catch (e: IOException) {
                        if (relay.isClosed) break
                    }
                }
            }
            // internet -> PC
            executor.execute {
                val buf = ByteArray(UDP_BUFFER)
                val packet = DatagramPacket(buf, buf.size)
                while (!out.isClosed) {
                    try {
                        packet.setData(buf)
                        out.receive(packet)
                        val endpoint = clientEndpoint.get() ?: continue
                        val header = udpHeader(packet.address, packet.port)
                        val data = ByteArray(header.size + packet.length)
                        System.arraycopy(header, 0, data, 0, header.size)
                        System.arraycopy(buf, 0, data, header.size, packet.length)
                        relay.send(DatagramPacket(data, data.size, endpoint))
                        counter.downloadBytes.addAndGet(packet.length.toLong())
                    } catch (e: IOException) {
                        if (out.isClosed) break
                    }
                }
            }
            // La asociación UDP vive mientras la conexión TCP de control siga abierta.
            val sink = ByteArray(64)
            while (input.read(sink) >= 0) Unit
        } finally {
            counter.activeUdp.decrementAndGet()
            relay.close()
            out.close()
        }
    }

    private fun pump(from: InputStream, to: OutputStream, bytes: AtomicLong) {
        val buf = ByteArray(TCP_BUFFER)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
                bytes.addAndGet(n.toLong())
            }
        } catch (e: IOException) {
            // Fin de la conexión.
        }
    }

    private fun reply(output: OutputStream, code: Int, address: InetAddress? = null, port: Int = 0) {
        val addr = address?.takeIf { it is Inet4Address || it is Inet6Address }
        val bytes = mutableListOf(VERSION, code, 0)
        if (addr is Inet6Address) {
            bytes += ATYP_IPV6
            addr.address.forEach { bytes += it.toInt() and 0xFF }
        } else {
            bytes += ATYP_IPV4
            (addr?.address ?: ByteArray(4)).forEach { bytes += it.toInt() and 0xFF }
        }
        bytes += (port shr 8) and 0xFF
        bytes += port and 0xFF
        output.write(ByteArray(bytes.size) { bytes[it].toByte() })
        output.flush()
    }

    // ------------------------------------------------------------------

    class Target(val address: InetAddress?, val host: String?, val port: Int)
    class UdpHeader(val target: Target, val headerLength: Int)

    private class AutoCloseableHolder(val socket: Socket) {
        val extra: MutableList<DatagramSocket> = java.util.concurrent.CopyOnWriteArrayList()
        fun close() {
            runCatching { socket.close() }
            extra.forEach { runCatching { it.close() } }
        }
    }

    companion object {
        const val VERSION = 5
        const val NO_AUTH = 0
        const val CMD_CONNECT = 1
        const val CMD_UDP_ASSOCIATE = 3
        const val ATYP_IPV4 = 1
        const val ATYP_DOMAIN = 3
        const val ATYP_IPV6 = 4
        const val REP_SUCCEEDED = 0
        const val REP_GENERAL_FAILURE = 1
        const val REP_NETWORK_UNREACHABLE = 3
        const val REP_HOST_UNREACHABLE = 4
        const val REP_CONNECTION_REFUSED = 5
        const val REP_COMMAND_NOT_SUPPORTED = 7

        private const val HANDSHAKE_TIMEOUT_MS = 15_000
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val TCP_BUFFER = 32 * 1024
        private const val UDP_BUFFER = 65_535

        fun readAddress(input: DataInputStream): Target {
            return when (input.readUnsignedByte()) {
                ATYP_IPV4 -> {
                    val a = ByteArray(4).also { input.readFully(it) }
                    Target(InetAddress.getByAddress(a), null, input.readUnsignedShort())
                }
                ATYP_IPV6 -> {
                    val a = ByteArray(16).also { input.readFully(it) }
                    Target(InetAddress.getByAddress(a), null, input.readUnsignedShort())
                }
                ATYP_DOMAIN -> {
                    val name = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                    Target(null, String(name, Charsets.US_ASCII), input.readUnsignedShort())
                }
                else -> throw EOFException("ATYP no soportado")
            }
        }

        /** Cabecera UDP SOCKS5: RSV(2) FRAG(1) ATYP DST.ADDR DST.PORT. Null si es inválida o fragmentada. */
        fun parseUdpHeader(buf: ByteArray, length: Int): UdpHeader? {
            if (length < 4 || buf[2].toInt() != 0) return null
            var i = 4
            val target = when (buf[3].toInt()) {
                ATYP_IPV4 -> {
                    if (length < i + 6) return null
                    val a = buf.copyOfRange(i, i + 4); i += 4
                    Target(InetAddress.getByAddress(a), null, 0)
                }
                ATYP_IPV6 -> {
                    if (length < i + 18) return null
                    val a = buf.copyOfRange(i, i + 16); i += 16
                    Target(InetAddress.getByAddress(a), null, 0)
                }
                ATYP_DOMAIN -> {
                    val n = buf[i].toInt() and 0xFF; i++
                    if (length < i + n + 2) return null
                    val host = String(buf, i, n, Charsets.US_ASCII); i += n
                    Target(null, host, 0)
                }
                else -> return null
            }
            val port = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            return UdpHeader(Target(target.address, target.host, port), i + 2)
        }

        fun udpHeader(address: InetAddress, port: Int): ByteArray {
            val a = address.address
            val atyp = if (a.size == 16) ATYP_IPV6 else ATYP_IPV4
            return byteArrayOf(0, 0, 0, atyp.toByte()) + a +
                byteArrayOf(((port shr 8) and 0xFF).toByte(), (port and 0xFF).toByte())
        }
    }
}
