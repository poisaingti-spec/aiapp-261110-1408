package com.aiapp.builder

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch

/** A saved Build Mode project: the app, its whole agent conversation, and when it was last used. */
@Serializable
data class SavedProject(
    val id: String,
    val updated: Long = System.currentTimeMillis(),
    val project: Project? = null,
    val timeline: List<TimelineItem> = emptyList(),
) {
    val title: String get() = project?.appName
        ?: (timeline.firstOrNull { it is TimelineItem.User } as? TimelineItem.User)?.text?.take(50)
        ?: "Untitled"
    val lastResult: TimelineItem.Result? get() = timeline.lastOrNull { it is TimelineItem.Result } as? TimelineItem.Result
}

/** The single-project save format used before projects history. */
@Serializable
private data class OldSaved(val project: Project? = null, val timeline: List<TimelineItem> = emptyList())

/**
 * Shared Build Mode state: the open project's agent timeline. Every project is saved, so you can
 * come back to any of them later and keep asking for changes (like chats).
 */
object BuildRunner {
    val timeline = mutableStateListOf<TimelineItem>()
    var running by mutableStateOf(false)
        internal set
    var project by mutableStateOf<Project?>(null)
        internal set
    var projectId by mutableStateOf(UUID.randomUUID().toString())
        private set

    /** Live progress of the current long step (upload/unpack): 0..1, or null when there's none. */
    var progress by mutableStateOf<Float?>(null)
        internal set
    var progressText by mutableStateOf("")
        internal set

    /** The request the service should run next. */
    @Volatile
    internal var pending: BuildRequest? = null

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var loaded = false

    private fun dir(context: Context) = File(context.filesDir, "build_projects").apply { mkdirs() }
    private fun prefs(context: Context) = context.getSharedPreferences("build_mode", Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        // One-time move from the single-project file used before.
        val old = File(context.filesDir, "build_mode.json")
        if (old.exists()) {
            runCatching {
                val o = json.decodeFromString<OldSaved>(old.readText())
                if (o.timeline.isNotEmpty()) {
                    val id = UUID.randomUUID().toString()
                    File(dir(context), "$id.json").writeText(json.encodeToString(SavedProject(id, old.lastModified(), o.project, o.timeline)))
                    prefs(context).edit().putString("current", id).apply()
                }
            }
            old.delete()
        }
        prefs(context).getString("current", null)?.let { open(context, it) }
    }

    private val io = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private var pendingSave: kotlinx.coroutines.Job? = null

    /** Saves soon (batched, off the main thread) so frequent progress updates don't make the app stutter. */
    fun save(context: Context) {
        if (pendingSave?.isActive == true) return
        val app = context.applicationContext
        pendingSave = io.launch {
            kotlinx.coroutines.delay(1_500)
            writeNow(app)
        }
    }

    /** Saves right away (end of a build). */
    fun saveNow(context: Context) {
        pendingSave?.cancel()
        val app = context.applicationContext
        val snapshot = SavedProject(projectId, System.currentTimeMillis(), project, timeline.toList())
        io.launch { write(app, snapshot) }
    }

    private suspend fun writeNow(context: Context) {
        val snapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            SavedProject(projectId, System.currentTimeMillis(), project, timeline.toList())
        }
        write(context, snapshot)
    }

    private fun write(context: Context, s: SavedProject) {
        if (s.timeline.isEmpty() && s.project == null) return
        runCatching {
            val f = File(dir(context), "${s.id}.json")
            val tmp = File(dir(context), "${s.id}.tmp")
            tmp.writeText(json.encodeToString(s))
            tmp.renameTo(f)
            prefs(context).edit().putString("current", s.id).apply()
        }
    }

    /** All saved projects, most recently used first. */
    fun list(context: Context): List<SavedProject> = dir(context).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f -> runCatching { json.decodeFromString<SavedProject>(f.readText()) }.getOrNull() }
        .sortedByDescending { it.updated }

    /** Opens a saved project to keep working on it. */
    fun open(context: Context, id: String): Boolean {
        if (running) return false
        val saved = runCatching { json.decodeFromString<SavedProject>(File(dir(context), "$id.json").readText()) }.getOrNull()
            ?: return false
        projectId = saved.id
        project = saved.project
        timeline.clear()
        timeline.addAll(saved.timeline)
        prefs(context).edit().putString("current", id).apply()
        return true
    }

    fun delete(context: Context, id: String) {
        if (running && id == projectId) return
        File(dir(context), "$id.json").delete()
        if (id == projectId) newProject(context)
    }

    /** Starts a fresh project; the current one stays saved in Projects (and its repo stays on GitHub). */
    fun newProject(context: Context) {
        if (running) return
        saveNow(context)
        projectId = UUID.randomUUID().toString()
        project = null
        timeline.clear()
        prefs(context).edit().remove("current").apply()
    }
}
