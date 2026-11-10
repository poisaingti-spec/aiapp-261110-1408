package com.aiapp.builder

import java.io.File
import java.util.zip.ZipInputStream

/** Reads an uploaded Android Studio project from a .zip. */
object ZipImport {
    private const val MAX_FILE = 95L * 1024 * 1024 // GitHub refuses files over 100 MB
    private const val MAX_FILES = 5000
    /** Files bigger than this are kept on storage instead of in memory. */
    private const val IN_MEMORY = 1L * 1024 * 1024

    private val skipDirs = listOf("build/", ".gradle/", ".idea/", ".kotlin/", ".cxx/", ".externalNativeBuild/", "captures/", "__MACOSX/", ".git/")
    private val skipFiles = listOf("local.properties", ".DS_Store")

    val textExtensions = setOf(
        "kt", "kts", "java", "xml", "gradle", "properties", "json", "md", "txt", "pro", "toml", "yml", "yaml",
        "cfg", "html", "css", "js", "c", "cpp", "h", "hpp", "cmake", "aidl", "gitignore",
    )

    fun isText(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name.substringAfterLast('.', "").lowercase() in textExtensions || name == "gradlew" || name == ".gitignore"
    }

    data class Imported(
        /** Small files, in memory. */
        val files: Map<String, ByteArray>,
        /** Big files, saved on the phone's storage. */
        val bigFiles: Map<String, File>,
        val name: String,
        val skipped: Int,
        val tooBig: List<String>,
    ) {
        val count get() = files.size + bigFiles.size
    }

    fun read(zip: File, fallbackName: String): Imported {
        val raw = linkedMapOf<String, ByteArray>()
        val big = linkedMapOf<String, File>()
        val tooBig = mutableListOf<String>()
        val spill = File(zip.parentFile, zip.nameWithoutExtension + "_big").apply { deleteRecursively(); mkdirs() }
        var skipped = 0
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val path = e.name.replace('\\', '/').trimStart('/')
                if (e.isDirectory || path.contains("../")) continue
                if (skipDirs.any { path.startsWith(it) || path.contains("/$it") } || path.substringAfterLast('/') in skipFiles ||
                    path.endsWith(".apk") || path.endsWith(".aab")
                ) {
                    skipped++
                    continue
                }
                if (raw.size + big.size >= MAX_FILES) throw IllegalArgumentException("The project has more than $MAX_FILES files.")
                if (e.size in 0..IN_MEMORY) {
                    raw[path] = z.readBytes()
                    continue
                }
                // Large or unknown-size entry: stream it to storage, never fully into memory.
                val out = File(spill, "${big.size}_" + path.substringAfterLast('/'))
                var written = 0L
                out.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = z.read(buf)
                        if (n < 0) break
                        written += n
                        if (written > MAX_FILE) break
                        o.write(buf, 0, n)
                    }
                }
                when {
                    written > MAX_FILE -> {
                        out.delete()
                        tooBig += path
                    }
                    written <= IN_MEMORY -> {
                        raw[path] = out.readBytes()
                        out.delete()
                    }
                    else -> big[path] = out
                }
            }
        }
        if (raw.isEmpty() && big.isEmpty()) throw IllegalArgumentException("The zip is empty.")

        // The project root is the shallowest folder holding settings.gradle(.kts).
        val all = raw.keys + big.keys
        val settings = all.filter { it.substringAfterLast('/').startsWith("settings.gradle") }
            .minByOrNull { it.count { c -> c == '/' } }
            ?: throw IllegalArgumentException("This doesn't look like an Android Studio project (no settings.gradle found).")
        val prefix = settings.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        fun keep(k: String) = k.startsWith(prefix) && !k.removePrefix(prefix).startsWith(".github/workflows/ai-app-build")
        val files = raw.filterKeys(::keep).mapKeys { it.key.removePrefix(prefix) }
        val bigFiles = big.filterKeys(::keep).mapKeys { it.key.removePrefix(prefix) }

        val settingsText = files[settings.removePrefix(prefix)]?.toString(Charsets.UTF_8).orEmpty()
        val name = Regex("rootProject\\.name\\s*=\\s*[\"']([^\"']+)[\"']").find(settingsText)?.groupValues?.get(1)
            ?: fallbackName.removeSuffix(".zip")
        return Imported(files, bigFiles, name, skipped, tooBig.map { it.removePrefix(prefix) })
    }

    /** A short summary (file tree + key files) so any chat model can read an uploaded project. */
    fun summary(zip: File, maxChars: Int = 150_000): String {
        val sb = StringBuilder()
        val tree = mutableListOf<String>()
        val contents = mutableListOf<Pair<String, String>>()
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val path = e.name.replace('\\', '/')
                if (e.isDirectory || skipDirs.any { path.startsWith(it) || path.contains("/$it") }) continue
                tree += path
                if (isText(path) && tree.size < 2000) {
                    val bytes = z.readBytes()
                    if (bytes.size < 60_000) contents += path to bytes.toString(Charsets.UTF_8)
                }
            }
        }
        sb.append("Project files (${tree.size}):\n")
        tree.take(400).forEach { sb.append("  ").append(it).append('\n') }
        if (tree.size > 400) sb.append("  …\n")
        // Build files and sources first.
        val ordered = contents.sortedBy { (p, _) ->
            when {
                p.contains("gradle") -> 0
                p.endsWith(".kt") || p.endsWith(".java") -> 1
                p.endsWith("AndroidManifest.xml") -> 1
                else -> 2
            }
        }
        for ((p, c) in ordered) {
            if (sb.length + c.length > maxChars) break
            sb.append("\n--- ").append(p).append(" ---\n").append(c).append('\n')
        }
        return sb.toString()
    }
}
