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

/**
 * The biggest open-weight and unrestricted coding models (Qwen3 Coder, DeepSeek, Kimi, GLM,
 * Dolphin, Hermes, Llama, Mistral, Gemma), hosted in the cloud by OpenRouter.
 */
class OpenModelsProvider : LlmProvider {
    override val id = ProviderId.OPEN

    private val base = "https://openrouter.ai/api/v1"

    private fun headers(key: String) = mapOf(
        "Authorization" to "Bearer $key",
        "X-OpenRouter-Title" to "AI App",
    )

    /** Makers of open-weight and unrestricted coding models. */
    private val openVendors = listOf(
        "qwen/", "deepseek/", "moonshotai/", "z-ai/", "minimax/", "meta-llama/", "mistralai/",
        "google/gemma", "openai/gpt-oss", "nvidia/", "microsoft/phi", "stepfun", "baidu/", "xiaomi/",
        "nousresearch/", "cognitivecomputations/", "featherless/",
    )

    override suspend fun listModels(apiKey: String): List<ModelInfo> {
        val data = Http.getJson("$base/models", headers(apiKey))["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val lower = id.lowercase()
            if (openVendors.none { lower.startsWith(it) }) return@mapNotNull null
            val outputs = ((o["architecture"] as? JsonObject)?.get("output_modalities") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (outputs != null && "text" !in outputs) return@mapNotNull null
            ModelInfo(id, (o["name"] as? JsonPrimitive)?.contentOrNull ?: id)
        }
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
        if (level == "xhigh") attempts += { request(apiKey, model, messages, system, "high") }
        attempts += { request(apiKey, model, messages, system, null) }
        return fallbackChain(attempts)
    }

    private fun request(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        system: String,
        effort: String?,
    ): Flow<String> {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
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
            if (effort != null) put("reasoning_effort", effort)
            put("include_reasoning", true)
        }.toString()

        return Http.sse("$base/chat/completions", headers(apiKey), body).mapNotNull { data -> parse(data) }
    }

    private fun parse(data: String): String? {
        val o = Http.json.parseToJsonElement(data).jsonObject
        (o["error"] as? JsonObject)?.let { err ->
            throw ProviderException(500, (err["message"] as? JsonPrimitive)?.contentOrNull ?: "Model error")
        }
        val choice = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val delta = choice["delta"] as? JsonObject ?: return null
        val content = (delta["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val reasoning = (delta["reasoning"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when {
            content.isNotEmpty() -> content
            reasoning.isNotEmpty() -> thought(reasoning)
            else -> null
        }
    }
}
