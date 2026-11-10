package com.aiapp.builder

import com.aiapp.llm.Http
import com.aiapp.llm.ProviderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.zip.ZipInputStream

/** The bits of the GitHub REST API that Build Mode and Codespace Screen need. */
class GitHubClient(private val token: String) {
    private val api = "https://api.github.com"
    private val jsonType = "application/json".toMediaType()

    private fun request(url: String) = Request.Builder().url(url)
        .header("Authorization", "Bearer ${token.trim()}")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "AI-App-Android/1.0")

    /** Retries a request through brief network drops or transient 5xx responses (2 s, 5 s, 10 s, 20 s). */
    private suspend fun <T> retrying(block: suspend () -> T): T {
        val waits = listOf(2_000L, 5_000L, 10_000L, 20_000L)
        for (w in waits) {
            try {
                return block()
            } catch (e: java.io.IOException) {
                kotlinx.coroutines.delay(w)
            } catch (e: ProviderException) {
                if (e.code == 500 || e.code == 502 || e.code == 503 || e.code == 504 || e.code == 408) {
                    kotlinx.coroutines.delay(w)
                } else {
                    throw e
                }
            }
        }
        return block()
    }

    private suspend fun call(method: String, path: String, body: JsonElement? = null): JsonElement =
        retrying { callOnce(method, path, body) }

    private suspend fun callOnce(method: String, path: String, body: JsonElement? = null): JsonElement =
        withContext(Dispatchers.IO) {
            val rb = body?.toString()?.toRequestBody(jsonType) ?: if (method == "POST") "{}".toRequestBody(jsonType) else null
            val req = request("$api$path").method(method, rb).build()
            Http.client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching {
                        val root = Http.json.parseToJsonElement(text).jsonObject
                        val baseMsg = (root["message"] as? JsonPrimitive)?.contentOrNull
                        val firstErr = ((root["errors"] as? JsonArray)?.firstOrNull() as? JsonObject)
                            ?.let { (it["message"] as? JsonPrimitive)?.contentOrNull }
                        if (!firstErr.isNullOrBlank()) "$baseMsg ($firstErr)" else baseMsg
                    }.getOrNull() ?: "HTTP ${resp.code}"
                    throw ProviderException(resp.code, "GitHub: $msg")
                }
                if (text.isBlank()) JsonObject(emptyMap()) else Http.json.parseToJsonElement(text)
            }
        }

    private fun JsonElement.str(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonElement.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

    suspend fun login(): String = call("GET", "/user").str("login") ?: throw ProviderException(401, "GitHub: bad token")

    data class Repo(val owner: String, val name: String, val branch: String, val url: String)

    data class Codespace(
        val name: String,
        val displayName: String,
        val state: String,
        val webUrl: String,
        val repoFullName: String,
        val branch: String = "main",
    )

    private fun parseCodespace(o: JsonObject): Codespace? {
        val name = o.str("name") ?: return null
        val display = o.str("display_name") ?: name
        val state = o.str("state") ?: "Unknown"
        val webUrl = o.str("web_url") ?: "https://$name.github.dev"
        val repoName = o.obj("repository")?.str("full_name").orEmpty()
        val branch = o.obj("git_status")?.str("ref") ?: "main"
        return Codespace(
            name = name,
            displayName = display,
            state = state,
            webUrl = webUrl,
            repoFullName = repoName,
            branch = branch,
        )
    }

    /** Lists the authenticated user's GitHub Codespaces. */
    suspend fun listCodespaces(): List<Codespace> {
        val res = call("GET", "/user/codespaces?per_page=30")
        val arr = (res as? JsonObject)?.get("codespaces") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonObject)?.let(::parseCodespace) }
    }

    /** Starts an existing stopped GitHub Codespace and returns its updated metadata. */
    suspend fun startCodespace(codespaceName: String): Codespace {
        val res = call("POST", "/user/codespaces/$codespaceName/start")
        return (res as? JsonObject)?.let(::parseCodespace)
            ?: Codespace(codespaceName, codespaceName, "Available", "https://$codespaceName.github.dev", "")
    }

    /** Stops a running GitHub Codespace. */
    suspend fun stopCodespace(codespaceName: String): Codespace {
        val res = call("POST", "/user/codespaces/$codespaceName/stop")
        return (res as? JsonObject)?.let(::parseCodespace)
            ?: Codespace(codespaceName, codespaceName, "ShuttingDown", "https://$codespaceName.github.dev", "")
    }

    /** Creates or resumes a GitHub Codespace for [owner]/[repo] on [branch]. */
    suspend fun createCodespace(owner: String, repo: String, branch: String = "main"): Codespace {
        val body = buildJsonObject {
            put("ref", branch)
        }
        val res = call("POST", "/repos/$owner/$repo/codespaces", body)
        return (res as? JsonObject)?.let(::parseCodespace)
            ?: throw ProviderException(500, "GitHub couldn't create the Codespace.")
    }

    /** Lists the user's recent repositories so they can launch a Codespace for any repo. */
    suspend fun listRepos(): List<Repo> {
        val res = call("GET", "/user/repos?sort=updated&per_page=35")
        val arr = res as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val owner = o.obj("owner")?.str("login") ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            val branch = o.str("default_branch") ?: "main"
            val url = o.str("html_url") ?: "https://github.com/$owner/$name"
            Repo(owner, name, branch, url)
        }
    }

    /**
     * Creates a new GitHub repository.
     * - Strips newlines and control characters from [description] (GitHub rejects descriptions with `\n` with HTTP 422).
     * - Sanitizes [name] and automatically retries with a unique suffix if a repo with the same name already exists.
     */
    suspend fun createRepo(name: String, description: String, isPrivate: Boolean = false): Repo {
        val cleanName = name.trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-', '.')
            .ifBlank { "ai-app-project" }
            .take(80)
        // GitHub API strictly forbids control characters (including \n and \r) in repository descriptions
        val cleanDesc = description
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("[\\p{Cntrl}]"), "")
            .trim()
            .take(240)

        val candidates = listOf(
            cleanName,
            "${cleanName.take(65)}-${(System.currentTimeMillis() / 1000) % 100000}",
        )
        var lastErr: Throwable? = null
        for (candidateName in candidates) {
            try {
                val body = buildJsonObject {
                    put("name", candidateName)
                    put("description", cleanDesc)
                    put("private", isPrivate)
                    put("auto_init", true)
                }
                val r = call("POST", "/user/repos", body)
                return Repo(
                    owner = r.obj("owner")?.let { (it["login"] as? JsonPrimitive)?.contentOrNull } ?: login(),
                    name = r.str("name") ?: candidateName,
                    branch = r.str("default_branch") ?: "main",
                    url = r.str("html_url") ?: "https://github.com",
                )
            } catch (e: ProviderException) {
                lastErr = e
                // If HTTP 422 (e.g., name already exists), try the next candidate name
                if (e.code != 422) throw e
            }
        }
        throw (lastErr ?: ProviderException(422, "GitHub: Could not create repository $cleanName"))
    }

    /**
     * Commits [files] (path → content, or null to delete) on top of the branch in one commit.
     * Returns the commit SHA.
     */
    suspend fun commit(
        repo: Repo,
        files: Map<String, ByteArray?>,
        message: String,
        /** Big files uploaded straight from storage, so they never have to fit in memory. */
        diskFiles: Map<String, java.io.File> = emptyMap(),
        /** (files done, files total, bytes done, bytes total). */
        onProgress: (Int, Int, Long, Long) -> Unit = { _, _, _, _ -> },
    ): String {
        val base = "/repos/${repo.owner}/${repo.name}"
        // auto_init can take a moment before the branch exists.
        var ref: JsonElement? = null
        for (i in 0 until 10) {
            ref = runCatching { call("GET", "$base/git/ref/heads/${repo.branch}") }.getOrNull()
            if (ref != null) break
            kotlinx.coroutines.delay(1500)
        }
        val headSha = ref?.obj("object")?.let { (it["sha"] as? JsonPrimitive)?.contentOrNull }
            ?: throw ProviderException(500, "GitHub: branch ${repo.branch} not found")
        val baseTree = call("GET", "$base/git/commits/$headSha").obj("tree")
            ?.let { (it["sha"] as? JsonPrimitive)?.contentOrNull }

        var done = 0
        val totalFiles = files.size + diskFiles.size
        val totalBytes = files.values.sumOf { (it?.size ?: 0).toLong() } + diskFiles.values.sumOf { it.length() }
        var bytesDone = 0L
        onProgress(0, totalFiles, 0, totalBytes)
        val entries = files.map { (path, bytes) ->
            val blob = bytes?.let {
                call("POST", "$base/git/blobs", buildJsonObject {
                    put("content", Base64.getEncoder().encodeToString(it))
                    put("encoding", "base64")
                }).str("sha")!!
            }
            bytesDone += bytes?.size ?: 0
            onProgress(++done, totalFiles, bytesDone, totalBytes)
            path to blob
        }
        val diskEntries = diskFiles.map { (path, file) ->
            val start = bytesDone
            val sha = blobFromFile(base, file) { sent -> onProgress(done, totalFiles, start + sent, totalBytes) }
            bytesDone = start + file.length()
            onProgress(++done, totalFiles, bytesDone, totalBytes)
            path to sha
        }
        val allEntries = entries + diskEntries
        val tree = call("POST", "$base/git/trees", buildJsonObject {
            if (baseTree != null) put("base_tree", baseTree)
            putJsonArray("tree") {
                allEntries.forEach { (path, sha) ->
                    addJsonObject {
                        put("path", path)
                        put("mode", if (path == "gradlew" || path.endsWith("/gradlew") || path.endsWith(".sh")) "100755" else "100644")
                        put("type", "blob")
                        if (sha == null) put("sha", JsonNull) else put("sha", sha)
                    }
                }
            }
        }).str("sha")!!
        val commit = call("POST", "$base/git/commits", buildJsonObject {
            put("message", message)
            put("tree", tree)
            putJsonArray("parents") { add(JsonPrimitive(headSha)) }
        }).str("sha")!!
        call("PATCH", "$base/git/refs/heads/${repo.branch}", buildJsonObject { put("sha", commit) })
        return commit
    }

    /** Creates a blob by streaming the file as base64 inside the JSON body (no big strings in memory). */
    private suspend fun blobFromFile(base: String, file: java.io.File, onBytes: (Long) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val body = object : okhttp3.RequestBody() {
            override fun contentType() = jsonType
            override fun writeTo(sink: okio.BufferedSink) {
                sink.writeUtf8("{\"encoding\":\"base64\",\"content\":\"")
                val out = Base64.getEncoder().wrap(object : java.io.OutputStream() {
                    override fun write(b: Int) { sink.writeByte(b) }
                    override fun write(b: ByteArray, off: Int, len: Int) { sink.write(b, off, len) }
                    override fun close() = Unit
                })
                file.inputStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    var sent = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        if (sent - last > 256 * 1024) {
                            last = sent
                            onBytes(sent)
                        }
                    }
                }
                out.close()
                sink.writeUtf8("\"}")
            }
        }
        val req = request("$api$base/git/blobs").post(body).build()
        retrying { withContext(Dispatchers.IO) { Http.client.newCall(req).execute() } }.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ProviderException(r.code, "GitHub: couldn't upload ${file.name} (HTTP ${r.code})")
            (Http.json.parseToJsonElement(text).jsonObject["sha"] as? JsonPrimitive)?.contentOrNull
                ?: throw ProviderException(500, "GitHub: upload of ${file.name} failed")
        }
    }

    data class Run(val id: Long, val status: String, val conclusion: String?, val url: String)

    /** The run of [workflowFile] (e.g. "build.yml") for [sha]; other workflows in the repo are ignored. */
    suspend fun runFor(repo: Repo, sha: String, workflowFile: String? = null): Run? {
        val runs = call("GET", "/repos/${repo.owner}/${repo.name}/actions/runs?head_sha=$sha&per_page=20")
            .let { (it as? JsonObject)?.get("workflow_runs") as? JsonArray } ?: return null
        val r = runs.mapNotNull { it as? JsonObject }.firstOrNull { run ->
            workflowFile == null || (run["path"] as? JsonPrimitive)?.contentOrNull?.endsWith(workflowFile) == true
        } ?: return null
        return Run(
            id = (r["id"] as? JsonPrimitive)?.longOrNull ?: return null,
            status = (r["status"] as? JsonPrimitive)?.contentOrNull ?: "queued",
            conclusion = (r["conclusion"] as? JsonPrimitive)?.contentOrNull,
            url = (r["html_url"] as? JsonPrimitive)?.contentOrNull ?: repo.url,
        )
    }

    /**
     * Why a build failed: the error lines from the run's logs. If the logs can't be downloaded, falls
     * back to the run's error annotations (the workflow reports compile errors there too).
     */
    suspend fun failureSummary(repo: Repo, runId: Long): String {
        val fromLogs = runCatching {
            retrying {
                withContext(Dispatchers.IO) {
                    val req = request("$api/repos/${repo.owner}/${repo.name}/actions/runs/$runId/logs").get().build()
                    val lines = mutableListOf<String>()
                    Http.client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@withContext null
                        ZipInputStream(resp.body!!.byteStream()).use { zip ->
                            while (true) {
                                val entry = zip.nextEntry ?: break
                                if (entry.isDirectory) continue
                                zip.bufferedReader().readLines().forEach { lines += it }
                            }
                        }
                    }
                    extractErrors(lines)
                }
            }
        }.getOrNull()
        if (!fromLogs.isNullOrBlank()) return fromLogs
        val notes = runCatching {
            val jobs = (call("GET", "/repos/${repo.owner}/${repo.name}/actions/runs/$runId/jobs") as? JsonObject)?.get("jobs") as? JsonArray
            jobs.orEmpty().flatMap { j ->
                val id = ((j as? JsonObject)?.get("id") as? JsonPrimitive)?.longOrNull ?: return@flatMap emptyList()
                val ann = call("GET", "/repos/${repo.owner}/${repo.name}/check-runs/$id/annotations") as? JsonArray
                ann.orEmpty().mapNotNull { ((it as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull }
            }.filter { !it.contains("Node.js") && !it.contains("deprecated") && !it.contains("ubuntu-latest") }
        }.getOrNull().orEmpty()
        return notes.joinToString("\n").ifBlank { "The build failed, but its error details couldn't be read." }.take(6000)
    }

    suspend fun latestApkUrl(repo: Repo): String? {
        val rel = runCatching { call("GET", "/repos/${repo.owner}/${repo.name}/releases/latest") }.getOrNull() ?: return null
        val assets = (rel as? JsonObject)?.get("assets") as? JsonArray ?: return null
        return assets.mapNotNull { (it as? JsonObject)?.get("browser_download_url") as? JsonPrimitive }
            .map { it.content }.firstOrNull { it.endsWith(".apk") }
    }

    companion object {
        private val timestamp = Regex("^\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z ")

        /** Keeps compiler errors and Gradle's "What went wrong" section, capped so it fits a prompt. */
        fun extractErrors(raw: List<String>): String {
            val lines = raw.map { it.replace(timestamp, "") }
            val picked = LinkedHashSet<String>()
            lines.forEachIndexed { i, l ->
                val t = l.trim()
                if (t.startsWith("e: ") || t.contains("error:", ignoreCase = true) ||
                    t.startsWith("ERROR:") || t.contains("Unresolved reference") ||
                    t.contains("AAPT: error")
                ) picked += t
                if (t.startsWith("* What went wrong")) {
                    lines.drop(i + 1).take(8).forEach { picked += it.trim() }
                }
            }
            val text = picked.filter { it.isNotBlank() }.joinToString("\n")
            return text.ifBlank { lines.takeLast(40).joinToString("\n") }.take(6000)
        }
    }
}
