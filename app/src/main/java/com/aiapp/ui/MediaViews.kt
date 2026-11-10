package com.aiapp.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aiapp.llm.Http
import com.aiapp.llm.MediaItem
import com.aiapp.llm.MediaSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Downloads and caches web images for the chat. */
object ImageLoader {
    private val cache = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun clear() = cache.evictAll()

    suspend fun load(url: String, maxSide: Int = 900): Bitmap? {
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36")
                    .build()
                Http.client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val bytes = r.body?.bytes() ?: return@use null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }.getOrNull()?.also { cache.put(url, it) }
        }
    }
}

@Composable
fun NetImage(url: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, fallback: String? = null) {
    val bmp by produceState<Bitmap?>(null, url) {
        value = ImageLoader.load(url) ?: fallback?.let { ImageLoader.load(it) }
    }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription = null, contentScale = contentScale, modifier = modifier)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

/** Pictures and videos found on the web, shown under a reply. */
@Composable
fun MediaRow(items: List<MediaItem>) {
    val images = items.filter { !it.video }
    val videos = items.filter { it.video }
    var viewing by remember { mutableStateOf<MediaItem?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (images.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(images) { m ->
                    NetImage(
                        m.thumb,
                        modifier = Modifier.size(150.dp).clip(RoundedCornerShape(12.dp)).clickable { viewing = m },
                    )
                }
            }
        }
        videos.forEach { VideoCard(it) }
    }
    viewing?.let { m -> ImageViewer(m, onClose = { viewing = null }) }
}

@Composable
private fun ImageViewer(m: MediaItem, onClose: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize().clickable { onClose() }) {
            Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                NetImage(m.url, fallback = m.thumb, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().weight(1f))
                if (m.title.isNotBlank()) Text(m.title, color = Color.White, modifier = Modifier.padding(12.dp), maxLines = 2)
                Row {
                    if (m.source.isNotBlank()) {
                        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m.source))) } }) {
                            Text("Open source", color = Color.White)
                        }
                    }
                    TextButton(onClick = onClose) { Text("Close", color = Color.White) }
                }
            }
        }
    }
}

/** A YouTube result: thumbnail with ▶; tap to play right in the chat. */
@Composable
fun VideoCard(m: MediaItem) {
    val context = LocalContext.current
    var playing by rememberSaveable(m.url) { mutableStateOf(false) }
    val id = MediaSearch.youtubeId(m.url)
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                if (playing && id != null) {
                    YouTubePlayer(id)
                } else {
                    NetImage(m.thumb, modifier = Modifier.fillMaxSize().clickable { playing = true })
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).clickable { playing = true },
                        contentAlignment = Alignment.Center,
                    ) { Text("▶", color = Color.White, style = MaterialTheme.typography.headlineSmall) }
                }
            }
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(m.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m.url))) } }) {
                    Text("YouTube")
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YouTubePlayer(id: String) {
    val html = """
        <html><body style="margin:0;background:#000">
        <iframe width="100%" height="100%" style="position:absolute;top:0;left:0;border:0"
          src="https://www.youtube-nocookie.com/embed/$id?autoplay=1&playsinline=1"
          allow="autoplay; encrypted-media; picture-in-picture; fullscreen" allowfullscreen></iframe>
        </body></html>
    """.trimIndent()
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = WebChromeClient()
                loadDataWithBaseURL("https://www.youtube-nocookie.com", html, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
        modifier = Modifier.fillMaxSize(),
    )
}
