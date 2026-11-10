package com.aiapp.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException

/** Busy, quota-rotated (402), or rate-limited servers: worth waiting a moment or switching fallback model. */
fun ProviderException.isTemporary() = code == 402 || code == 408 || code == 429 || code == 500 || code == 502 || code == 503 || code == 504 ||
    (message?.contains("high demand", true) == true) || (message?.contains("overloaded", true) == true) ||
    (message?.contains("failed to fetch", true) == true)

/**
 * Runs the request; if the server is busy, rate-limited, or the network briefly drops (e.g. when the
 * app goes to the background and resumes) before any text arrives, waits and tries again (2 s, 5 s, 10 s).
 * If [fallback] is given, the last try uses that model instead.
 */
fun resilient(
    attempt: (model: String) -> Flow<String>,
    model: String,
    fallback: (suspend () -> String?)? = null,
    onRetry: (String) -> Unit = {},
): Flow<String> = flow {
    val waits = listOf(2_000L, 5_000L, 10_000L)
    var current = model
    for (i in 0..waits.size) {
        var emitted = false
        try {
            attempt(current).collect {
                emitted = true
                emit(it)
            }
            return@flow
        } catch (e: ProviderException) {
            if (emitted || !e.isTemporary() || i == waits.size) throw e
            if ((i == waits.lastIndex || e.code == 402) && fallback != null) {
                val other = fallback()
                if (other != null && other != current) {
                    onRetry("$current is busy — switching to $other")
                    current = other
                    continue
                }
            }
            onRetry("Server busy — retrying in ${waits[i] / 1000} s")
            delay(waits[i])
        } catch (e: IOException) {
            if (emitted || i == waits.size) throw e
            onRetry("Connection interrupted — reconnecting in ${waits[i] / 1000} s…")
            delay(waits[i])
        }
    }
}

/** A different, usually less busy model from the same provider (Flash/Haiku/mini first). */
suspend fun alternativeModel(provider: ProviderId, apiKey: String, current: String): String? {
    if (provider == ProviderId.OFFLINE) return null
    val ids = runCatching { Providers.rankedModels(provider, apiKey).map { it.id } }.getOrNull() ?: return null
    val fast = listOf("flash", "haiku", "mini", "sonnet", "qwen-coder", "deepseek")
    val bad = listOf("lite", "image", "tts", "audio", "live", "realtime", "embed", "transcribe", "search")
    return ids.firstOrNull { id -> id != current && fast.any { id.contains(it) } && bad.none { id.contains(it) } }
        ?: ids.firstOrNull { it != current && bad.none { b -> it.contains(b) } }
}
