@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.babenko.rescueservice.llm

import android.content.Context
import android.util.Log
import com.babenko.rescueservice.R
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

// --- DATA MODELS FOR THE OLD (STATELESS) API ---
@Serializable
data class DeviceStatus(
    val is_airplane_mode_on: Boolean?,
    val internet_connection_status: String?,
    val ringer_mode: String?,
    val battery_level: Int?,
    val installed_apps: String? = null // <--- ДОБАВЛЕНО ЭТО ПОЛЕ
)

@Serializable
data class ChatRequest(
    val uid: String,
    val user_text: String,
    val max_tokens: Int = 1024,
    val screen_context: String? = null,
    val device_status: DeviceStatus? = null,
    val last_instruction_given: String? = null
)

// --- COMMON RESPONSE MODELS ---
@Serializable
data class Selector(
    val by: String,
    val value: String
)

@Serializable
data class Action(
    val type: String,
    val selector: Selector
)

@Serializable
data class ChatResponse(
    val request_id: String,
    val latency_ms: Int,
    val response: String,
    val reply_text: String? = null,
    val actions: List<Action>? = null
)

// --- NEW MODELS FOR STATEFUL API ---
@Serializable
data class LlmRequest(
    @SerialName("session_id") val sessionId: String,
    @SerialName("user_text") val userText: String,
    @SerialName("screen_context") val screenContext: String,
    val status: String? = null
)

object LlmClient {
    private const val TAG = "LlmClient"
    private const val SERVER_URL = "http://34.55.160.235/assist"

    private lateinit var context: Context

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }

    private fun dumpActions(actions: List<Action>?): String {
        if (actions == null) return "null"
        if (actions.isEmpty()) return "[]"
        val sb = StringBuilder()
        actions.forEachIndexed { idx, a ->
            val by = a.selector.by
            val v = a.selector.value
            val vShort = if (v.length > 60) (v.take(57) + "...") else v
            sb.append("#").append(idx)
                .append("{type=").append(a.type)
                .append(", by=").append(by)
                .append(", value=").append(vShort)
                .append("} ")
        }
        return sb.toString().trim()
    }

    // --- NEW FUNCTION FOR STATEFUL API ---
    suspend fun assist(
        sessionId: String,
        userText: String,
        screenContext: String?,
        status: String?
    ): ChatResponse {
        val requestBody = LlmRequest(
            sessionId = sessionId,
            userText = userText,
            screenContext = screenContext ?: "", // Backend expects a non-null string
            status = status
        )

        return try {
            Log.d(TAG, "Sending stateful request [sessionId=$sessionId]: '${userText.take(120)}'")
            Log.d(TAG, "Status JSON: $status")

            val response = client.post(SERVER_URL) {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
                timeout { requestTimeoutMillis = 30_000 }
            }

            if (response.status.isSuccess()) {
                val chatResponse = response.body<ChatResponse>()
                Log.d(TAG, "LLM meta: request_id=${chatResponse.request_id}, latency_ms=${chatResponse.latency_ms}")
                Log.d(TAG, "LLM actions count=${chatResponse.actions?.size ?: 0} :: ${dumpActions(chatResponse.actions)}")
                chatResponse
            } else {
                val errorBody = response.body<String>()
                Log.e(TAG, "Server returned an error: ${response.status.value}, Body: $errorBody")
                ChatResponse(
                    response = context.getString(R.string.server_error),
                    request_id = "error_${UUID.randomUUID()}",
                    latency_ms = 0
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting LLM server (sessionId=$sessionId): ${e.message}", e)
            ChatResponse(
                response = context.getString(R.string.no_connection_error),
                request_id = "error_${UUID.randomUUID()}",
                latency_ms = 0
            )
        }
    }

    /**
     * Sends the full context to the backend and returns a response from the model. (DEPRECATED)
     */
    suspend fun getResponse(
        prompt: String,
        screenContext: String?,
        deviceStatus: DeviceStatus?,
        lastInstruction: String?
    ): ChatResponse {
        val uid = UUID.randomUUID().toString()
        val requestBody = ChatRequest(
            uid = uid,
            user_text = prompt,
            screen_context = screenContext,
            device_status = deviceStatus,
            last_instruction_given = lastInstruction
        )

        return try {
            Log.d(
                TAG,
                "Sending request to server [uid=$uid, hasScreenCtx=${screenContext != null}, " +
                        "hasDeviceStatus=${deviceStatus != null}, lastInstruction=${!lastInstruction.isNullOrBlank()}]: '${prompt.take(120)}'"
            )

            val response = client.post(SERVER_URL) {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
                timeout { requestTimeoutMillis = 30_000 }
            }

            if (response.status.isSuccess()) {
                val chatResponse = response.body<ChatResponse>()
                Log.d(TAG, "Received successful response: '${chatResponse.response}'")
                val replyShort = (chatResponse.reply_text ?: "").let { t ->
                    val s = t.trim()
                    if (s.length <= 120) s else s.take(117) + "..."
                }
                Log.d(TAG, "LLM meta: request_id=${chatResponse.request_id}, latency_ms=${chatResponse.latency_ms}")
                Log.d(TAG, "LLM reply_text(len=${replyShort.length}): '$replyShort'")
                Log.d(
                    TAG,
                    "LLM actions count=${chatResponse.actions?.size ?: 0} :: ${dumpActions(chatResponse.actions)}"
                )
                chatResponse
            } else {
                val errorBody = response.body<String>()
                Log.e(TAG, "Server returned an error: ${response.status.value}, Body: $errorBody")
                ChatResponse(
                    response = context.getString(R.string.server_error),
                    request_id = "",
                    latency_ms = 0
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting LLM server (uid=$uid): ${e.message}", e)
            ChatResponse(
                response = context.getString(R.string.no_connection_error),
                request_id = "",
                latency_ms = 0
            )
        }
    }
}