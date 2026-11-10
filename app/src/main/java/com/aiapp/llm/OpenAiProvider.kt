package com.aiapp.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class OpenAiProvider : LlmProvider {
    override val id = ProviderId.OPENAI

    private val base = "https://api.openai.com/v1"

    private fun headers(key: String) = mapOf("Authorization" to "Bearer $key")

    // Keep "codex" enabled so ChatGPT Codex models (gpt-5-codex, codex-mini-latest, etc.) work in Chat and Build Mode.
    private val excluded = listOf(
        "audio", "realtime", "tts", "transcribe", "image", "search", "embedding",
        "moderation", "whisper", "dall-e", "instruct",
    )

    private val reasoningPattern = Regex("^(o\\d|gpt-5|codex)")

    override suspend fun listModels(apiKey: String): List<ModelInfo> {
        val data = Http.getJson("$base/models", headers(apiKey))["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { el -> ((el as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }
            .filter { id ->
                (id.startsWith("gpt") || id.startsWith("chatgpt") || id.contains("codex") || Regex("^o\\d").containsMatchIn(id)) &&
                    excluded.none { id.contains(it) }
            }
            .map { ModelInfo(it, it) }
    }

    private fun effortValue(effort: Effort): String? = when (effort) {
        Effort.LOW -> "low"
        Effort.MEDIUM -> "medium"
        Effort.HIGH -> "high"
        Effort.MAX -> "xhigh"
        Effort.AUTO -> null
    }

    override fun stream(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        effort: Effort,
        system: String,
    ): Flow<String> {
        val level = effortValue(effort)
        val attempts = mutableListOf<() -> Flow<String>>()
        if (level != null) attempts += { request(apiKey, model, messages, system, level) }
        // Models without the top level fall back to "high".
        if (level == "xhigh") attempts += { request(apiKey, model, messages, system, "high") }
        attempts += { request(apiKey, model, messages, system, null) }
        // Codex models that require the /v1/responses endpoint fall back automatically.
        attempts += { requestResponsesApi(apiKey, model, messages, system) }
        return fallbackChain(attempts)
    }

    private fun request(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        system: String,
        effort: String?,
    ): Flow<String> {
        val reasoning = reasoningPattern.containsMatchIn(model)
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", if (reasoning) "developer" else "system")
                    put("content", system)
                }
                val withFiles = AttachmentContent.binaryIndices(messages)
                messages.forEachIndexed { i, m ->
                    addJsonObject {
                        put("role", if (m.role == Role.USER) "user" else "assistant")
                        put("content", AttachmentContent.openAi(m, i in withFiles))
                    }
                }
            }
            if (reasoning && effort != null) put("reasoning_effort", effort)
        }.toString()

        return Http.sse("$base/chat/completions", headers(apiKey), body).mapNotNull { data -> parse(data) }
    }

    /** Fallback for Codex models (e.g. codex-mini-latest, gpt-5-codex) that use OpenAI's /v1/responses endpoint. */
    private fun requestResponsesApi(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        system: String,
    ): Flow<String> {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("instructions", system)
            putJsonArray("input") {
                val withFiles = AttachmentContent.binaryIndices(messages)
                messages.forEachIndexed { i, m ->
                    addJsonObject {
                        put("role", if (m.role == Role.USER) "user" else "assistant")
                        put("content", AttachmentContent.text(m, AttachmentContent.Target.OPENAI, i in withFiles))
                    }
                }
            }
        }.toString()

        return Http.sse("$base/responses", headers(apiKey), body).mapNotNull { data -> parseResponses(data) }
    }

    private fun parse(data: String): String? {
        val o = Http.json.parseToJsonElement(data).jsonObject
        val choice = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val delta = choice["delta"] as? JsonObject ?: return null
        return (delta["content"] as? JsonPrimitive)?.contentOrNull
    }

    private fun parseResponses(data: String): String? {
        val o = Http.json.parseToJsonElement(data).jsonObject
        val type = (o["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when (type) {
            "response.output_text.delta" -> (o["delta"] as? JsonPrimitive)?.contentOrNull
            "response.reasoning_summary_text.delta" -> (o["delta"] as? JsonPrimitive)?.contentOrNull?.let { thought(it) }
            else -> null
        }
    }
}
