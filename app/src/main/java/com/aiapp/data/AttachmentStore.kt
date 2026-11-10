package com.aiapp.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.aiapp.llm.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Copies picked files (any type, any size that fits on the phone) into the app's storage and pulls
 * out text where possible: plain text and code, Word/Excel/PowerPoint, and project zips.
 */
object AttachmentStore {
    private const val MAX_TEXT = 2_000_000
    private const val MAX_IMAGE_SIDE = 1600

    private val textExtensions = setOf(
        "txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "yaml", "yml", "toml", "ini", "log", "srt", "vtt",
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rtf",
        "swift", "rb", "php", "sh", "sql", "gradle", "properties", "css", "scss", "dart", "lua", "r", "tex",
    )

    /** [onProgress] gets 0..1 while the file is copied (or -1 if the size is unknown). */
    suspend fun import(context: Context, uri: Uri, onProgress: (Float) -> Unit = {}): Attachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val dir = File(context.filesDir, "attachments").apply { mkdirs() }
        if (size > 0 && size > dir.usableSpace - 200L * 1024 * 1024) {
            throw IllegalArgumentException("Not enough free space on the phone for $name.")
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        var mime = resolver.getType(uri) ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        if (mime == "application/octet-stream") MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { mime = it }
        if (ext in textExtensions && !mime.startsWith("text/")) mime = "text/plain"
        if (ext == "pdf") mime = "application/pdf"
        if (ext == "zip") mime = "application/zip"
        if (ext == "apk") mime = "application/vnd.android.package-archive"

        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val out = File(dir, "${UUID.randomUUID().toString().take(8)}_$safe")
        resolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(1 shl 16)
                var copied = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    copied += n
                    if (copied - lastReport > 512 * 1024) {
                        lastReport = copied
                        onProgress(if (size > 0) (copied.toFloat() / size).coerceAtMost(0.99f) else -1f)
                    }
                }
            }
        } ?: throw IllegalArgumentException("Couldn't open $name.")
        onProgress(1f)

        when {
            mime.startsWith("image/") && ext != "svg" -> {
                // Shrink big photos: models don't need more detail, and it keeps requests fast and cheap.
                val shrunk = runCatching { shrink(out) }.getOrDefault(false)
                Attachment(name, if (shrunk) "image/jpeg" else mime, out.absolutePath)
            }
            mime == "application/pdf" || mime.startsWith("audio/") || mime.startsWith("video/") ->
                Attachment(name, mime, out.absolutePath)
            ext == "zip" -> {
                // Projects: keep the zip (for building) and a readable summary (for any model to read).
                val summary = runCatching { com.aiapp.builder.ZipImport.summary(out) }.getOrNull()
                Attachment(name, "application/zip", out.absolutePath, summary)
            }
            ext == "apk" -> {
                // Android APK: inspect via REA Engine to extract manifest, components, DEX classes & architecture specification
                val summary = runCatching { com.aiapp.builder.ReaEngine.inspectApk(out).fullMarkdownSpec }.getOrNull()
                Attachment(name, "application/vnd.android.package-archive", out.absolutePath, summary)
            }
            ext in listOf("docx", "xlsx", "pptx", "odt", "ods", "odp") ->
                Attachment(name, mime, out.absolutePath, runCatching { officeText(out, ext) }.getOrNull())
            mime.startsWith("text/") || ext in textExtensions || mime.contains("json") || mime.contains("xml") || ext == "svg" ->
                Attachment(name, "text/plain", out.absolutePath, readText(out))
            else -> Attachment(name, mime, out.absolutePath, sniffText(out))
        }
    }

    private fun readText(f: File): String = f.bufferedReader().use { r ->
        val buf = CharArray(MAX_TEXT)
        val n = r.read(buf)
        if (n <= 0) "" else String(buf, 0, n)
    }

    /** Unknown types: if the start of the file looks like text, treat it as text. */
    private fun sniffText(f: File): String? {
        val head = f.inputStream().use { it.readNBytesCompat(4096) }
        if (head.isEmpty()) return null
        val printable = head.count { b -> b in 9..13 || b in 32..126 || b < 0 }
        return if (printable > head.size * 0.95 && head.none { it == 0.toByte() }) readText(f) else null
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val buf = ByteArray(n)
        var total = 0
        while (total < n) {
            val r = read(buf, total, n - total)
            if (r < 0) break
            total += r
        }
        return buf.copyOf(total)
    }

    /** Text from Word, Excel and PowerPoint (and OpenDocument) files, which are zips of XML. */
    private fun officeText(f: File, ext: String): String = ZipFile(f).use { zip ->
        fun xml(name: String) = zip.getEntry(name)?.let { e -> zip.getInputStream(e).bufferedReader().readText() }
        fun strip(x: String) = x
            .replace(Regex("</w:p>|</a:p>|<w:br/>|</text:p>|</row>|</table:table-row>"), "\n")
            .replace(Regex("</c>|</table:table-cell>"), "\t")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        val sb = StringBuilder()
        when (ext) {
            "docx" -> sb.append(strip(xml("word/document.xml").orEmpty()))
            "pptx" -> zip.entries().toList().map { it.name }
                .filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
                .forEachIndexed { i, n -> sb.append("--- Slide ${i + 1} ---\n").append(strip(xml(n).orEmpty())).append('\n') }
            "xlsx" -> {
                val shared = Regex("<si>([\\s\\S]*?)</si>").findAll(xml("xl/sharedStrings.xml").orEmpty())
                    .map { strip(it.groupValues[1]) }.toList()
                zip.entries().toList().map { it.name }.filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }.sorted()
                    .forEach { n ->
                        sb.append("--- ${n.substringAfterLast('/').removeSuffix(".xml")} ---\n")
                        Regex("<row[^>]*>([\\s\\S]*?)</row>").findAll(xml(n).orEmpty()).forEach { row ->
                            val cells = Regex("<c([^>]*)>(?:[\\s\\S]*?<v>([^<]*)</v>)?[\\s\\S]*?</c>|<c([^>]*)/>").findAll(row.groupValues[1]).map { c ->
                                val attrs = c.groupValues[1]
                                val v = c.groupValues[2]
                                if (attrs.contains("t=\"s\"")) shared.getOrNull(v.toIntOrNull() ?: -1).orEmpty() else v
                            }
                            sb.append(cells.joinToString("\t")).append('\n')
                        }
                    }
            }
            else -> sb.append(strip(xml("content.xml").orEmpty()))
        }
        sb.toString().replace(Regex("\n{3,}"), "\n\n").take(MAX_TEXT)
    }

    private fun shrink(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return false
        if (longest <= MAX_IMAGE_SIDE && file.length() < 1_500_000) return false
        var sample = 1
        while (longest / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val scale = MAX_IMAGE_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
        val final = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        file.outputStream().use { final.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return true
    }
}
