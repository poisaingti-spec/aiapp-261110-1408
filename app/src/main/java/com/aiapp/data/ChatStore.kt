package com.aiapp.data

import android.content.Context
import com.aiapp.llm.ChatMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val updated: Long = System.currentTimeMillis(),
    val provider: String = "",
    val model: String = "",
    val messages: List<ChatMessage> = emptyList(),
)

/** Saves each chat as a JSON file in the app's private storage. */
class ChatStore(context: Context) {
    private val dir = File(context.filesDir, "chats").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun save(c: Conversation) {
        val tmp = File(dir, "${c.id}.tmp")
        tmp.writeText(json.encodeToString(c))
        tmp.renameTo(File(dir, "${c.id}.json"))
    }

    fun load(id: String): Conversation? = runCatching {
        json.decodeFromString<Conversation>(File(dir, "$id.json").readText())
    }.getOrNull()

    /** All chats, newest first (messages included; chats are small). */
    fun list(): List<Conversation> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f -> runCatching { json.decodeFromString<Conversation>(f.readText()) }.getOrNull() }
        .sortedByDescending { it.updated }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }
}
