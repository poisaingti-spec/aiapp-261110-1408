package com.aiapp.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
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

class GeminiProvider : LlmProvider {
    override val id = ProviderId.GEMINI

    private val base = "https://generativelanguage.googleapis.com/v1beta"

    private fun headers(key: String) = mapOf("x-goog-api-key" to key)

    override suspend fun listModels(apiKey: String): List<ModelInfo> {
        val models = Http.getJson("$base/models?pageSize=200", headers(apiKey))["models"] as? JsonArray ?: return emptyList()
        return models.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val methods = (o["supportedGenerationMethods"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            if ("generateContent" !in methods || !name.contains("gemini")) return@mapNotNull null
            val id = name.removePrefix("models/")
            ModelInfo(id, (o["displayName"] as? JsonPrimitive)?.contentOrNull ?: id)
        }
    }

    private fun thinkingBudget(effort: Effort): Int? = when (effort) {
        Effort.LOW -> 0
        Effort.HIGH -> 8_192
        Effort.MAX -> 24_576
        else -> null
    }

    override fun stream(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        effort: Effort,
        system: String,
    ): Flow<String> = fallbackOn400(
        primary = { request(apiKey, model, messages, system, thinkingBudget(effort), thoughts = true) },
        fallback = { request(apiKey, model, messages, system, null) },
    )

    private fun request(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        system: String,
        budget: Int?,
        thoughts: Boolean = false,
    ): Flow<String> = flow {
        // Big files (over ~15 MB) go through Gemini's upload API first.
        val uploaded = AttachmentContent.geminiUploads(messages).associate { it.path to GeminiFiles.uri(apiKey, it) }
        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", system) } }
            }
            val withFiles = AttachmentContent.binaryIndices(messages)
            putJsonArray("contents") {
                messages.forEachIndexed { i, m ->
                    addJsonObject {
                        put("role", if (m.role == Role.USER) "user" else "model")
                        put("parts", AttachmentContent.geminiParts(m, i in withFiles, uploaded))
                    }
                }
            }
            if (budget != null || thoughts) {
                putJsonObject("generationConfig") {
                    putJsonObject("thinkingConfig") {
                        if (budget != null) put("thinkingBudget", budget)
                        // Stream summaries of the model's thinking so they can be shown live.
                        if (thoughts) put("includeThoughts", true)
                    }
                }
            }
        }.toString()

        val url = "$base/models/$model:streamGenerateContent?alt=sse"
        emitAll(Http.sse(url, headers(apiKey), body).mapNotNull { data -> parse(data) })
    }

    private fun parse(data: String): String? {
        val o = Http.json.parseToJsonElement(data).jsonObject
        val candidate = (o["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val parts = (candidate["content"] as? JsonObject)?.get("parts") as? JsonArray ?: return null
        var answer = ""
        var thinking = ""
        parts.forEach { p ->
            val part = p as? JsonObject ?: return@forEach
            val t = (part["text"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            if ((part["thought"] as? JsonPrimitive)?.booleanOrNull == true) thinking += t else answer += t
        }
        return when {
            answer.isNotEmpty() -> answer
            thinking.isNotEmpty() -> thought(thinking)
            else -> null
        }
    }
}
