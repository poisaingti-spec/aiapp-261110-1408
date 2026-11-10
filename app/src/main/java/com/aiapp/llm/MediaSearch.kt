package com.aiapp.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import java.net.URLEncoder

/** Finds images and videos on the web (no API key needed). */
object MediaSearch {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36"

    private suspend fun get(url: String, referer: String? = null): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).header("User-Agent", UA)
                .apply { if (referer != null) header("Referer", referer) }
                .header("Accept-Language", "en-US,en;q=0.9").build()
            Http.client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        }.getOrNull()
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    suspend fun images(query: String, limit: Int = 8): List<MediaItem> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        // DuckDuckGo image search needs a short-lived token from its search page first.
        val page = get("https://duckduckgo.com/?q=$q&iax=images&ia=images")
        val vqd = page?.let { Regex("vqd=[\"']?([\\d-]+)").find(it)?.groupValues?.get(1) }
        if (vqd != null) {
            val json = get("https://duckduckgo.com/i.js?l=wt-wt&o=json&q=$q&vqd=$vqd&f=,,,,,&p=1", referer = "https://duckduckgo.com/")
            val results = runCatching { Http.json.parseToJsonElement(json!!).jsonObject["results"] as? JsonArray }.getOrNull()
            val items = results.orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val image = o.str("image") ?: return@mapNotNull null
                MediaItem(
                    video = false,
                    url = image,
                    thumb = o.str("thumbnail") ?: image,
                    title = o.str("title").orEmpty(),
                    source = o.str("url").orEmpty(),
                )
            }.filter { it.url.startsWith("https://") || it.thumb.startsWith("https://") }
            if (items.isNotEmpty()) return items.take(limit)
        }
        // Fallback: Wikimedia Commons.
        val wiki = get(
            "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6" +
                "&gsrlimit=$limit&gsrsearch=$q&prop=imageinfo&iiprop=url&iiurlwidth=480",
        ) ?: return emptyList()
        val pages = runCatching {
            (Http.json.parseToJsonElement(wiki).jsonObject["query"] as? JsonObject)?.get("pages") as? JsonObject
        }.getOrNull() ?: return emptyList()
        return pages.values.mapNotNull { p ->
            val o = p as? JsonObject ?: return@mapNotNull null
            val info = (o["imageinfo"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@mapNotNull null
            MediaItem(
                video = false,
                url = info.str("url") ?: return@mapNotNull null,
                thumb = info.str("thumburl") ?: info.str("url")!!,
                title = o.str("title").orEmpty().removePrefix("File:"),
                source = info.str("descriptionurl").orEmpty(),
            )
        }
    }

    suspend fun videos(query: String, limit: Int = 5): List<MediaItem> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val html = get("https://www.youtube.com/results?search_query=$q&hl=en") ?: return emptyList()
        val seen = LinkedHashMap<String, String>()
        // Each result in YouTube's page data has a videoId followed by its title.
        Regex("\"videoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"[\\s\\S]{0,1500}?\"title\":\\{\"runs\":\\[\\{\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"")
            .findAll(html).forEach { m ->
                val id = m.groupValues[1]
                if (id !in seen) seen[id] = m.groupValues[2].replace("\\u0026", "&").replace("\\\"", "\"")
            }
        if (seen.isEmpty()) {
            Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"").findAll(html).forEach { seen.putIfAbsent(it.groupValues[1], "") }
        }
        return seen.entries.take(limit).map { (id, title) ->
            MediaItem(
                video = true,
                url = "https://www.youtube.com/watch?v=$id",
                thumb = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                title = title,
                source = "YouTube",
            )
        }
    }

    /** "dQw4w9WgXcQ" from a YouTube link, or null. */
    fun youtubeId(url: String): String? =
        Regex("(?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/shorts/|youtube\\.com/embed/)([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)
}
