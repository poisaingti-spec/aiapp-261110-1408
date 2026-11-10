package com.aiapp.builder

import com.aiapp.llm.ChatMessage
import com.aiapp.llm.Effort
import com.aiapp.llm.Http
import com.aiapp.llm.ProviderId
import com.aiapp.llm.Providers
import com.aiapp.llm.Role
import com.aiapp.llm.WebTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BuildRequest(
    /** The new app's description, or the change to make to [BuildRunner.project]. */
    val description: String,
    val appName: String,
    val provider: ProviderId,
    val model: String,
    val apiKey: String,
    val effort: Effort,
    val githubToken: String,
    /** Let the agent download files from sites outside the built-in safe list. */
    val allowOtherSites: Boolean,
    /** Continue this project instead of starting a new one. */
    val project: Project? = null,
    /** An uploaded Android Studio project (.zip) to build instead of writing a new app. */
    val zipPath: String? = null,
    val zipName: String? = null,
    /** Files attached to this request: shown to the model, and images/fonts/sounds added to the app. */
    val attachments: List<com.aiapp.llm.Attachment> = emptyList(),
    /** Pick up where the last build of [project] stopped instead of starting over. */
    val resume: Boolean = false,
    /** Optional custom package ID when rebuilding/renaming an APK via REA (e.g. com.reabuilt.myapp). */
    val customPackageName: String? = null,
    /** Optional path to an uploaded APK whose drawables, fonts, raw media, and assets should be bundled into the rebuilt app. */
    val sourceApkPath: String? = null,
)

data class BuildResult(
    val success: Boolean,
    val message: String,
    val repoUrl: String? = null,
    val apkUrl: String? = null,
    /** Non-null when the model answered a question conversationally without modifying files or starting a build. */
    val conversationalReply: String? = null,
)

/**
 * Build Mode agent: the model writes or edits the app, GitHub Actions builds it, and build errors
 * go back to the model to fix, until the APK builds or the fix rounds for the Brain level run out.
 * If the user asked a conversational question and the model replied without modifying files, returns
 * [BuildResult.conversationalReply] immediately without triggering an unnecessary GitHub Actions build.
 */
class BuildAgent(
    private val req: BuildRequest,
    private val emit: (BuildEvent) -> Unit,
    /** Called whenever the project changes, so it can be saved. */
    private val onProject: (Project) -> Unit = {},
    /** Live progress for long steps (null = done), with a short description. */
    private val onProgress: (Float?, String) -> Unit = { _, _ -> },
) {
    private val gh = GitHubClient(req.githubToken)
    private val slug = (req.project?.appName ?: req.appName).lowercase().replace(Regex("[^a-z0-9]+"), "").ifEmpty { "app" }.take(30)
    private val packageName = req.project?.packageName
        ?: req.customPackageName?.takeIf { it.matches(Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")) }
        ?: ("com.aibuilt." + (if (slug.first().isLetter()) slug else "a$slug"))
    private val appName get() = req.project?.appName ?: importedName ?: req.appName

    private val imported = req.project?.imported == true || req.zipPath != null
    private val workflowFile = if (imported) ProjectTemplate.IMPORT_WORKFLOW_FILE else "build.yml"
    private var importedName: String? = null
    private var uploadedOnce = false
    private var currentSha: String? = req.project?.lastSha
    private var currentRound = 0

    private val files = linkedMapOf<String, String>().apply { req.project?.files?.let { putAll(it) } }
    private val deps = linkedSetOf<String>().apply { req.project?.deps?.let { addAll(it) } }

    /** Files changed since the last commit (null value = delete). */
    private val pendingFiles = linkedMapOf<String, ByteArray?>()
    /** Big uploaded files waiting to be committed straight from storage. */
    private val pendingDisk = linkedMapOf<String, java.io.File>()
    private var depsChanged = false

    private val maxFixRounds: Int = when (req.effort) {
        Effort.LOW -> 1
        Effort.MEDIUM, Effort.AUTO -> 3
        Effort.HIGH -> 6
        Effort.MAX -> 10
    } + if (req.provider == ProviderId.OFFLINE) 2 else 0

    private fun step(kind: BuildEvent.Kind, text: String) = emit(BuildEvent(kind, text))

    suspend fun run(): BuildResult {
        step(BuildEvent.Kind.STEP, "Checking your GitHub token")
        gh.login()

        val existing = req.project
        val repo: GitHubClient.Repo
        var notes: List<String>
        val neverUploaded = existing != null && existing.imported && !existing.uploaded && existing.lastApkUrl == null
        val resumeZip = existing?.takeIf { neverUploaded }?.zipPath?.takeIf { java.io.File(it).exists() }
        val zipToRead = req.zipPath ?: resumeZip
        var checkSha: String? = null
        var startRound = 0
        if (req.resume && existing != null && resumeZip == null) {
            // Retry: continue from the saved checkpoint instead of starting over.
            repo = GitHubClient.Repo(existing.owner, existing.repo, existing.branch, existing.repoUrl)
            notes = emptyList()
            startRound = existing.round
            val restored = existing.pendingPaths.mapNotNull { p ->
                val text = files[p] ?: (if (!imported) ProjectTemplate.files(appName, packageName, deps.toList())[p] else null)
                text?.let { p to it.toByteArray() }
            }
            when {
                restored.isNotEmpty() -> {
                    restored.forEach { (p, b) -> pendingFiles[p] = b }
                    step(BuildEvent.Kind.STEP, "Resuming: uploading ${restored.size} file(s) that didn't reach GitHub last time")
                }
                existing.lastSha != null -> {
                    checkSha = existing.lastSha
                    step(BuildEvent.Kind.STEP, "Resuming: checking the last build on GitHub (no code is rewritten)")
                }
                else -> step(BuildEvent.Kind.STEP, "Resuming: starting a fresh build of the current code")
            }
        } else if ((existing == null && req.zipPath != null) || resumeZip != null) {
            val zipLabel = req.zipName ?: existing?.zipName ?: "the project"
            if (resumeZip != null) step(BuildEvent.Kind.STEP, "The first upload didn't finish — uploading the whole project again")
            step(BuildEvent.Kind.STEP, "Unpacking $zipLabel")
            onProgress(-1f, "Reading ${mb(java.io.File(zipToRead!!).length())}…")
            val zip = withContext(Dispatchers.IO) { ZipImport.read(java.io.File(zipToRead), zipLabel) }
            onProgress(null, "")
            importedName = zip.name
            zip.files.forEach { (path, bytes) ->
                pendingFiles[path] = bytes
                if (ZipImport.isText(path) && bytes.size < 200_000) files[path] = bytes.toString(Charsets.UTF_8)
            }
            pendingDisk.putAll(zip.bigFiles)
            if (zip.tooBig.isNotEmpty()) {
                step(BuildEvent.Kind.ERROR, "Skipped ${zip.tooBig.size} file(s) over 95 MB (GitHub's limit): ${zip.tooBig.take(3).joinToString { it.substringAfterLast('/') }}")
            }
            pendingFiles[".github/workflows/${ProjectTemplate.IMPORT_WORKFLOW_FILE}"] = ProjectTemplate.importWorkflow.toByteArray()
            step(BuildEvent.Kind.STEP, "Found ${zip.count} files in \"${zip.name}\"" + if (zip.skipped > 0) " (skipped ${zip.skipped} build/cache files)" else "")
            val repoName = zip.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "project" }.take(40) +
                "-" + SimpleDateFormat("yyMMdd-HHmm", Locale.US).format(Date())
            repo = if (existing != null) {
                GitHubClient.Repo(existing.owner, existing.repo, existing.branch, existing.repoUrl)
            } else {
                step(BuildEvent.Kind.STEP, "Creating GitHub repo $repoName")
                gh.createRepo(repoName, "Uploaded to AI App: ${zip.name}")
            }
            notes = emptyList()
        } else if (existing == null) {
            step(BuildEvent.Kind.THINKING, "Planning and writing the app with ${req.provider.label}")
            val assets = addAttachedAssets()
            val rawOutput = ask(firstPrompt() + assets, req.attachments)
            notes = apply(rawOutput)
            if (files.isEmpty()) {
                val cleanReply = rawOutput.trim()
                if (cleanReply.isNotBlank()) {
                    return BuildResult(true, "Answered your question (no build started).", conversationalReply = cleanReply)
                }
                return BuildResult(false, "The model didn't return any files. Try again or pick a stronger model.")
            }
            val repoName = "$slug-" + SimpleDateFormat("yyMMdd-HHmm", Locale.US).format(Date())
            step(BuildEvent.Kind.STEP, "Creating GitHub repo $repoName")
            repo = gh.createRepo(repoName, "Built by AI App: ${req.description}")
            ProjectTemplate.files(appName, packageName, deps.toList()).forEach { (p, c) -> pendingFiles[p] = c.toByteArray() }
            if ("app/src/main/AndroidManifest.xml" !in files) {
                pendingFiles["app/src/main/AndroidManifest.xml"] = ProjectTemplate.defaultManifest(appName).toByteArray()
            }
        } else {
            repo = GitHubClient.Repo(existing.owner, existing.repo, existing.branch, existing.repoUrl)
            step(BuildEvent.Kind.THINKING, "Working out the changes with ${req.provider.label}")
            val assets = addAttachedAssets()
            val rawOutput = ask(changePrompt() + assets, req.attachments)
            notes = apply(rawOutput)
            if (pendingFiles.isEmpty() && !depsChanged) {
                val cleanReply = rawOutput.trim()
                if (cleanReply.isNotBlank() && !cleanReply.contains("=== FILE:")) {
                    return BuildResult(
                        success = true,
                        message = "Answered your question (no files changed).",
                        repoUrl = repo.url,
                        apkUrl = existing.lastApkUrl,
                        conversationalReply = cleanReply,
                    )
                }
                return BuildResult(false, "The model didn't change any files. Try describing the change differently.", repo.url, existing.lastApkUrl)
            }
        }
        saveProject(repo, null)

        var round = startRound
        while (true) {
          currentRound = round
          val sha: String
          val resumeSha = checkSha
          if (resumeSha != null) {
            // Already on GitHub: just look at (or wait for) that build.
            checkSha = null
            sha = resumeSha
          } else {
            if (depsChanged) {
                pendingFiles["app/build.gradle.kts"] =
                    ProjectTemplate.files(appName, packageName, deps.toList()).getValue("app/build.gradle.kts").toByteArray()
                depsChanged = false
            }
            // Make sure GitHub always has the build instructions (harmless if unchanged).
            if (imported) {
                pendingFiles[".github/workflows/${ProjectTemplate.IMPORT_WORKFLOW_FILE}"] = ProjectTemplate.importWorkflow.toByteArray()
            } else {
                ProjectTemplate.files(appName, packageName, deps.toList())[".github/workflows/build.yml"]?.let {
                    pendingFiles[".github/workflows/build.yml"] = it.toByteArray()
                }
            }
            val toUpload = pendingFiles.size + pendingDisk.size
            step(BuildEvent.Kind.STEP, "Uploading $toUpload changed file${if (toUpload == 1) "" else "s"} to GitHub")
            val message = when {
                round > 0 -> "Fix build errors (round $round)"
                existing != null -> "Change: ${req.description.take(60)}"
                req.zipPath != null -> "Uploaded project from AI App"
                else -> "Initial app from AI App"
            }
            val total = pendingFiles.size + pendingDisk.size
            var lastPct = -1
            sha = gh.commit(repo, pendingFiles.toMap(), message, pendingDisk.toMap()) { n, of, sent, size ->
                val pct = if (size > 0) (sent * 100 / size).toInt() else n * 100 / of.coerceAtLeast(1)
                if (pct != lastPct) {
                    lastPct = pct
                    onProgress(pct / 100f, "$n of $of files · ${mb(sent)} of ${mb(size)}")
                }
            }
            onProgress(null, "")
            step(BuildEvent.Kind.DONE, "Upload finished: $total file${if (total == 1) "" else "s"} (${mb(pendingFiles.values.sumOf { (it?.size ?: 0).toLong() } + pendingDisk.values.sumOf { it.length() })})")
            pendingFiles.clear()
            pendingDisk.values.forEach { it.delete() }
            pendingDisk.clear()
            uploadedOnce = true
            currentSha = sha
            saveProject(repo, null)
          }

            step(BuildEvent.Kind.BUILD, "Building the APK on GitHub (usually 3–5 minutes)")
            val run = waitForRun(repo, sha)
                ?: return BuildResult(
                    false,
                    if (neverUploaded) {
                        "GitHub didn't start a build because this project's first upload never finished. Tap New, then upload the zip again."
                    } else {
                        "GitHub didn't start a build. Open the repo on GitHub → Actions: if Actions is turned off for the repo, " +
                            "turn it on and try again; otherwise sign out and sign in with GitHub again in Settings."
                    },
                    repo.url,
                )

            if (run.conclusion == "success") {
                step(BuildEvent.Kind.STEP, "Build succeeded. Getting the download link")
                // Only hand over a link once the APK file is really there.
                var apk: String? = null
                var size: Long? = null
                for (i in 0 until 20) {
                    apk = gh.latestApkUrl(repo)
                    size = apk?.let { com.aiapp.data.ApkInstaller.check(it) }
                    if (apk != null && size != null) break
                    delay(3000)
                }
                if (apk == null || size == null) {
                    return BuildResult(false, "The build worked, but GitHub hasn't published the APK yet. Open the repo's Releases on GitHub in a minute.", repo.url)
                }
                step(BuildEvent.Kind.DONE, "APK ready: ${mb(size)}")
                saveProject(repo, apk)
                step(BuildEvent.Kind.DONE, "Done")
                return BuildResult(
                    true,
                    when {
                        existing != null -> "Your changes are built."
                        req.zipPath != null -> "Your project compiled to an APK."
                        else -> "Your app is ready."
                    },
                    repo.url,
                    apk,
                )
            }

            if (round >= maxFixRounds) {
                return BuildResult(false, "The build still fails after $round fix rounds. Try a higher Brain level or a stronger model.", repo.url)
            }
            round++
            currentRound = round
            step(BuildEvent.Kind.STEP, "Reading the build errors")
            val errors = gh.failureSummary(repo, run.id)
            val count = errors.lines().count { it.startsWith("e: ") || it.contains("error:", true) }.coerceAtLeast(1)
            step(BuildEvent.Kind.ERROR, "Build failed with $count error${if (count == 1) "" else "s"}. Fixing (round $round of $maxFixRounds)")
            notes = apply(ask(fixPrompt(errors, notes)))
            saveProject(repo, null)
        }
    }

    private fun mb(bytes: Long) = if (bytes < 1024 * 1024) "${bytes / 1024} KB" else "%.1f MB".format(bytes / 1048576.0)

    private fun saveProject(repo: GitHubClient.Repo, apk: String?) {
        onProject(
            Project(
                appName = appName,
                packageName = packageName,
                owner = repo.owner,
                repo = repo.name,
                branch = repo.branch,
                repoUrl = repo.url,
                description = req.project?.description ?: req.description.ifBlank { "Uploaded project" },
                files = files.toMap(),
                deps = deps.toList(),
                lastApkUrl = apk ?: req.project?.lastApkUrl,
                imported = imported,
                zipPath = req.zipPath ?: req.project?.zipPath,
                zipName = req.zipName ?: req.project?.zipName,
                uploaded = uploadedOnce || req.project?.uploaded == true,
                lastSha = currentSha,
                pendingPaths = pendingFiles.keys.toList(),
                round = currentRound,
            ),
        )
    }

    /** Adds attached images, fonts, sounds and other files to the project; returns notes for the prompt. */
    private fun addAttachedAssets(): String {
        val notes = mutableListOf<String>()
        req.sourceApkPath?.let { apkPath ->
            val apkFile = java.io.File(apkPath)
            if (apkFile.exists()) {
                val extracted = ReaEngine.extractReusableResources(apkFile)
                if (extracted.files.isNotEmpty()) {
                    pendingFiles.putAll(extracted.files)
                    step(BuildEvent.Kind.DOWNLOAD, "REA bundled ${extracted.files.size} resources (drawables/fonts/raw/assets) from ${apkFile.name}")
                    notes += extracted.promptSummary
                }
            }
        }
        req.attachments.filter { it.text == null && !it.isZip }.forEach { a ->
            val ext = a.name.substringAfterLast('.', "").lowercase()
            val base = a.name.substringBeforeLast('.').lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
                .let { if (it.isEmpty() || !it.first().isLetter()) "file_$it" else it }.take(40)
            val path = when {
                ext in listOf("png", "jpg", "jpeg", "webp") || (a.isImage && a.mime == "image/jpeg") ->
                    "app/src/main/res/drawable/$base.${if (ext == "jpeg" || (a.mime == "image/jpeg" && ext !in listOf("png", "webp"))) "jpg" else ext}"
                ext in listOf("ttf", "otf") -> "app/src/main/res/font/$base.$ext"
                ext in listOf("mp3", "ogg", "wav", "m4a", "aac", "mp4", "webm") -> "app/src/main/res/raw/$base.$ext"
                else -> "app/src/main/assets/${a.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
            }
            val bytes = runCatching { java.io.File(a.path).readBytes() }.getOrNull() ?: return@forEach
            if (bytes.size > MAX_DOWNLOAD) {
                notes += "${a.name} is over 15 MB, so it wasn't added to the app."
                return@forEach
            }
            pendingFiles[path] = bytes
            val ref = when {
                path.contains("/res/drawable/") -> "R.drawable.${path.substringAfterLast('/').substringBeforeLast('.')}"
                path.contains("/res/font/") -> "R.font.${path.substringAfterLast('/').substringBeforeLast('.')}"
                path.contains("/res/raw/") -> "R.raw.${path.substringAfterLast('/').substringBeforeLast('.')}"
                else -> "assets/${path.substringAfterLast('/')}"
            }
            step(BuildEvent.Kind.DOWNLOAD, "Added your file ${a.name} as $ref")
            notes += "The user's file ${a.name} is in the project as $ref; use it where it fits."
        }
        return if (notes.isEmpty()) "" else "\n\nFiles from the user:\n" + notes.joinToString("\n") { "- $it" }
    }

    private suspend fun ask(prompt: String, attachments: List<com.aiapp.llm.Attachment> = emptyList()): String {
        val effort = if (req.effort == Effort.AUTO) Effort.HIGH else req.effort
        val system = (if (imported) ProjectTemplate.importedSystemPrompt else ProjectTemplate.systemPrompt(packageName)) +
            ProjectTemplate.webInstructions
        val cap = if (req.provider == ProviderId.OFFLINE) 3_000 else 8_000
        val working = mutableListOf(ChatMessage(Role.USER, prompt, attachments))
        var steps = 0
        while (true) {
            val sb = StringBuilder()
            val thinking = StringBuilder()
            var scanned = 0
            com.aiapp.llm.resilient(
                attempt = { model -> Providers.get(req.provider).stream(req.apiKey, model, working.toList(), effort, system) },
                model = req.model,
                fallback = { com.aiapp.llm.alternativeModel(req.provider, req.apiKey, req.model) },
                onRetry = { step(BuildEvent.Kind.STEP, it) },
            ).collect { raw ->
                if (raw.startsWith(com.aiapp.llm.THOUGHT)) {
                    thinking.append(raw.substring(1))
                    onProgress(-1f, "Thinking: " + thinking.toString().replace('\n', ' ').takeLast(160))
                    return@collect
                }
                if (thinking.isNotEmpty()) {
                    step(BuildEvent.Kind.THINKING, "Thought it through: " + thinking.toString().replace('\n', ' ').trim().take(140) + "…")
                    thinking.clear()
                    onProgress(null, "")
                }
                val chunk = raw
                sb.append(chunk)
                val nl = sb.lastIndexOf("\n")
                if (nl > scanned) {
                    sb.substring(scanned, nl).lines().forEach { line ->
                        fileStart.find(line.trim())?.let { step(BuildEvent.Kind.FILE, "Writing ${it.groupValues[1].trim('`', ' ').substringAfterLast('/')}") }
                    }
                    scanned = nl
                }
            }
            val reply = sb.toString()
            if (steps >= MAX_WEB_STEPS || reply.contains("=== FILE:")) return reply
            val search = WebTools.searchTag.find(reply)?.groupValues?.get(1)?.trim()
            val fetch = WebTools.fetchTag.find(reply)?.groupValues?.get(1)?.trim()
            val result = when {
                search != null -> {
                    step(BuildEvent.Kind.WEB, "Searching: $search")
                    "Web search results for \"$search\":\n\n" + WebTools.search(search).take(cap)
                }
                fetch != null -> {
                    step(BuildEvent.Kind.WEB, "Reading ${fetch.removePrefix("https://").take(60)}")
                    "Content of $fetch:\n\n" + WebTools.fetch(fetch).take(cap)
                }
                else -> return reply
            }
            steps++
            working += ChatMessage(Role.ASSISTANT, reply.trim())
            working += ChatMessage(Role.USER, "[Tool result]\n$result\n\nNow continue: write the files in the required format.")
        }
    }

    private fun firstPrompt() = """
        Build this Android app: ${req.description}
        App name: $appName
    """.trimIndent()

    private fun prioritized(hint: String): List<Map.Entry<String, String>> {
        val h = hint.lowercase()
        return files.entries.sortedBy { (path, _) ->
            val name = path.substringAfterLast('/').lowercase()
            when {
                h.contains(path.lowercase()) || h.contains(name) -> 0
                name.startsWith("build.gradle") || name.startsWith("settings.gradle") || name == "libs.versions.toml" -> 1
                name == "androidmanifest.xml" || name.endsWith(".kt") || name.endsWith(".java") -> 2
                else -> 3
            }
        }
    }

    private fun StringBuilder.appendFiles(hint: String = "") {
        var budget = if (req.provider == ProviderId.OFFLINE) 24_000 else 60_000
        prioritized(hint).forEach { (path, content) ->
            if (budget <= 0) return@forEach
            val c = content.take(budget)
            budget -= c.length
            appendLine("=== FILE: $path ===")
            appendLine(c)
            appendLine("=== END FILE ===")
        }
        if (deps.isNotEmpty()) appendLine("Extra dependencies: ${deps.joinToString()}")
    }

    private fun changePrompt() = buildString {
        appendLine("Here is an existing Android app, \"$appName\" (${if (imported) "uploaded by the user" else "originally: ${req.project?.description}"}).")
        appendLine()
        appendLine("Current files:")
        appendFiles(req.description)
        appendLine()
        appendLine("Make this change: ${req.description}")
        appendLine()
        appendLine("Reply with every file you change or add, in full, in the same format. Files you don't send stay as they are.")
        appendLine("To delete a file, send it with the single line: DELETE")
    }

    private fun fixPrompt(errors: String, notes: List<String>) = buildString {
        appendLine("The app below failed to build. Fix it.")
        appendLine("App request: ${req.project?.description ?: req.description}")
        if (req.project != null) appendLine("Latest change requested: ${req.description}")
        appendLine()
        appendLine("Build errors:")
        appendLine(errors)
        if (notes.isNotEmpty()) {
            appendLine()
            appendLine("Notes from the build tool:")
            notes.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("Current files:")
        appendFiles(errors)
        appendLine()
        appendLine("Reply with every file you change, in full, in the same format. Files you don't send stay as they are.")
        appendLine("To delete a file, send it with the single line: DELETE")
    }

    private suspend fun apply(output: String): List<String> {
        val notes = mutableListOf<String>()
        val parsed = parse(output)
        if (parsed.truncated) notes += "Your last reply was cut off. Send fewer or shorter files."
        parsed.files.forEach { (path, content) ->
            val allowed = if (imported) !path.startsWith(".github/") && !path.endsWith(".jar") else path.startsWith("app/src/main/")
            if (!allowed || path.contains("..") || path.startsWith("/")) {
                notes += if (imported) "Ignored $path: .github/ and the wrapper jar can't be changed." else "Ignored $path: only files under app/src/main/ are allowed."
            } else if (content.trim() == "DELETE") {
                if (files.remove(path) != null) pendingFiles[path] = null
            } else if (files[path] != content) {
                files[path] = content
                pendingFiles[path] = content.toByteArray()
            }
        }
        if (imported && parsed.deps.isNotEmpty()) notes += "Add libraries by editing the module's build.gradle(.kts) directly."
        if (!imported) parsed.deps.forEach { d ->
            val resolved = resolveDependency(d)
            if (resolved == null) {
                notes += "Dependency $d was not found on Maven; use a different library."
            } else if (resolved !in deps) {
                deps.removeAll { it.substringBeforeLast(':') == resolved.substringBeforeLast(':') }
                deps += resolved
                depsChanged = true
                step(BuildEvent.Kind.LIBRARY, "Added library $resolved")
            }
        }
        parsed.downloads.forEach { (url, path) -> download(url, path)?.let { notes += it } }
        return notes
    }

    private suspend fun waitForRun(repo: GitHubClient.Repo, sha: String): GitHubClient.Run? {
        var run: GitHubClient.Run? = null
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 35 * 60_000) {
            run = runCatching { gh.runFor(repo, sha, workflowFile) }.getOrNull() ?: run
            if (run == null && System.currentTimeMillis() - start > 3 * 60_000) return null
            if (run?.status == "completed") return run
            delay(10_000)
        }
        return run
    }

    private val repos = listOf(
        "https://dl.google.com/android/maven2",
        "https://repo1.maven.org/maven2",
        "https://jitpack.io",
    )

    private val safeHosts = listOf(
        "github.com", "raw.githubusercontent.com", "githubusercontent.com", "fonts.google.com",
        "fonts.gstatic.com", "upload.wikimedia.org", "commons.wikimedia.org", "repo1.maven.org",
        "dl.google.com", "jitpack.io", "unpkg.com", "cdn.jsdelivr.net",
    )

    private suspend fun httpText(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        }.getOrNull()
    }

    private suspend fun resolveDependency(coord: String): String? {
        val parts = coord.trim().split(":")
        if (parts.size < 2) return null
        val (group, artifact) = parts
        val version = parts.getOrNull(2)?.trim().orEmpty()
        val path = group.replace('.', '/') + "/" + artifact
        for (base in repos) {
            if (version.isNotEmpty() && version != "latest" && !version.contains('+')) {
                if (httpText("$base/$path/$version/$artifact-$version.pom") != null) return "$group:$artifact:$version"
            }
            val meta = httpText("$base/$path/maven-metadata.xml") ?: continue
            val latest = Regex("<release>([^<]+)</release>").find(meta)?.groupValues?.get(1)
                ?: Regex("<latest>([^<]+)</latest>").find(meta)?.groupValues?.get(1)
                ?: Regex("<version>([^<]+)</version>").findAll(meta).lastOrNull()?.groupValues?.get(1)
            if (latest != null) return "$group:$artifact:$latest"
        }
        return null
    }

    private val resourceName = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9]+)?$")

    private suspend fun download(url: String, path: String): String? {
        val okPath = path.startsWith("app/src/main/res/raw/") || path.startsWith("app/src/main/res/drawable/") ||
            path.startsWith("app/src/main/res/font/") || path.startsWith("app/src/main/assets/")
        if (!okPath || path.contains("..")) return "Skipped download to $path: use res/raw, res/drawable, res/font or assets."
        if (!path.startsWith("app/src/main/assets/") && !resourceName.matches(path.substringAfterLast('/'))) {
            return "Skipped $path: resource names must be lowercase letters, digits and underscores."
        }
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
        if (host == null || !url.startsWith("https://")) return "Skipped $url: only https links work."
        val safe = safeHosts.any { host == it || host.endsWith(".$it") }
        if (!safe && !req.allowOtherSites) {
            step(BuildEvent.Kind.DOWNLOAD, "Blocked download from $host (turn on \"Allow other sites\")")
            return "Download from $host was blocked. Use a file from GitHub, Google Fonts or Wikimedia, or draw it in code."
        }
        step(BuildEvent.Kind.DOWNLOAD, "Downloading ${path.substringAfterLast('/')} from $host")
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val len = r.body?.contentLength() ?: -1
                    if (len > MAX_DOWNLOAD) return@use null
                    r.body?.bytes()?.takeIf { it.size <= MAX_DOWNLOAD }
                }
            }.getOrNull()
        } ?: return "Download failed or was too large (max 15 MB): $url"
        pendingFiles[path] = bytes
        return null
    }

    data class Parsed(
        val files: List<Pair<String, String>>,
        val deps: List<String>,
        val downloads: List<Pair<String, String>>,
        val truncated: Boolean,
    )

    companion object {
        private const val MAX_DOWNLOAD = 15L * 1024 * 1024
        private const val MAX_WEB_STEPS = 3
        private val fileStart = Regex("^=+\\s*FILE:\\s*(.+?)\\s*=*\\s*$")
        private val fileEnd = Regex("^=+\\s*END FILE\\s*=*\\s*$")
        private val depLine = Regex("^=+\\s*DEPENDENCY:\\s*(.+?)\\s*=*\\s*$")
        private val downloadLine = Regex("^=+\\s*DOWNLOAD:\\s*(\\S+)\\s*->\\s*(\\S+?)\\s*=*\\s*$")

        fun parse(text: String): Parsed {
            val files = mutableListOf<Pair<String, String>>()
            val deps = mutableListOf<String>()
            val downloads = mutableListOf<Pair<String, String>>()
            var path: String? = null
            val body = StringBuilder()
            for (raw in text.lines()) {
                val line = raw.trim()
                val current = path
                if (current == null) {
                    val start = fileStart.find(line)
                    val dep = depLine.find(line)
                    val dl = downloadLine.find(line)
                    when {
                        start != null -> {
                            path = start.groupValues[1].trim('`', ' ')
                            body.clear()
                        }
                        dep != null -> deps.add(dep.groupValues[1].trim('`', ' '))
                        dl != null -> downloads.add(dl.groupValues[1] to dl.groupValues[2])
                    }
                } else if (fileEnd.matches(line)) {
                    files.add(current to stripFences(body.toString()))
                    path = null
                } else {
                    body.appendLine(raw)
                }
            }
            return Parsed(files, deps, downloads, truncated = path != null)
        }

        private fun stripFences(s: String): String {
            val lines = s.trimEnd().lines().toMutableList()
            while (lines.isNotEmpty() && lines.first().isBlank()) lines.removeAt(0)
            if (lines.firstOrNull()?.trim()?.startsWith("```") == true) lines.removeAt(0)
            if (lines.lastOrNull()?.trim() == "```") lines.removeAt(lines.lastIndex)
            return lines.joinToString("\n") + "\n"
        }
    }
}
