package com.aiapp.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.aiapp.builder.Project
import com.aiapp.builder.ProjectTemplate
import com.aiapp.builder.ZipImport
import com.aiapp.llm.Attachment
import com.aiapp.llm.ChatMessage
import com.aiapp.llm.Http
import com.aiapp.llm.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Saves files and generated Android Studio project archives into the phone's Downloads/AIApp folder. */
object Downloads {
    private const val MAX_BYTES = 50L * 1024 * 1024

    suspend fun save(context: Context, url: String): String = withContext(Dispatchers.IO) {
        runCatching {
            Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@use "Download failed (HTTP ${r.code})."
                val body = r.body ?: return@use "Download failed (empty)."
                if (body.contentLength() > MAX_BYTES) return@use "File is larger than 50 MB, so it wasn't downloaded."
                val mime = body.contentType()?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream"
                val name = url.substringBefore('?').substringAfterLast('/').ifBlank { "download" }
                    .replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
                val bytes = body.bytes()
                if (bytes.size > MAX_BYTES) return@use "File is larger than 50 MB, so it wasn't downloaded."
                saveBytesSync(context, name, mime, bytes)
            }
        }.getOrElse { "Download failed: ${it.message}" }
    }

    /** Exports a generated or modified [Project]'s complete Android Studio source code as a .zip into Downloads/AIApp. */
    suspend fun exportProjectZip(context: Context, project: Project): String = withContext(Dispatchers.IO) {
        runCatching {
            val safeName = project.appName.replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-').ifBlank { "android-app" }
            val zipFileName = "$safeName-android-studio-src.zip"
            val allFiles = if (project.imported) {
                project.files
            } else {
                ProjectTemplate.files(project.appName, project.packageName, project.deps) + project.files
            }
            if (allFiles.isEmpty()) return@withContext "No source files generated yet."
            val baos = ByteArrayOutputStream()
            ZipOutputStream(baos).use { zos ->
                for ((path, content) in allFiles.toSortedMap()) {
                    val cleanPath = path.trimStart('/')
                    zos.putNextEntry(ZipEntry("$safeName/$cleanPath"))
                    zos.write(content.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }
            saveBytesSync(context, zipFileName, "application/zip", baos.toByteArray())
        }.getOrElse { "Export failed: ${it.message}" }
    }

    /** Copies an existing local [Attachment] (.zip or file) into the phone's public Downloads/AIApp folder. */
    suspend fun saveAttachmentToDownloads(context: Context, attachment: Attachment): String = withContext(Dispatchers.IO) {
        runCatching {
            val f = File(attachment.path)
            if (!f.exists()) return@withContext "File not found."
            val bytes = f.readBytes()
            saveBytesSync(context, attachment.name, attachment.mime.ifBlank { "application/zip" }, bytes)
        }.getOrElse { "Save failed: ${it.message}" }
    }

    /** Shares or opens the system Save/Share sheet for a local [Attachment] (.zip). */
    fun shareAttachment(context: Context, attachment: Attachment) {
        runCatching {
            val f = File(attachment.path)
            if (!f.exists()) return
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = attachment.mime.ifBlank { "application/zip" }
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, attachment.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Save or Share ${attachment.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * Compiles code written in the Chat conversation into a complete, ready-to-compile
     * Android Studio Gradle project `.zip`!
     *
     * - Automatically merges any uploaded base `.zip` from earlier in the conversation, OR wraps the code
     *   in `ProjectTemplate` (`settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `app/build.gradle.kts`,
     *   `AndroidManifest.xml`, and `.github/workflows/build.yml`) so it can be uploaded directly to the Build tab
     *   or opened in Android Studio to compile into an APK without missing files.
     * - Saves the `.zip` both as an in-chat `Attachment` AND directly into `Downloads/AIApp/` on the phone.
     */
    suspend fun compileChatToSourceZip(
        context: Context,
        messages: List<ChatMessage>,
        latestReply: String = "",
        preferredAppName: String? = null,
        preferredPackageName: String? = null,
        sourceApkPath: String? = null,
    ): Pair<Attachment, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val allTexts = buildList {
                messages.forEach { m ->
                    if (m.role == Role.ASSISTANT && m.text.isNotBlank()) add(m.text)
                }
                if (latestReply.isNotBlank()) add(latestReply)
            }
            if (allTexts.isEmpty()) return@withContext null

            // Check if the user previously attached an Android Studio .zip in this chat
            val priorUserZip = messages.flatMap { it.attachments }.lastOrNull { it.isZip && File(it.path).exists() }
            val importedBase = priorUserZip?.let { runCatching { ZipImport.read(File(it.path), it.name) }.getOrNull() }

            // Extract code files from the latest reply first; if empty (e.g. user said "zip the file and give me the zip here"),
            // extract across the earlier assistant replies in the chat!
            var extracted = if (latestReply.isNotBlank()) extractCodeFilesFromText(latestReply) else emptyMap()
            if (extracted.isEmpty()) {
                val merged = linkedMapOf<String, String>()
                allTexts.forEach { txt ->
                    merged.putAll(extractCodeFilesFromText(txt))
                }
                extracted = merged
            }

            // If we have neither extracted code nor a base zip, synthesize a minimal MainActivity if requested
            if (extracted.isEmpty() && importedBase == null) return@withContext null

            // Detect package name and app name
            val detectedPackage = preferredPackageName?.takeIf { it.isNotBlank() }
                ?: extracted.values.firstNotNullOfOrNull { code ->
                    Regex("^\\s*package\\s+([a-zA-Z0-9_.]+)", RegexOption.MULTILINE).find(code)?.groupValues?.get(1)
                } ?: "com.example.aiapp"

            val detectedAppName = preferredAppName?.takeIf { it.isNotBlank() }
                ?: importedBase?.name
                ?: detectAppNameFromChat(messages, latestReply, detectedPackage)

            val safeFolder = detectedAppName.replace(Regex("[^A-Za-z0-9._-]"), "").ifBlank { "AndroidApp" }
            val zipName = "$safeFolder-android-studio-src.zip"

            // Normalize paths of extracted files so any bare filename like "MainActivity.kt" goes into app/src/main/java/<pkg>/
            val pkgPath = detectedPackage.replace('.', '/')
            val normalizedExtracted = linkedMapOf<String, String>()
            for ((rawPath, code) in extracted) {
                val clean = rawPath.trim().trimStart('/', '\\')
                val normalizedPath = when {
                    clean.equals("settings.gradle.kts", true) || clean.equals("settings.gradle", true) -> clean
                    clean.equals("build.gradle.kts", true) || clean.equals("build.gradle", true) -> {
                        if (code.contains("com.android.application") && code.contains("namespace")) "app/build.gradle.kts" else clean
                    }
                    clean.equals("gradle.properties", true) -> "gradle.properties"
                    clean.endsWith("AndroidManifest.xml", true) -> "app/src/main/AndroidManifest.xml"
                    clean.startsWith("app/") || clean.startsWith("gradle/") || clean.startsWith(".github/") -> clean
                    clean.startsWith("src/main/") -> "app/$clean"
                    clean.endsWith(".kt") || clean.endsWith(".java") -> {
                        val filePkg = Regex("^\\s*package\\s+([a-zA-Z0-9_.]+)", RegexOption.MULTILINE)
                            .find(code)?.groupValues?.get(1)?.replace('.', '/') ?: pkgPath
                        val fileName = clean.substringAfterLast('/')
                        "app/src/main/java/$filePkg/$fileName"
                    }
                    clean.endsWith(".xml") -> {
                        val fileName = clean.substringAfterLast('/')
                        if (code.contains("<manifest")) "app/src/main/AndroidManifest.xml"
                        else if (code.contains("<resources")) "app/src/main/res/values/$fileName"
                        else "app/src/main/res/layout/$fileName"
                    }
                    else -> "app/src/main/$clean"
                }
                normalizedExtracted[normalizedPath] = code
            }

            // Ensure there is a MainActivity.kt if this is a fresh template project
            val finalFiles = linkedMapOf<String, ByteArray>()
            if (importedBase != null) {
                importedBase.files.forEach { (k, v) -> finalFiles[k] = v }
                normalizedExtracted.forEach { (k, v) -> finalFiles[k] = v.toByteArray(Charsets.UTF_8) }
            } else {
                val templateMap = ProjectTemplate.files(detectedAppName, detectedPackage, emptyList())
                templateMap.forEach { (k, v) -> finalFiles[k] = v.toByteArray(Charsets.UTF_8) }
                finalFiles["app/src/main/AndroidManifest.xml"] =
                    ProjectTemplate.defaultManifest(detectedAppName).toByteArray(Charsets.UTF_8)

                // Bundle reusable resources (drawables, fonts, raw media, assets) from any attached or specified APK
                val apkFileToBundle = sourceApkPath?.let { File(it) }?.takeIf { it.exists() }
                    ?: messages.flatMap { it.attachments }.lastOrNull { it.isApk && File(it.path).exists() }?.let { File(it.path) }
                if (apkFileToBundle != null) {
                    val extractedRes = com.aiapp.builder.ReaEngine.extractReusableResources(apkFileToBundle)
                    finalFiles.putAll(extractedRes.files)
                }

                // If extracted Kotlin code has no MainActivity.kt, map the primary Kotlin file to MainActivity.kt
                val hasMainActivity = normalizedExtracted.keys.any { it.endsWith("/MainActivity.kt") } ||
                    normalizedExtracted.values.any { it.contains("class MainActivity") }
                if (!hasMainActivity) {
                    val firstKt = normalizedExtracted.entries.firstOrNull { it.key.endsWith(".kt") }
                    if (firstKt != null) {
                        val code = firstKt.value
                        val withPkg = if (code.contains(Regex("^\\s*package\\s+", RegexOption.MULTILINE))) {
                            code
                        } else {
                            "package $detectedPackage\n\n$code"
                        }
                        normalizedExtracted.remove(firstKt.key)
                        normalizedExtracted["app/src/main/java/$pkgPath/MainActivity.kt"] = withPkg
                    }
                } else {
                    // Make sure every .kt file has a package statement matching its folder
                    for ((k, v) in normalizedExtracted.toMap()) {
                        if (k.endsWith(".kt") && !v.contains(Regex("^\\s*package\\s+", RegexOption.MULTILINE))) {
                            normalizedExtracted[k] = "package $detectedPackage\n\n$v"
                        }
                    }
                }

                normalizedExtracted.forEach { (k, v) -> finalFiles[k] = v.toByteArray(Charsets.UTF_8) }
            }

            val baos = ByteArrayOutputStream()
            ZipOutputStream(baos).use { zos ->
                for ((path, bytes) in finalFiles.toSortedMap()) {
                    val cleanPath = path.trimStart('/')
                    zos.putNextEntry(ZipEntry("$safeFolder/$cleanPath"))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
            val zipBytes = baos.toByteArray()

            // 1. Save to app's internal attachments folder so it can be attached to the ChatMessage and sent to Build tab
            val dir = File(context.filesDir, "attachments").apply { mkdirs() }
            val localFile = File(dir, "${System.currentTimeMillis()}_$zipName")
            localFile.writeBytes(zipBytes)

            // 2. Also save directly to phone's public Downloads/AIApp folder
            val downloadMsg = saveBytesSync(context, zipName, "application/zip", zipBytes)

            val attachment = Attachment(
                name = zipName,
                mime = "application/zip",
                path = localFile.absolutePath,
                text = "Android Studio Project ($safeFolder) · ${finalFiles.size} files (${zipBytes.size / 1024} KB)",
            )
            attachment to downloadMsg
        }.getOrNull()
    }

    private fun detectAppNameFromChat(messages: List<ChatMessage>, latestReply: String, pkg: String): String {
        val combined = (messages.map { it.text } + latestReply).joinToString("\n")
        Regex("rootProject\\.name\\s*=\\s*[\"']([^\"']+)[\"']").find(combined)?.groupValues?.get(1)?.let {
            if (it.isNotBlank()) return it
        }
        Regex("`([A-Za-z0-9_]+App)`").find(combined)?.groupValues?.get(1)?.let {
            if (it.isNotBlank()) return it
        }
        val pkgTail = pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        if (pkgTail.isNotBlank() && pkgTail.lowercase() !in setOf("aiapp", "example", "app", "myapplication")) {
            return "${pkgTail}App"
        }
        return "CalculatorApp"
    }

    /**
     * Extracts source files from an LLM message supporting:
     * 1. `=== FILE: path === ... === END FILE ===`
     * 2. `<file path="path">...</file>`
     * 3. Standard Markdown fenced code blocks (` ```kotlin `, ` ```xml `, ` ```kts `, ` ```gradle `, ` ```java `)
     */
    fun extractCodeFilesFromText(text: String): Map<String, String> {
        val out = linkedMapOf<String, String>()

        // 1. === FILE: path === ... === END FILE ===
        val markerRegex = Regex("===\\s*FILE:\\s*([^=\\n]+?)\\s*===\\s*\\n([\\s\\S]*?)(?:===\\s*END FILE\\s*===|$)")
        markerRegex.findAll(text).forEach { m ->
            val path = m.groupValues[1].trim()
            val body = m.groupValues[2].trim()
                .removePrefix("```kotlin").removePrefix("```java").removePrefix("```xml").removePrefix("```kts").removePrefix("```")
                .removeSuffix("```").trim()
            if (path.isNotBlank() && body.isNotBlank()) {
                out[path] = body
            }
        }

        // 2. <file path="...">...</file>
        val xmlTagRegex = Regex("<file\\s+path=[\"']([^\"']+)[\"']\\s*>([\\s\\S]*?)</file>", RegexOption.IGNORE_CASE)
        xmlTagRegex.findAll(text).forEach { m ->
            val path = m.groupValues[1].trim()
            val body = m.groupValues[2].trim()
                .removePrefix("```kotlin").removePrefix("```java").removePrefix("```xml").removePrefix("```kts").removePrefix("```")
                .removeSuffix("```").trim()
            if (path.isNotBlank() && body.isNotBlank()) {
                out[path] = body
            }
        }
        if (out.isNotEmpty()) return out

        // 3. Standard Markdown fenced code blocks
        val fenceRegex = Regex("```([a-zA-Z0-9_-]*)\\s*\\n([\\s\\S]*?)```")
        var ktCounter = 1
        fenceRegex.findAll(text).forEach { m ->
            val lang = m.groupValues[1].trim().lowercase()
            val rawBody = m.groupValues[2].trim()
            if (rawBody.isBlank()) return@forEach

            // Skip pure ASCII directory trees like:
            // CalculatorApp/
            // ├── settings.gradle.kts
            if (rawBody.contains("├──") || rawBody.contains("└──") || rawBody.lines().size < 3 && !rawBody.contains("{") && !rawBody.contains("=")) {
                return@forEach
            }

            // Look at the 2 lines immediately preceding the code block in `text` for a filename hint
            val beforeText = text.substring(0, m.range.first).trimEnd()
            val prevLines = beforeText.lines().takeLast(2).joinToString(" ")
            val pathFromHeading = Regex("([a-zA-Z0-9_./-]+\\.(?:kt|kts|java|xml|gradle|properties|toml|pro))")
                .findAll(prevLines).lastOrNull()?.groupValues?.get(1)

            // Or look at the first line inside the code block for a comment like `// FILE: ...` or `// app/src/...`
            val firstLine = rawBody.lines().firstOrNull()?.trim().orEmpty()
            val pathFromComment = Regex("^(?://|#|<!--)\\s*(?:FILE:\\s*|path:\\s*)?([a-zA-Z0-9_./-]+\\.(?:kt|kts|java|xml|gradle|properties|toml|pro))", RegexOption.IGNORE_CASE)
                .find(firstLine)?.groupValues?.get(1)

            val cleanedBody = if (pathFromComment != null) {
                rawBody.lines().drop(1).joinToString("\n").trim()
            } else {
                rawBody
            }

            val explicitPath = pathFromComment ?: pathFromHeading
            if (explicitPath != null) {
                out[explicitPath] = cleanedBody
                return@forEach
            }

            // Otherwise infer from language or content
            when {
                lang in setOf("kotlin", "kt") || cleanedBody.contains("import androidx.compose") || cleanedBody.contains("class MainActivity") || cleanedBody.contains("@Composable") -> {
                    val className = Regex("\\b(?:class|object)\\s+([A-Za-z0-9_]+)").find(cleanedBody)?.groupValues?.get(1)
                    val fileName = when {
                        cleanedBody.contains("class MainActivity") || cleanedBody.contains("setContent") -> "MainActivity.kt"
                        className != null -> "$className.kt"
                        ktCounter == 1 -> "MainActivity.kt"
                        else -> "Screen$ktCounter.kt"
                    }
                    ktCounter++
                    out[fileName] = cleanedBody
                }
                lang == "xml" || cleanedBody.startsWith("<?xml") || cleanedBody.startsWith("<manifest") || cleanedBody.startsWith("<resources") -> {
                    val fileName = when {
                        cleanedBody.contains("<manifest") -> "app/src/main/AndroidManifest.xml"
                        cleanedBody.contains("<resources") -> "app/src/main/res/values/strings.xml"
                        else -> "app/src/main/res/layout/activity_main.xml"
                    }
                    out[fileName] = cleanedBody
                }
                lang in setOf("kts", "gradle") || cleanedBody.contains("rootProject.name") || cleanedBody.contains("compileSdk") -> {
                    val fileName = when {
                        cleanedBody.contains("rootProject.name") -> "settings.gradle.kts"
                        cleanedBody.contains("compileSdk") || cleanedBody.contains("applicationId") -> "app/build.gradle.kts"
                        else -> "build.gradle.kts"
                    }
                    out[fileName] = cleanedBody
                }
            }
        }
        return out
    }

    private fun saveBytesSync(context: Context, name: String, mime: String, bytes: ByteArray): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AIApp")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "Couldn't create the file in Downloads."
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "AIApp").apply { mkdirs() }
            File(dir, name).writeBytes(bytes)
        }
        return "Saved $name (${bytes.size / 1024} KB) to Downloads/AIApp on your phone."
    }
}
