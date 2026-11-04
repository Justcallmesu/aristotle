package io.github.justcallmesu.aristotle.data

import io.github.justcallmesu.aristotle.data.terminal.TerminalAccess
import io.github.justcallmesu.aristotle.data.terminal.TerminalChannel
import io.github.justcallmesu.aristotle.data.terminal.TerminalDialer
import io.github.justcallmesu.aristotle.data.terminal.TerminalSocket
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

sealed interface PairResult {
    /** [addresses]: every listener the PC reported, already checked ([PcAddresses]); may be empty. */
    data class Paired(val deviceId: String, val token: DeviceToken, val addresses: List<ServerEndpoint>) : PairResult

    /** 401: wrong, expired, used or locked out; the server does not say which. */
    data object InvalidCode : PairResult

    /** 403: this phone's address is not allowed on that listener (wrong network). */
    data object Forbidden : PairResult

    /** 400 or an unexpected status/body. */
    data class Rejected(val status: Int) : PairResult

    /** 500: the PC could not store the device. */
    data object ServerError : PairResult
    data class Failed(val problem: ConnectionProblem) : PairResult
}

@Serializable
private data class PairBody(
    @SerialName("device_id") val deviceId: String,
    val token: String,
    val addresses: List<WireAddress> = emptyList(),
)

@Serializable
private data class AddressesBody(val addresses: List<WireAddress>)

@Serializable
private data class MeBody(val terminal: TerminalFlags? = null, val server: ServerBlock? = null)

@Serializable
private data class ServerBlock(
    val version: String? = null,
    val protocol: Int = 1,
    @SerialName("min_phone_protocol") val minPhoneProtocol: Int = 1,
)

/** `GET /healthz` (protocol §11.2); only `status` is read. */
@Serializable
private data class HealthBody(val status: String? = null)

@Serializable
private data class TerminalFlags(val enabled: Boolean = false, val granted: Boolean = false)

/** `POST /v1/pair` with the client pinned to [pin]; a seam so the pair screen can be tested without a server. */
fun interface PairingApi {
    suspend fun pair(endpoint: ServerEndpoint, pin: ByteArray, code: String, deviceName: String): PairResult
}

class HttpPairingApi(private val httpClients: HttpClients) : PairingApi {
    override suspend fun pair(endpoint: ServerEndpoint, pin: ByteArray, code: String, deviceName: String): PairResult =
        ServerClient(httpClients.pinnedTo(pin, endpoint.network), endpoint.baseUrl).pair(code, deviceName)
}

/** The link's calls over HTTPS with the PC's pin; one client per pin and address kind ([HttpClients]). */
class HttpLinkDialer(private val httpClients: HttpClients) : LinkDialer, TerminalDialer, PinnedHealth {
    private val clients = ConcurrentHashMap<Pair<String, Network>, OkHttpClient>()

    override fun openRelay(pc: PcCredentials, endpoint: ServerEndpoint): RelayChannel =
        RelaySocket(clientFor(pc, endpoint), endpoint.baseUrl, pc.token)

    override suspend fun addresses(pc: PcCredentials, endpoint: ServerEndpoint): List<ServerEndpoint>? =
        ServerClient(clientFor(pc, endpoint), endpoint.baseUrl).addresses(pc.token)

    override fun networkChanged() = httpClients.evictIdle()

    /**
     * The PC's pinned client (same trust manager, hostname check and pin) with [route]'s sockets (bound to the Wi-Fi
     * network, or plain under a VPN that routes none of its subnet, TL1e) and names resolved there (TL1c), and a pool of
     * its own that keeps nothing: a pooled relay connection
     * (over a VPN, say) is never reused for the check, nor the check's for the relay. No token is sent.
     */
    override suspend fun healthy(pc: PcCredentials, endpoint: ServerEndpoint, route: WifiRoute): Boolean {
        val bound = clientFor(pc, endpoint).newBuilder()
            .socketFactory(route.sockets)
            .dns(route.dns)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .build()
        return ServerClient(bound, endpoint.baseUrl).healthy()
    }

    override fun openTerminal(pc: PcCredentials, endpoint: ServerEndpoint): TerminalChannel =
        TerminalSocket(clientFor(pc, endpoint), endpoint.baseUrl, pc.token)

    override suspend fun terminalAllowed(pc: PcCredentials, endpoint: ServerEndpoint): Boolean? =
        ServerClient(clientFor(pc, endpoint), endpoint.baseUrl).terminalAllowed(pc.token, pc.pcId)

    private fun clientFor(pc: PcCredentials, endpoint: ServerEndpoint): OkHttpClient =
        clients.getOrPut(pc.fingerprint to endpoint.network) { httpClients.pinnedTo(pc.pin, endpoint.network) }
}

/** aristotle-server's REST API (docs/protocol.md §11.2). Nothing here logs; tokens and codes stay in memory. */
class ServerClient(private val client: OkHttpClient, baseUrl: String) {
    private val base: HttpUrl = baseUrl.toHttpUrl()

    suspend fun pair(code: String, deviceName: String): PairResult {
        val body = buildJsonObject {
            put("code", PairingCode.normalize(code))
            if (deviceName.isNotBlank()) put("device_name", deviceName.trim())
        }.toString().toRequestBody(JSON)
        val request = Request.Builder().url(base.resolve("/v1/pair")!!).post(body).build()
        return execute(request, PairResult::Failed) { response -> pairResult(response) }
    }

    /** `GET /v1/addresses`: the PC's listeners now, checked; null on any failure (the stored list stays). */
    suspend fun addresses(token: DeviceToken): List<ServerEndpoint>? {
        val request = Request.Builder().url(base.resolve("/v1/addresses")!!).header("Authorization", "Bearer ${token.value}").build()
        return execute(request, { null }) { response ->
            if (response.code != 200) null else decode(response, AddressesBody.serializer())?.let { PcAddresses.endpointsOf(it.addresses) }
        }
    }

    /**
     * `GET /healthz` (protocol §11.2) over this pinned client: true only for `200 {"status":"ok"}`. Another certificate
     * fails the handshake, so a true here is the paired PC itself (LAN discovery, Q65). Any failure is false.
     */
    suspend fun healthy(): Boolean {
        val request = Request.Builder().url(base.resolve("/healthz")!!).build()
        return execute(request, { false }) { response -> response.code == 200 && decode(response, HealthBody.serializer())?.status == "ok" }
    }

    /**
     * `GET /v1/me` (protocol §11.2, §14): whether this phone may open a terminal (switch on **and** granted); false
     * for a server without the `terminal` object; null on any failure. The `server` block is recorded under [pcId],
     * the PC the call was made to, even if the caller was cancelled meanwhile.
     */
    suspend fun terminalAllowed(token: DeviceToken, pcId: String): Boolean? {
        val request = Request.Builder().url(base.resolve("/v1/me")!!).header("Authorization", "Bearer ${token.value}").build()
        return execute(request, { null }) { response ->
            if (response.code != 200) null else decode(response, MeBody.serializer())?.let { me ->
                // A missing `server` block is an old server: version unknown, protocol 1 (Q63).
                ServerVersions.record(pcId, me.server?.let { ServerInfo(it.version, it.protocol, it.minPhoneProtocol) } ?: ServerInfo.OLD)
                me.terminal?.let { TerminalAccess.visible(it.enabled, it.granted) } ?: false }
        }
    }

    private fun pairResult(response: Response): PairResult = when (response.code) {
        201 -> decode(response, PairBody.serializer())
            ?.let { PairResult.Paired(it.deviceId, DeviceToken(it.token), PcAddresses.endpointsOf(it.addresses)) }
            ?: PairResult.Rejected(response.code)
        401 -> PairResult.InvalidCode
        403 -> PairResult.Forbidden
        500 -> PairResult.ServerError
        else -> PairResult.Rejected(response.code)
    }

    private fun <T> decode(response: Response, serializer: kotlinx.serialization.KSerializer<T>): T? =
        runCatching { HermesJson.decodeFromString(serializer, response.body.string()) }.getOrNull()

    /**
     * Runs the call on OkHttp's threads. Cancelling the caller cancels the call at once (T09 review 2): a
     * blocking `execute()` would hold the caller for up to the connect and read timeouts.
     */
    private suspend fun <T> execute(request: Request, onFailure: (ConnectionProblem) -> T, handle: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(onFailure(connectionProblemOf(e)))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        continuation.resume(response.use(handle))
                    } catch (e: IOException) {
                        continuation.resume(onFailure(connectionProblemOf(e)))
                    } catch (e: RuntimeException) {
                        continuation.resumeWithException(e)
                    }
                }
            })
        }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
