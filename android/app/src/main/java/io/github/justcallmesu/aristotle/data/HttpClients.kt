package io.github.justcallmesu.aristotle.data

import java.io.IOException
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
 * interceptor is the real rule: `http://` only to 100.64.0.0/10 or fd7a:115c:a1e0::/48.
 */
object CleartextGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!url.isHttps && !ServerAddress.isTailnetHost(url.host)) throw CleartextRefusedException(url.host)
        return chain.proceed(chain.request())
    }
}

/** Why a request to the PC failed below HTTP. */
enum class ConnectionProblem { Unreachable, CertificateChanged, CleartextRefused }

fun connectionProblemOf(error: Throwable): ConnectionProblem {
    var cause: Throwable? = error
    while (cause != null) {
        when (cause) {
            is PinMismatchException, is SSLPeerUnverifiedException -> return ConnectionProblem.CertificateChanged
            is CleartextRefusedException -> return ConnectionProblem.CleartextRefused
        }
        cause = cause.cause
    }
    return ConnectionProblem.Unreachable
}

/** Builds the OkHttp client for one PC endpoint: pinned TLS on the LAN, guarded cleartext on the tailnet. */
class HttpClients(private val base: OkHttpClient = defaultBase()) {

    fun forEndpoint(endpoint: ServerEndpoint, pin: ByteArray?): OkHttpClient {
        val builder = base.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(CleartextGuard)
        if (endpoint.network == Network.Lan) {
            requireNotNull(pin) { "a LAN endpoint needs a pinned fingerprint" }
            pinTo(builder, pin)
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
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}
