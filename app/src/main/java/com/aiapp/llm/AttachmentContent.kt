package com.aiapp.llm

import com.aiapp.media.VideoEditor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.Base64

/**
 * Turns attachments into each provider's request format. Text (including text pulled out of Office
 * files and zips) goes in as text for every model; photos, PDFs, audio and video go in natively where
 * the model supports them, within each provider's size limits. For video files on OpenAI/FreeCloud/Offline
 * models, real video metadata (duration, resolution, audio track, and estimated Editor V1 render time)
 * is injected so GLM-5.3-Flash (Editor V1) can generate a complete `video-edit` timeline spec.
 */
object AttachmentContent {
    private const val MAX_INLINE_TEXT = 60_000
    private const val CHUNK = 1_500
    private const val RECENT_WITH_FILES = 3
    private const val MB = 1024L * 1024

    enum class Target { CLAUDE, OPENAI, GEMINI, TEXT_ONLY }

    private fun size(a: Attachment) = File(a.path).length()

    /** Can [target] take this file natively (as a file, not text)? Gemini's big files go via its upload API. */
    fun native(a: Attachment, target: Target): Boolean = when (target) {
        Target.CLAUDE -> (a.isImage && size(a) <= 5 * MB) || (a.isPdf && size(a) <= 30 * MB)
        Target.OPENAI -> (a.isImage && size(a) <= 20 * MB) || (a.isPdf && size(a) <= 30 * MB)
        Target.GEMINI -> a.isImage || a.isPdf || a.mime.startsWith("audio/") || a.mime.startsWith("video/")
        Target.TEXT_ONLY -> false
    }

    /** Indices of the user messages whose files should be sent as files. */
    fun binaryIndices(messages: List<ChatMessage>): Set<Int> =
        messages.withIndex()
            .filter { (_, m) -> m.role == Role.USER && m.attachments.any { it.text == null } }
            .map { it.index }
            .takeLast(RECENT_WITH_FILES)
            .toSet()

    /** The message text plus text files (or the most relevant parts of big ones) and notes for files not sent. */
    fun text(m: ChatMessage, target: Target, filesSent: Boolean): String = buildString {
        append(m.text)
        m.attachments.forEach { a ->
            when {
                a.text != null -> {
                    append("\n\n[Attached file: ${a.name}]\n```\n")
                    append(relevant(a.text, m.text))
                    append("\n```")
                }
                filesSent && native(a, target) -> Unit
                else -> append("\n\n" + note(a, target))
            }
        }
    }

    private fun note(a: Attachment, target: Target): String {
        if (a.isVideo) {
            val meta = VideoEditor.inspectVideo(a.path)
            return "[Attached video for Editor V1 AI Video Editing: ${meta.summary(a.name)}. " +
                "Include a ```video-edit JSON block so the app can render the edited MP4 directly on the phone.]"
        }
        val mb = size(a) / MB
        val kind = when {
            a.isImage -> "photo"
            a.isPdf -> "PDF"
            a.mime.startsWith("audio/") -> "audio file"
            else -> "file (${a.mime})"
        }
        val hint = when {
            target == Target.TEXT_ONLY && (a.isImage || a.isPdf) -> "this model can't open it; suggest switching to Claude, Gemini or ChatGPT"
            a.mime.startsWith("audio/") -> "only Gemini can listen to it; suggest switching to Gemini"
            (a.isImage || a.isPdf) -> "it's too large ($mb MB) for this model; suggest Gemini, which takes large files"
            else -> "its contents can't be read as text"
        }
        return "[Attached $kind: ${a.name} — $hint.]"
    }

    /** For big files: the chunks that share the most words with the question, in file order. */
    private fun relevant(text: String, question: String): String {
        if (text.length <= MAX_INLINE_TEXT) return text
        val words = question.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        val chunks = text.chunked(CHUNK)
        if (words.isEmpty()) return text.take(MAX_INLINE_TEXT) + "\n…(file truncated)"
        val scored = chunks.mapIndexed { i, c ->
            val lower = c.lowercase()
            i to words.sumOf { w -> Regex("\\b${Regex.escape(w)}").findAll(lower).count() }
        }
        val keep = scored.sortedByDescending { it.second }.take(MAX_INLINE_TEXT / CHUNK).map { it.first }.sorted()
        return "(Large file: showing the ${keep.size} most relevant of ${chunks.size} parts.)\n" +
            keep.joinToString("\n…\n") { chunks[it] }
    }

    private fun base64(a: Attachment): String? = runCatching {
        Base64.getEncoder().encodeToString(File(a.path).readBytes())
    }.getOrNull()

    private fun files(m: ChatMessage, target: Target, send: Boolean) =
        if (send && m.role == Role.USER) m.attachments.filter { it.text == null && native(it, target) } else emptyList()

    fun anthropic(m: ChatMessage, sendFiles: Boolean): JsonElement {
        val files = files(m, Target.CLAUDE, sendFiles)
        if (files.isEmpty()) return JsonPrimitive(text(m, Target.CLAUDE, false))
        return buildJsonArray {
            files.forEach { a ->
                val data = base64(a) ?: return@forEach
                addJsonObject {
                    put("type", if (a.isPdf) "document" else "image")
                    putJsonObject("source") {
                        put("type", "base64")
                        put("media_type", a.mime)
                        put("data", data)
                    }
                }
            }
            addJsonObject {
                put("type", "text")
                put("text", text(m, Target.CLAUDE, true).ifBlank { "(see attachment)" })
            }
        }
    }

    /** [uploaded] maps a file's path to its Gemini Files API URI (used for big files). */
    fun geminiParts(m: ChatMessage, sendFiles: Boolean, uploaded: Map<String, String> = emptyMap()): JsonArray = buildJsonArray {
        val files = files(m, Target.GEMINI, sendFiles)
        files.forEach { a ->
            val uri = uploaded[a.path]
            if (uri != null) {
                addJsonObject {
                    putJsonObject("fileData") {
                        put("mimeType", a.mime)
                        put("fileUri", uri)
                    }
                }
            } else {
                val data = base64(a) ?: return@forEach
                addJsonObject {
                    putJsonObject("inlineData") {
                        put("mimeType", a.mime)
                        put("data", data)
                    }
                }
            }
        }
        addJsonObject { put("text", text(m, Target.GEMINI, files.isNotEmpty()).ifBlank { "(see attachment)" }) }
    }

    /** Files in [messages] that are too big to send inline to Gemini and need uploading first. */
    fun geminiUploads(messages: List<ChatMessage>): List<Attachment> {
        val idx = binaryIndices(messages)
        return messages.filterIndexed { i, _ -> i in idx }
            .flatMap { files(it, Target.GEMINI, true) }
            .filter { size(it) > GEMINI_INLINE_LIMIT }
    }

    const val GEMINI_INLINE_LIMIT = 15L * 1024 * 1024

    /** OpenAI-style content (also used by OpenRouter). */
    fun openAi(m: ChatMessage, sendFiles: Boolean): JsonElement {
        val files = files(m, Target.OPENAI, sendFiles)
        if (files.isEmpty()) return JsonPrimitive(text(m, Target.OPENAI, false))
        return buildJsonArray {
            addJsonObject {
                put("type", "text")
                put("text", text(m, Target.OPENAI, true).ifBlank { "(see attachment)" })
            }
            files.forEach { a ->
                val data = base64(a) ?: return@forEach
                if (a.isImage) {
                    addJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") { put("url", "data:${a.mime};base64,$data") }
                    }
                } else {
                    addJsonObject {
                        put("type", "file")
                        putJsonObject("file") {
                            put("filename", a.name)
                            put("file_data", "data:application/pdf;base64,$data")
                        }
                    }
                }
            }
        }
    }
}
