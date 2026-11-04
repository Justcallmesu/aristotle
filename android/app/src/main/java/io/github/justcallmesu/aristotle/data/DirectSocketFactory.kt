package io.github.justcallmesu.aristotle.data

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.SocketFactory

/**
 * Plain TCP sockets that never consult the ProxySelector: `java.net.Socket()` asks it for a SOCKS proxy
 * (`socket://host:port`) on every connect. TLS runs on top of them ([HttpClients]). [bind] runs on each new socket
 * before it connects (LAN discovery binds it to the Wi-Fi network, TL1c); when it throws, the socket is closed and
 * the error goes to the caller, so nothing connects unbound.
 */
class DirectSocketFactory(private val bind: (Socket) -> Unit = {}) : SocketFactory() {
    override fun createSocket(): Socket {
        val socket = Socket(Proxy.NO_PROXY)
        try {
            bind(socket)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
        return socket
    }

    override fun createSocket(host: String?, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }
}
