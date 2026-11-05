package com.universalrouter.mode.reverse

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class Socks5ServerTest {

    private val loopback = InetAddress.getLoopbackAddress()
    private lateinit var socks: Socks5Server

    @Before
    fun setUp() {
        socks = Socks5Server(loopback, 0, { DefaultUpstream })
        socks.start()
    }

    @After
    fun tearDown() = socks.stop()

    @Test
    fun `CONNECT reenvia TCP en ambos sentidos`() {
        val echo = ServerSocket(0, 10, loopback)
        thread(isDaemon = true) {
            echo.accept().use { s -> s.getInputStream().copyTo(s.getOutputStream()) }
        }
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(loopback, socks.localPort))
        Socket(proxy).use { s ->
            s.connect(InetSocketAddress(loopback, echo.localPort), 5_000)
            s.soTimeout = 5_000
            val msg = "hola desde el PC".toByteArray()
            s.getOutputStream().write(msg)
            val back = ByteArray(msg.size)
            DataInputStream(s.getInputStream()).readFully(back)
            assertArrayEquals(msg, back)
        }
        echo.close()
        Thread.sleep(200)
        assertEquals(16, socks.counter.uploadBytes.get())
        assertEquals(16, socks.counter.downloadBytes.get())
    }

    @Test
    fun `UDP ASSOCIATE reenvia datagramas (juegos)`() {
        val echo = DatagramSocket(0, loopback)
        thread(isDaemon = true) {
            val buf = ByteArray(1500)
            val p = DatagramPacket(buf, buf.size)
            echo.receive(p)
            echo.send(DatagramPacket(buf, p.length, p.socketAddress))
        }

        Socket(loopback, socks.localPort).use { control ->
            control.soTimeout = 5_000
            val out = control.getOutputStream()
            val input = DataInputStream(control.getInputStream())
            out.write(byteArrayOf(5, 1, 0))
            assertEquals(5, input.readUnsignedByte())
            assertEquals(0, input.readUnsignedByte())
            // UDP ASSOCIATE 0.0.0.0:0
            out.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
            assertEquals(5, input.readUnsignedByte())
            assertEquals(0, input.readUnsignedByte())
            input.readUnsignedByte()
            val relay = Socks5Server.readAddress(input)

            DatagramSocket(0, loopback).use { udp ->
                udp.soTimeout = 5_000
                val payload = "ping-juego".toByteArray()
                val packet = Socks5Server.udpHeader(loopback, echo.localPort) + payload
                udp.send(DatagramPacket(packet, packet.size, relay.address, relay.port))

                val buf = ByteArray(1500)
                val resp = DatagramPacket(buf, buf.size)
                udp.receive(resp)
                val header = Socks5Server.parseUdpHeader(buf, resp.length)!!
                assertEquals(echo.localPort, header.target.port)
                assertArrayEquals(payload, buf.copyOfRange(header.headerLength, resp.length))
            }
        }
        echo.close()
    }

    @Test
    fun `CONNECT con nombre de dominio`() {
        val echo = ServerSocket(0, 10, loopback)
        thread(isDaemon = true) { echo.accept().use { it.getOutputStream().write("ok".toByteArray()) } }
        Socket(loopback, socks.localPort).use { s ->
            s.soTimeout = 5_000
            val out = s.getOutputStream()
            val input = DataInputStream(s.getInputStream())
            out.write(byteArrayOf(5, 1, 0))
            input.readUnsignedShort()
            val host = "localhost".toByteArray()
            out.write(
                byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host +
                    byteArrayOf((echo.localPort shr 8).toByte(), echo.localPort.toByte()),
            )
            assertEquals(5, input.readUnsignedByte())
            assertEquals(0, input.readUnsignedByte())
            input.readUnsignedByte()
            Socks5Server.readAddress(input)
            val got = ByteArray(2).also { input.readFully(it) }
            assertEquals("ok", String(got))
        }
        echo.close()
    }
}
