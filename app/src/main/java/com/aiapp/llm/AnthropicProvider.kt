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
import kotlinx.serialization.json.putJsonObject

class AnthropicProvider : LlmProvider {
    override val id = ProviderId.CLAUDE

    private val base = "https://api.anthropic.com/v1"

    private fun headers(key: String) = mapOf(
        "x-api-key" to key,
        "anthropic-version" to "2023-06-01",
    )

    override suspend fun listModels(apiKey: String): List<ModelInfo> {
        val data = Http.getJson("$base/models?limit=100", headers(apiKey))["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            ModelInfo(id, (o["display_name"] as? JsonPrimitive)?.contentOrNull ?: id)
        }
    }

    /** Effort level for adaptive thinking (Claude Opus 4.6 and newer, including Opus 5.5 and Fable 5.1). */
    private fun effortValue(effort: Effort): String? = when (effort) {
        Effort.LOW -> "low"
        Effort.MEDIUM -> "medium"
        Effort.HIGH -> "high"
        Effort.MAX -> "max"
        Effort.AUTO -> null
    }

    /** Thinking budget for older models that only understand manual extended thinking. */
    private fun thinkingBudget(effort: Effort): Int? = when (effort) {
        Effort.HIGH -> 8_000
        Effort.MAX -> 24_000
        else -> null
    }

    /** Output room: high and max let the model think a lot, and max_tokens caps thinking plus answer. */
    private fun maxTokens(effort: Effort): Int = when (effort) {
        Effort.MAX -> 64_000
        Effort.HIGH -> 32_000
        else -> 16_000
    }

    override fun stream(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        effort: Effort,
        system: String,
    ): Flow<String> {
        val attempts = mutableListOf<() -> Flow<String>>()
        val level = effortValue(effort)
        if (level != null) {
            // Newest format: adaptive thinking steered by output_config.effort, with visible thinking.
            attempts += { request(apiKey, model, messages, system, maxTokens(effort), level, null, showThinking = true) }
            attempts += { request(apiKey, model, messages, system, maxTokens(effort), level, null) }
            // Some models cap below "max".
            if (level == "max") attempts += { request(apiKey, model, messages, system, maxTokens(effort), "high", null) }
        }
        thinkingBudget(effort)?.let { budget ->
            attempts += { request(apiKey, model, messages, system, budget + 8_000, null, budget) }
        }
        // Default settings. Newer models still think on their own here, so leave them room first.
        attempts += { request(apiKey, model, messages, system, 16_000, null, null) }
        attempts += { request(apiKey, model, messages, system, 8_000, null, null) }
        return fallbackChain(attempts)
    }

    private fun request(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        system: String,
        maxTokens: Int,
        effort: String?,
        budget: Int?,
        showThinking: Boolean = false,
    ): Flow<String> {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("stream", true)
            put("system", system)
            val withFiles = AttachmentContent.binaryIndices(messages)
            putJsonArray("messages") {
                messages.forEachIndexed { i, m ->
                    addJsonObject {
                        put("role", if (m.role == Role.USER) "user" else "assistant")
                        put("content", AttachmentContent.anthropic(m, i in withFiles))
                    }
                }
            }
            if (effort != null) {
                putJsonObject("thinking") {
                    put("type", "adaptive")
                    // Ask for a readable summary of the reasoning, to show live.
                    if (showThinking) put("display", "summarized")
                }
                putJsonObject("output_config") { put("effort", effort) }
            } else if (budget != null) {
                putJsonObject("thinking") {
                    put("type", "enabled")
                    put("budget_tokens", budget)
                }
            }
        }.toString()

        return Http.sse("$base/messages", headers(apiKey), body).mapNotNull { data -> parse(data) }
    }

    private fun parse(data: String): String? {
        val o = Http.json.parseToJsonElement(data).jsonObject
        return when ((o["type"] as? JsonPrimitive)?.contentOrNull) {
            "content_block_delta" -> {
                val delta = o["delta"] as? JsonObject ?: return null
                when ((delta["type"] as? JsonPrimitive)?.contentOrNull) {
                    "text_delta" -> (delta["text"] as? JsonPrimitive)?.contentOrNull
                    "thinking_delta" -> (delta["thinking"] as? JsonPrimitive)?.contentOrNull?.let { thought(it) }
                    else -> null
                }
            }
            "error" -> {
                val err = o["error"] as? JsonObject
                throw ProviderException(500, (err?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Claude error")
            }
            else -> null
        }
    }
}
