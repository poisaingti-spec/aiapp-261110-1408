package com.aiapp.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.aiapp.llm.Http
import com.aiapp.llm.ModelInfo
import com.aiapp.llm.ProviderException
import com.aiapp.llm.ProviderId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Base64

/** Image and video generation with your Gemini or OpenAI key. */
object MediaGen {
    private const val GEMINI = "https://generativelanguage.googleapis.com/v1beta"
    private const val OPENAI = "https://api.openai.com/v1"
    private val jsonType = "application/json".toMediaType()

    private suspend fun post(url: String, headers: Map<String, String>, body: String): JsonObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(body.toRequestBody(jsonType)).build()
        Http.client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ProviderException(r.code, Http.errorMessage(r.code, text))
            Http.json.parseToJsonElement(text).jsonObject
        }
    }

    private suspend fun bytes(url: String, headers: Map<String, String>): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ProviderException(r.code, "Download failed (HTTP ${r.code}).")
            r.body!!.bytes()
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    private suspend fun geminiModels(key: String): List<Triple<String, String, List<String>>> {
        val list = Http.getJson("$GEMINI/models?pageSize=500", mapOf("x-goog-api-key" to key))["models"] as? JsonArray
            ?: return emptyList()
        return list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("name")?.removePrefix("models/") ?: return@mapNotNull null
            val methods = (o["supportedGenerationMethods"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            Triple(id, o.str("displayName") ?: id, methods)
        }
    }

    suspend fun imageModels(provider: ProviderId, key: String): List<ModelInfo> = when (provider) {
        ProviderId.GEMINI -> geminiModels(key).filter { (id, _, m) ->
            (id.contains("image") && "generateContent" in m) || (id.contains("imagen") && "predict" in m)
        }.map { ModelInfo(it.first, it.second) }.sortedByDescending { it.id }
        ProviderId.OPENAI -> {
            val data = Http.getJson("$OPENAI/models", mapOf("Authorization" to "Bearer $key"))["data"] as? JsonArray
            data.orEmpty().mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }
                .filter { it.startsWith("gpt-image") || it.startsWith("dall-e") || it.startsWith("chatgpt-image") }
                .sortedDescending().map { ModelInfo(it, it) }
        }
        else -> emptyList()
    }

    suspend fun videoModels(geminiKey: String): List<ModelInfo> =
        geminiModels(geminiKey).filter { "predictLongRunning" in it.third }
            .map { ModelInfo(it.first, it.second) }.sortedByDescending { it.id }

    /** Returns the image bytes (PNG or JPEG). With [reference], edits or builds on that photo. */
    suspend fun image(
        provider: ProviderId,
        model: String,
        key: String,
        prompt: String,
        reference: com.aiapp.llm.Attachment? = null,
    ): ByteArray = when (provider) {
        ProviderId.OPENAI -> if (reference != null && reference.isImage) {
            withContext(Dispatchers.IO) {
                val file = File(reference.path)
                val form = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("model", if (model.startsWith("dall-e")) "gpt-image-1" else model)
                    .addFormDataPart("prompt", prompt)
                    .addFormDataPart("image[]", file.name, file.asRequestBodyCompat(reference.mime))
                    .build()
                val req = Request.Builder().url("$OPENAI/images/edits").header("Authorization", "Bearer $key").post(form).build()
                Http.client.newCall(req).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (!r.isSuccessful) throw ProviderException(r.code, Http.errorMessage(r.code, text))
                    val first = (Http.json.parseToJsonElement(text).jsonObject["data"] as? JsonArray)?.firstOrNull() as? JsonObject
                    first?.str("b64_json")?.let { Base64.getDecoder().decode(it) } ?: throw ProviderException(500, "No image returned.")
                }
            }
        } else {
            val body = buildJsonObject {
                put("model", model)
                put("prompt", prompt)
                put("n", 1)
                put("size", "1024x1024")
                if (model.startsWith("dall-e")) put("response_format", "b64_json")
            }.toString()
            val r = post("$OPENAI/images/generations", mapOf("Authorization" to "Bearer $key"), body)
            val first = (r["data"] as? JsonArray)?.firstOrNull() as? JsonObject
                ?: throw ProviderException(500, "No image returned.")
            first.str("b64_json")?.let { Base64.getDecoder().decode(it) }
                ?: first.str("url")?.let { bytes(it, emptyMap()) }
                ?: throw ProviderException(500, "No image returned.")
        }
        ProviderId.GEMINI -> if (model.contains("imagen") && reference == null) {
            val body = buildJsonObject {
                putJsonArray("instances") { addJsonObject { put("prompt", prompt) } }
                putJsonObject("parameters") { put("sampleCount", 1) }
            }.toString()
            val r = post("$GEMINI/models/$model:predict", mapOf("x-goog-api-key" to key), body)
            val p = (r["predictions"] as? JsonArray)?.firstOrNull() as? JsonObject
            p?.str("bytesBase64Encoded")?.let { Base64.getDecoder().decode(it) }
                ?: throw ProviderException(500, "No image returned (the prompt may have been blocked).")
        } else {
            val body = buildJsonObject {
                putJsonArray("contents") {
                    addJsonObject {
                        putJsonArray("parts") {
                            if (reference != null && reference.isImage) {
                                addJsonObject {
                                    putJsonObject("inlineData") {
                                        put("mimeType", reference.mime)
                                        put("data", Base64.getEncoder().encodeToString(File(reference.path).readBytes()))
                                    }
                                }
                            }
                            addJsonObject { put("text", prompt) }
                        }
                    }
                }
                putJsonObject("generationConfig") {
                    putJsonArray("responseModalities") { add(JsonPrimitive("TEXT")); add(JsonPrimitive("IMAGE")) }
                }
            }.toString()
            // Imagen can't take a photo, so use a Gemini image model for edits.
            val m = if (model.contains("imagen")) imageModels(ProviderId.GEMINI, key).firstOrNull { !it.id.contains("imagen") }?.id
                ?: throw ProviderException(400, "Your key has no Gemini model that can edit photos.") else model
            val r = post("$GEMINI/models/$m:generateContent", mapOf("x-goog-api-key" to key), body)
            val parts = ((r["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject)
                ?.let { (it["content"] as? JsonObject)?.get("parts") as? JsonArray }.orEmpty()
            parts.firstNotNullOfOrNull { p ->
                val inline = (p as? JsonObject)?.let { it["inlineData"] ?: it["inline_data"] } as? JsonObject
                inline?.str("data")
            }?.let { Base64.getDecoder().decode(it) }
                ?: throw ProviderException(500, "No image returned (the prompt may have been blocked).")
        }
        else -> throw ProviderException(400, "Pick Gemini or ChatGPT for images.")
    }

    /** Starts a video, polls until it's ready (a few minutes) and returns the MP4 bytes. */
    suspend fun video(
        model: String,
        key: String,
        prompt: String,
        aspect: String,
        reference: com.aiapp.llm.Attachment? = null,
        onStatus: (String) -> Unit,
    ): ByteArray {
        val headers = mapOf("x-goog-api-key" to key)
        val startImage = reference?.takeIf { it.isImage }
        val body = buildJsonObject {
            putJsonArray("instances") {
                addJsonObject {
                    put("prompt", prompt)
                    if (startImage != null) {
                        // Animate this photo: it becomes the first frame.
                        putJsonObject("image") {
                            put("bytesBase64Encoded", Base64.getEncoder().encodeToString(File(startImage.path).readBytes()))
                            put("mimeType", startImage.mime)
                        }
                    }
                }
            }
            putJsonObject("parameters") { put("aspectRatio", aspect) }
        }.toString()
        var op = try {
            post("$GEMINI/models/$model:predictLongRunning", headers, body)
        } catch (e: ProviderException) {
            if (e.code != 400) throw e
            // Some models don't take an aspect ratio.
            post("$GEMINI/models/$model:predictLongRunning", headers, buildJsonObject {
                putJsonArray("instances") { addJsonObject { put("prompt", prompt) } }
            }.toString())
        }
        val name = op.str("name") ?: throw ProviderException(500, "Video job didn't start.")
        val start = System.currentTimeMillis()
        while ((op["done"] as? JsonPrimitive)?.booleanOrNull != true) {
            val secs = (System.currentTimeMillis() - start) / 1000
            onStatus("Generating video… ${secs / 60}:${(secs % 60).toString().padStart(2, '0')}")
            if (secs > 15 * 60) throw ProviderException(504, "The video took too long. Try again.")
            delay(10_000)
            op = Http.getJson("$GEMINI/$name", headers)
        }
        (op["error"] as? JsonObject)?.let { throw ProviderException(500, it.str("message") ?: "Video failed.") }
        val resp = op["response"] as? JsonObject
        val sample = ((resp?.get("generateVideoResponse") as? JsonObject)?.get("generatedSamples") as? JsonArray)
            ?.firstOrNull() as? JsonObject
        val uri = (sample?.get("video") as? JsonObject)?.str("uri")
            ?: throw ProviderException(500, "No video returned (the prompt may have been blocked).")
        onStatus("Downloading video…")
        return bytes(uri, headers)
    }

    /** Saves to Pictures/AIApp or Movies/AIApp; returns a content Uri you can view or share. */
    suspend fun saveToGallery(context: Context, data: ByteArray, video: Boolean): Uri = withContext(Dispatchers.IO) {
        val isJpeg = data.size > 2 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()
        val ext = if (video) "mp4" else if (isJpeg) "jpg" else "png"
        val mime = if (video) "video/mp4" else if (isJpeg) "image/jpeg" else "image/png"
        val name = "aiapp_${System.currentTimeMillis()}.$ext"
        if (Build.VERSION.SDK_INT >= 29) {
            val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/AIApp",
                )
            }
            val uri = context.contentResolver.insert(collection, values) ?: throw ProviderException(500, "Couldn't save to the gallery.")
            context.contentResolver.openOutputStream(uri)?.use { it.write(data) }
            uri
        } else {
            val dir = File(context.getExternalFilesDir(if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES), "AIApp")
                .apply { mkdirs() }
            val f = File(dir, name).apply { writeBytes(data) }
            Uri.fromFile(f)
        }
    }

    private fun File.asRequestBodyCompat(mime: String): okhttp3.RequestBody {
        val type = mime.toMediaType()
        val f = this
        return object : okhttp3.RequestBody() {
            override fun contentType() = type
            override fun contentLength() = f.length()
            override fun writeTo(sink: okio.BufferedSink) {
                f.inputStream().use { input -> sink.outputStream().let { input.copyTo(it) } }
            }
        }
    }
}
