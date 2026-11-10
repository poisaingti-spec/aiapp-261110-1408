package com.aiapp.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/** Uploads big files (up to 2 GB: long PDFs, audio, video) to Gemini's Files API, which keeps them 48 hours. */
object GeminiFiles {
    private const val UPLOAD = "https://generativelanguage.googleapis.com/upload/v1beta/files"
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    private val cache = mutableMapOf<String, Pair<String, Long>>()

    suspend fun uri(apiKey: String, a: Attachment): String {
        cache[a.path]?.let { (uri, at) -> if (System.currentTimeMillis() - at < 40 * 3_600_000L) return uri }
        val file = File(a.path)
        val (name, uri) = withContext(Dispatchers.IO) {
            val start = Request.Builder().url(UPLOAD)
                .header("x-goog-api-key", apiKey)
                .header("X-Goog-Upload-Protocol", "resumable")
                .header("X-Goog-Upload-Command", "start")
                .header("X-Goog-Upload-Header-Content-Length", file.length().toString())
                .header("X-Goog-Upload-Header-Content-Type", a.mime)
                .post(buildJsonObject { putJsonObject("file") { put("display_name", a.name.take(100)) } }.toString()
                    .toRequestBody("application/json".toMediaType()))
                .build()
            val uploadUrl = Http.client.newCall(start).execute().use { r ->
                if (!r.isSuccessful) throw ProviderException(r.code, "Gemini upload failed: " + Http.errorMessage(r.code, r.body?.string().orEmpty()))
                r.header("x-goog-upload-url") ?: throw ProviderException(500, "Gemini didn't accept the upload.")
            }
            val put = Request.Builder().url(uploadUrl)
                .header("X-Goog-Upload-Offset", "0")
                .header("X-Goog-Upload-Command", "upload, finalize")
                .post(file.asRequestBody(a.mime.toMediaType()))
                .build()
            Http.client.newCall(put).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ProviderException(r.code, "Gemini upload failed: " + Http.errorMessage(r.code, text))
                val f = Http.json.parseToJsonElement(text).jsonObject["file"] as? JsonObject
                    ?: throw ProviderException(500, "Gemini upload failed.")
                ((f["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()) to
                    ((f["uri"] as? JsonPrimitive)?.contentOrNull ?: throw ProviderException(500, "Gemini upload failed."))
            }
        }
        // Audio and video are processed after upload; wait until ready.
        for (i in 0 until 60) {
            val f = runCatching { Http.getJson("$BASE/$name", mapOf("x-goog-api-key" to apiKey)) }.getOrNull() ?: break
            val state = (f["state"] as? JsonPrimitive)?.contentOrNull
            if (state == null || state == "ACTIVE") break
            if (state == "FAILED") throw ProviderException(500, "Gemini couldn't process ${a.name}.")
            delay(3000)
        }
        cache[a.path] = uri to System.currentTimeMillis()
        return uri
    }
}
