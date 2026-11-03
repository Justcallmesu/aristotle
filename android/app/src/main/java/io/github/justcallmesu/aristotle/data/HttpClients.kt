package io.github.justcallmesu.aristotle.data

import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient

/** Why a request to the PC failed below HTTP. */
enum class ConnectionProblem { Unreachable, CertificateChanged }

fun connectionProblemOf(error: Throwable): ConnectionProblem = problemIn(error, depth = 0) ?: ConnectionProblem.Unreachable

/** Searches the cause chain and the suppressed exceptions (OkHttp attaches failed connect attempts there). */
private fun problemIn(error: Throwable, depth: Int): ConnectionProblem? {
    if (depth > MAX_CAUSE_DEPTH) return null
    if (error is PinMismatchException || error is SSLPeerUnverifiedException) return ConnectionProblem.CertificateChanged
    val related = listOfNotNull(error.cause?.takeIf { it !== error }) + error.suppressed
    return related.firstNotNullOfOrNull { problemIn(it, depth + 1) }
}

private const val MAX_CAUSE_DEPTH = 8

/**
 * Builds the OkHttp client for one paired PC (spec Q49): HTTPS on every address of the PC, LAN and Tailscale
 * alike, trusting exactly its pinned key. The connection specs hold TLS only, so OkHttp refuses an `http://`
 * or `ws://` URL before it opens a socket. No client uses a proxy: a system or Wi-Fi proxy would see the
 * Bearer token. Each client has its own SSLContext, so OkHttp never reuses a pooled connection across pins.
 *
 * D26: LAN and Tailscale clients get separate connection pools. OkHttp's `Address` does not compare socket
 * factories, so a shared pool could hand a connection made one way to a client that needs the other.
 * Connecting gives up after 3 s, so a dead address costs little before the next one is tried (T09).
 * No client pings (`pingInterval` 0): the server pings, and the link must answer, not judge (protocol §12).
 */
class HttpClients(
    private val sockets: SocketFactory = DirectSocketFactory(),
    private val base: OkHttpClient = defaultBase(),
) {
    private val pools = Network.entries.associateWith { ConnectionPool() }

    fun pinnedTo(pin: ByteArray, network: Network): OkHttpClient {
        val trustManager = PinnedTrustManager(pin)
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        return base.newBuilder()
            .connectionPool(pools.getValue(network))
            .proxy(Proxy.NO_PROXY)
            .socketFactory(sockets)
            .connectionSpecs(TLS_ONLY)
            .sslSocketFactory(context.socketFactory, trustManager)
            .hostnameVerifier(PinnedHostnameVerifier(pin))
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /** Drops pooled idle connections: after the phone's network changed they lead nowhere. */
    fun evictIdle() = pools.values.forEach(ConnectionPool::evictAll)

    private companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 3L
        private const val TIMEOUT_SECONDS = 10L
        private val TLS_ONLY = listOf(ConnectionSpec.MODERN_TLS)

        fun defaultBase(): OkHttpClient = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}
