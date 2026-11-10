package com.aiapp.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val jsonType = "application/json".toMediaType()
    const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
        .addInterceptor { chain ->
            val orig = chain.request()
            val req = if (orig.header("User-Agent") == null) {
                orig.newBuilder().header("User-Agent", "AI-App-Android/1.0").build()
            } else {
                orig
            }
            chain.proceed(req)
        }
        .build()

    suspend fun getJson(url: String, headers: Map<String, String>): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ProviderException(resp.code, errorMessage(resp.code, body))
            json.parseToJsonElement(body).jsonObject
        }
    }

    /** POSTs [body] and emits the payload of every server-sent-event `data:` line. Cancelling the flow cancels the request. */
    fun sse(url: String, headers: Map<String, String>, body: String): Flow<String> = callbackFlow {
        val request = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .post(body.toRequestBody(jsonType))
            .build()
        val call = client.newCall(request)

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(if (call.isCanceled()) null else e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val text = resp.body?.string().orEmpty()
                        close(ProviderException(resp.code, errorMessage(resp.code, text)))
                        return
                    }
                    try {
                        val source = resp.body!!.source()
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data == "[DONE]") break
                            if (data.isNotEmpty()) trySendBlocking(data)
                        }
                        close()
                    } catch (e: IOException) {
                        close(if (call.isCanceled()) null else e)
                    }
                }
            }
        })

        awaitClose { call.cancel() }
    }

    fun errorMessage(code: Int, body: String): String {
        val message = runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            when (val err = root["error"]) {
                is JsonObject -> (err["message"] as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> err.contentOrNull
                else -> (root["message"] as? JsonPrimitive)?.contentOrNull
            }
        }.getOrNull()
        return message ?: "HTTP $code"
    }
}
