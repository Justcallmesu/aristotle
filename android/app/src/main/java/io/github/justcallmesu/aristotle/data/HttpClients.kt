package io.github.justcallmesu.aristotle.data

import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/** Thrown before any byte is sent when a plain-HTTP request targets anything but a tailnet IP. */
class CleartextRefusedException(host: String) : IOException("plain HTTP is only allowed to a Tailscale address, not $host")

/**
 * The network security config permits cleartext (it cannot express the tailnet CIDRs), so this
 * interceptor is the address rule: `http://` only to 100.64.0.0/10 or fd7a:115c:a1e0::/48.
 * [TailnetSocketFactory] adds the network rule: those connections only go over the VPN.
 */
object CleartextGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!url.isHttps && !ServerAddress.isTailnetHost(url.host)) throw CleartextRefusedException(url.host)
        return chain.proceed(chain.request())
    }
}

/** Why a request to the PC failed below HTTP. */
enum class ConnectionProblem { Unreachable, CertificateChanged, CleartextRefused, TailscaleOff }

fun connectionProblemOf(error: Throwable): ConnectionProblem = problemIn(error, depth = 0) ?: ConnectionProblem.Unreachable

/** Searches the cause chain and the suppressed exceptions (OkHttp attaches failed connect attempts there). */
private fun problemIn(error: Throwable, depth: Int): ConnectionProblem? {
    if (depth > MAX_CAUSE_DEPTH) return null
    when (error) {
        is PinMismatchException, is SSLPeerUnverifiedException -> return ConnectionProblem.CertificateChanged
        is CleartextRefusedException -> return ConnectionProblem.CleartextRefused
        is TailscaleOffException -> return ConnectionProblem.TailscaleOff
    }
    val related = listOfNotNull(error.cause?.takeIf { it !== error }) + error.suppressed
    return related.firstNotNullOfOrNull { problemIn(it, depth + 1) }
}

private const val MAX_CAUSE_DEPTH = 8

/**
 * Builds the OkHttp client for one PC endpoint: pinned TLS on the LAN, guarded cleartext over the VPN
 * on the tailnet. No client uses a proxy: a system or Wi-Fi proxy would see the Bearer token.
 */
class HttpClients(private val vpn: VpnRoutes, private val base: OkHttpClient = defaultBase()) {

    fun forEndpoint(endpoint: ServerEndpoint, pin: ByteArray?): OkHttpClient {
        val builder = base.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(CleartextGuard)
        when (endpoint.network) {
            Network.Lan -> {
                builder.socketFactory(DirectSocketFactory())
                pinTo(builder, requireNotNull(pin) { "a LAN endpoint needs a pinned fingerprint" })
            }
            Network.Tailscale -> builder.socketFactory(TailnetSocketFactory(vpn, endpoint.inetAddress))
        }
        return builder.build()
    }

    private fun pinTo(builder: OkHttpClient.Builder, pin: ByteArray) {
        val trustManager = PinnedTrustManager(pin)
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        builder.sslSocketFactory(context.socketFactory, trustManager)
        builder.hostnameVerifier(PinnedHostnameVerifier(pin))
    }

    private companion object {
        private const val TIMEOUT_SECONDS = 10L

        fun defaultBase(): OkHttpClient = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}
