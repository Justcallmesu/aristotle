package io.github.justcallmesu.aristotle.data

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.SocketFactory

/** How a connection to one PC endpoint leaves the phone. */
enum class Route { SystemDefault, ViaVpn, TailscaleOff }

object Routes {
    /**
     * LAN endpoints are pinned TLS and use the normal network. Plain HTTP to a tailnet address is safe
     * only inside WireGuard: 100.64.0.0/10 is also carrier and hotel CGNAT space (protocol §11.1), so
     * without a VPN network that routes the address the pairing code and the token would leave in clear
     * over Wi-Fi or cellular to whoever holds that address.
     */
    fun decide(network: Network, vpnRoutesAddress: Boolean): Route = when {
        network == Network.Lan -> Route.SystemDefault
        vpnRoutesAddress -> Route.ViaVpn
        else -> Route.TailscaleOff
    }
}

/**
 * Plain sockets that never consult the ProxySelector: `java.net.Socket()` asks it for a SOCKS proxy
 * (`socket://host:port`) on every connect. [attach] binds each socket to a network before it connects.
 */
class DirectSocketFactory(private val attach: (Socket) -> Unit = {}) : SocketFactory() {
    override fun createSocket(): Socket = Socket(Proxy.NO_PROXY).also(attach)

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

/** Finds the VPN network that routes an address (Android: [AndroidVpnRoutes]). */
fun interface VpnRoutes {
    /** A socket factory bound to the VPN network whose routes cover [address], or null when there is none. */
    fun socketFactoryFor(address: InetAddress): SocketFactory?
}

/** No VPN network routes the tailnet address; nothing was sent. */
class TailscaleOffException(host: String) : IOException("no VPN network routes $host: Tailscale is off")

/**
 * Socket factory for tailnet endpoints. Each socket is bound to the VPN network when it is created, so a
 * tailnet connection never opens on Wi-Fi or cellular. Checked per socket because Tailscale can be
 * turned off between requests.
 */
class TailnetSocketFactory(private val vpn: VpnRoutes, private val address: InetAddress) : SocketFactory() {

    private fun bound(): SocketFactory {
        val factory = vpn.socketFactoryFor(address)
        return when (Routes.decide(Network.Tailscale, factory != null)) {
            Route.ViaVpn -> checkNotNull(factory)
            Route.SystemDefault, Route.TailscaleOff -> throw TailscaleOffException(address.hostAddress.orEmpty())
        }
    }

    override fun createSocket(): Socket = bound().createSocket()

    override fun createSocket(host: String?, port: Int): Socket = bound().createSocket(host, port)

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        bound().createSocket(host, port, localHost, localPort)

    override fun createSocket(host: InetAddress?, port: Int): Socket = bound().createSocket(host, port)

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        bound().createSocket(address, port, localAddress, localPort)
}
