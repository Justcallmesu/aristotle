package io.github.justcallmesu.aristotle.data

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

sealed interface PairResult {
    data class Paired(val deviceId: String, val token: DeviceToken) : PairResult

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

sealed interface MeResult {
    data class Ok(val deviceId: String, val name: String) : MeResult
    data object Unauthorized : MeResult
    data object Forbidden : MeResult
    data class Rejected(val status: Int) : MeResult
    data class Failed(val problem: ConnectionProblem) : MeResult
}

@Serializable
private data class PairBody(@SerialName("device_id") val deviceId: String, val token: String)

@Serializable
private data class MeBody(@SerialName("device_id") val deviceId: String, val name: String = "")

/** `POST /v1/pair` with the client pinned to [pin]; a seam so the pair screen can be tested without a server. */
fun interface PairingApi {
    suspend fun pair(endpoint: ServerEndpoint, pin: ByteArray?, code: String, deviceName: String): PairResult
}

class HttpPairingApi(private val httpClients: HttpClients) : PairingApi {
    override suspend fun pair(endpoint: ServerEndpoint, pin: ByteArray?, code: String, deviceName: String): PairResult =
        ServerClient(httpClients.forEndpoint(endpoint, pin), endpoint.baseUrl).pair(code, deviceName)
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

    suspend fun me(token: DeviceToken): MeResult {
        val request = Request.Builder().url(base.resolve("/v1/me")!!).header("Authorization", "Bearer ${token.value}").build()
        return execute(request, MeResult::Failed) { response -> meResult(response) }
    }

    private fun pairResult(response: Response): PairResult = when (response.code) {
        201 -> decode(response, PairBody.serializer())?.let { PairResult.Paired(it.deviceId, DeviceToken(it.token)) }
            ?: PairResult.Rejected(response.code)
        401 -> PairResult.InvalidCode
        403 -> PairResult.Forbidden
        500 -> PairResult.ServerError
        else -> PairResult.Rejected(response.code)
    }

    private fun meResult(response: Response): MeResult = when (response.code) {
        200 -> decode(response, MeBody.serializer())?.let { MeResult.Ok(it.deviceId, it.name) } ?: MeResult.Rejected(200)
        401 -> MeResult.Unauthorized
        403 -> MeResult.Forbidden
        else -> MeResult.Rejected(response.code)
    }

    private fun <T> decode(response: Response, serializer: kotlinx.serialization.KSerializer<T>): T? =
        runCatching { HermesJson.decodeFromString(serializer, response.body.string()) }.getOrNull()

    private suspend fun <T> execute(request: Request, onFailure: (ConnectionProblem) -> T, handle: (Response) -> T): T =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use(handle)
            } catch (e: IOException) {
                onFailure(connectionProblemOf(e))
            }
        }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
