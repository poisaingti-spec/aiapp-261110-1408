package com.aiapp.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.aiapp.llm.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/** Downloads a built APK inside the app, checks it's a real APK, keeps a copy in Downloads, and opens the installer. */
object ApkInstaller {

    /** Size in bytes if [url] really serves a file, else null. */
    suspend fun check(url: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            Http.client.newCall(Request.Builder().url(url).head().build()).execute().use { r ->
                if (!r.isSuccessful) null else (r.header("Content-Length")?.toLongOrNull() ?: 0L).takeIf { it > 0 }
            }
        }.getOrNull()
    }

    suspend fun download(context: Context, url: String, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "apks").apply { mkdirs() }
        val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "app.apk" }
        val out = File(dir, name)
        Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Download failed (HTTP ${r.code}). The build may still be publishing — try again in a minute.")
            val body = r.body ?: throw IllegalStateException("Download failed (empty).")
            val total = body.contentLength()
            body.byteStream().use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
        // A real APK is a zip file starting with "PK" and containing AndroidManifest.xml.
        val ok = out.length() > 1000 && out.inputStream().use { val h = ByteArray(2); it.read(h) == 2 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte() } &&
            runCatching { java.util.zip.ZipFile(out).use { z -> z.getEntry("AndroidManifest.xml") != null } }.getOrDefault(false)
        if (!ok) {
            out.delete()
            throw IllegalStateException("The downloaded file isn't a valid APK. Try again, or open the link in the browser.")
        }
        saveCopy(context, out)
        out
    }

    /** Keeps a copy in Downloads/AIApp so you can share or reinstall it later. */
    private fun saveCopy(context: Context, apk: File) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, apk.name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AIApp")
                }
                context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                    context.contentResolver.openOutputStream(uri)?.use { o -> apk.inputStream().use { it.copyTo(o) } }
                }
            }
        }
    }

    /** Opens Android's install screen for [apk]. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
