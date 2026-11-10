package com.aiapp.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable

enum class ProviderId(val label: String, val needsKey: Boolean = true) {
    FREE_CLOUD("Free Cloud (No Key)", needsKey = false),
    OFFLINE("Offline (On-Phone)", needsKey = false),
    CLAUDE("Claude"),
    GEMINI("Gemini"),
    OPENAI("ChatGPT"),
    OPEN("Open models"),
}

/** Effort controls how hard the model thinks. AUTO leaves it to the provider's default. */
enum class Effort(val label: String) {
    AUTO("Auto"),
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    MAX("Max"),
}

@Serializable
enum class Role { USER, ASSISTANT }

/**
 * A file attached to a message. [path] points into the app's private storage. [text] holds the
 * extracted text for text-like files (txt, csv, code, json…), so every model can read them.
 */
@Serializable
data class Attachment(
    val name: String,
    val mime: String,
    val path: String,
    val text: String? = null,
) {
    val isImage get() = mime.startsWith("image/")
    val isPdf get() = mime == "application/pdf"
    val isZip get() = mime == "application/zip"
    val isVideo get() = mime.startsWith("video/")
    val isApk get() = name.endsWith(".apk", true) || mime == "application/vnd.android.package-archive"
}

/** An image or video found on the web, shown under a reply. */
@Serializable
data class MediaItem(
    val video: Boolean,
    val url: String,
    val thumb: String,
    val title: String = "",
    val source: String = "",
)

@Serializable
data class ChatMessage(
    val role: Role,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val media: List<MediaItem> = emptyList(),
    /** The model's visible reasoning for this reply, if it shared any. */
    val thinking: String = "",
    val thoughtSeconds: Int = 0,
)

/** Stream chunks starting with this character are the model's thinking, not its answer. */
const val THOUGHT = "\u0001"

fun thought(text: String) = THOUGHT + text

data class ModelInfo(val id: String, val name: String)

class ProviderException(val code: Int, message: String) : Exception(message)

interface LlmProvider {
    val id: ProviderId
    suspend fun listModels(apiKey: String): List<ModelInfo>
    fun stream(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        effort: Effort,
        system: String,
    ): Flow<String>
}

/**
 * Runs [primary]; if it is rejected with HTTP 400 before any text arrives (usually because the
 * model doesn't accept a reasoning setting), runs [fallback] instead.
 */
fun fallbackOn400(primary: () -> Flow<String>, fallback: () -> Flow<String>): Flow<String> =
    fallbackChain(listOf(primary, fallback))

/**
 * Tries each request in order, moving to the next one only when the previous was rejected with
 * HTTP 400 before any text arrived. Lets the newest reasoning settings be tried first, with older
 * formats as backups for models that don't accept them.
 */
fun fallbackChain(attempts: List<() -> Flow<String>>): Flow<String> = flow {
    for ((index, attempt) in attempts.withIndex()) {
        var emitted = false
        try {
            attempt().collect {
                emitted = true
                emit(it)
            }
            return@flow
        } catch (e: ProviderException) {
            if (e.code != 400 || emitted || index == attempts.lastIndex) throw e
        }
    }
}

object Providers {
    private val all: Map<ProviderId, LlmProvider> = listOf(
        FreeCloudProvider(),
        AnthropicProvider(),
        GeminiProvider(),
        OpenAiProvider(),
        OpenModelsProvider(),
    ).associateBy { it.id }

    private lateinit var offlineProvider: OfflineProvider

    /** Call once at startup: the offline engine needs the app's storage. */
    fun init(context: android.content.Context) {
        if (!::offlineProvider.isInitialized) offlineProvider = OfflineProvider(context)
    }

    val offline: OfflineProvider get() = offlineProvider

    /** The offline provider if it was ever created (for freeing memory without creating it). */
    val offlineIfLoaded: OfflineProvider? get() = if (::offlineProvider.isInitialized) offlineProvider else null

    fun get(id: ProviderId): LlmProvider = if (id == ProviderId.OFFLINE) offlineProvider else all.getValue(id)

    /** Live model list for [id], most advanced first. Free Cloud and Offline models keep their curated catalog order. */
    suspend fun rankedModels(id: ProviderId, apiKey: String): List<ModelInfo> {
        val list = get(id).listModels(apiKey)
        return if (id == ProviderId.OFFLINE || id == ProviderId.FREE_CLOUD) list else ModelRanking.rank(id, list)
    }
}
