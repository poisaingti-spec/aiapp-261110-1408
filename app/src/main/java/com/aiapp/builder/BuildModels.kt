package com.aiapp.builder

import kotlinx.serialization.Serializable

/** One line in the agent timeline. */
@Serializable
data class BuildEvent(val kind: Kind, val text: String) {
    @Serializable
    enum class Kind { STEP, THINKING, WEB, FILE, LIBRARY, DOWNLOAD, BUILD, ERROR, DONE }
}

/** Something shown in the Build Mode conversation. */
@Serializable
sealed class TimelineItem {
    @Serializable
    data class User(val text: String) : TimelineItem()

    @Serializable
    data class Event(val event: BuildEvent) : TimelineItem()

    @Serializable
    data class Result(val success: Boolean, val message: String, val apkUrl: String? = null, val repoUrl: String? = null) : TimelineItem()

    /** Direct conversational reply from the Build Agent when the user asks a question or discusses ideas without triggering an APK build. */
    @Serializable
    data class Reply(val text: String, val thinking: String = "") : TimelineItem()
}

/** The app being built, kept between requests so follow-ups edit the same project. */
@Serializable
data class Project(
    val appName: String,
    val packageName: String,
    val owner: String,
    val repo: String,
    val branch: String,
    val repoUrl: String,
    val description: String,
    val files: Map<String, String> = emptyMap(),
    val deps: List<String> = emptyList(),
    val lastApkUrl: String? = null,
    /** Uploaded from a .zip rather than written by the app (any file can be edited; own build setup). */
    val imported: Boolean = false,
    /** The uploaded zip, kept so an interrupted upload can be finished later. */
    val zipPath: String? = null,
    val zipName: String? = null,
    /** True once the project's files have reached GitHub at least once. */
    val uploaded: Boolean = false,
    /** Checkpoint: the last commit sent to GitHub (its build can be checked again on Retry). */
    val lastSha: String? = null,
    /** Checkpoint: files changed by the model but not yet uploaded. */
    val pendingPaths: List<String> = emptyList(),
    /** Checkpoint: which fix round the build had reached. */
    val round: Int = 0,
)
