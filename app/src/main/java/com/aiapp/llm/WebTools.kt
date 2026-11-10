package com.aiapp.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Internet access for any model, including offline ones: the model asks for a search or a page with
 * a short tag, the app fetches it and hands the text back. Works without any search API key.
 */
object WebTools {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36"

    val searchTag = Regex("<search>([\\s\\S]{1,300}?)</search>", RegexOption.IGNORE_CASE)
    val fetchTag = Regex("<fetch>\\s*(https?://\\S{1,500}?)\\s*</fetch>", RegexOption.IGNORE_CASE)
    val imagesTag = Regex("<images>([\\s\\S]{1,200}?)</images>", RegexOption.IGNORE_CASE)
    val videosTag = Regex("<videos>([\\s\\S]{1,200}?)</videos>", RegexOption.IGNORE_CASE)
    val downloadTag = Regex("<download>\\s*(https?://\\S{1,500}?)\\s*</download>", RegexOption.IGNORE_CASE)

    /** Saves downloads to Downloads/AIApp. Set once at startup. */
    var downloader: (suspend (String) -> String)? = null

    fun instructions(today: String) = "\n\n" + """

        Today's date is $today.
        You can use the internet. To search the web, reply with ONLY this and nothing else:
        <search>your search query</search>
        To read a web page, reply with ONLY:
        <fetch>https://full.url</fetch>
        To show the user pictures (people, places, things), reply with ONLY:
        <images>what to find</images>
        To show the user videos (clips, songs, trailers, how-tos), reply with ONLY:
        <videos>what to find</videos>
        The pictures or videos appear in the chat automatically; afterwards just write a short caption.
        To save a file (PDF, image, APK, document) to the user's phone when they ask for it, reply with ONLY:
        <download>https://direct.file.url</download>
        You will get the results and can then search again, read a page, or answer. Search when the
        question needs current information (news, prices, weather, sports, recent releases) or facts
        you are not sure about. Otherwise answer directly. When you use web results, mention the
        sources' website names.
    """.trimIndent()

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            Http.client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val type = r.header("Content-Type").orEmpty()
                if (type.isNotEmpty() && !type.contains("text") && !type.contains("json") && !type.contains("xml")) {
                    return@use null
                }
                r.body?.source()?.let { src ->
                    src.request(2_000_000)
                    src.buffer.clone().readUtf8()
                }
            }
        }.getOrNull()
    }

    suspend fun search(query: String): String {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val html = get("https://html.duckduckgo.com/html/?q=$q")
        val results = html?.let { parseDuckDuckGo(it) }.orEmpty()
        if (results.isNotEmpty()) {
            return results.take(6).mapIndexed { i, r -> "${i + 1}. ${r.first}\n${r.second}\n${r.third}" }
                .joinToString("\n\n")
        }
        // Fallback: Wikipedia search.
        val wiki = get("https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=5&srsearch=$q")
            ?: return "No results (search unavailable)."
        val hits = runCatching {
            (Http.json.parseToJsonElement(wiki).jsonObject["query"] as? JsonObject)?.get("search") as? JsonArray
        }.getOrNull().orEmpty()
        if (hits.isEmpty()) return "No results."
        return hits.mapIndexed { i, h ->
            val o = h as JsonObject
            val title = (o["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val snippet = stripHtml((o["snippet"] as? JsonPrimitive)?.contentOrNull.orEmpty())
            "${i + 1}. $title\nhttps://en.wikipedia.org/wiki/${title.replace(' ', '_')}\n$snippet"
        }.joinToString("\n\n")
    }

    /** (title, url, snippet) from DuckDuckGo's HTML results page. */
    private fun parseDuckDuckGo(html: String): List<Triple<String, String, String>> {
        val links = Regex("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>").findAll(html).toList()
        val snippets = Regex("class=\"result__snippet\"[^>]*>([\\s\\S]*?)</a>").findAll(html).map { stripHtml(it.groupValues[1]) }.toList()
        return links.mapIndexed { i, m ->
            var url = m.groupValues[1].replace("&amp;", "&")
            Regex("[?&]uddg=([^&]+)").find(url)?.let { url = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (url.startsWith("//")) url = "https:$url"
            Triple(stripHtml(m.groupValues[2]), url, snippets.getOrElse(i) { "" })
        }.filter { !it.second.contains("duckduckgo.com/y.js") }
    }

    suspend fun fetch(url: String): String {
        val body = get(url) ?: return "Couldn't open $url."
        val text = if (body.contains("<html", ignoreCase = true) || body.contains("<body", ignoreCase = true)) {
            stripHtml(
                body.replace(Regex("<(script|style|noscript|svg|nav|footer|header)[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("<(br|p|div|li|h[1-6]|tr)[^>]*>", RegexOption.IGNORE_CASE), "\n"),
            )
        } else {
            body
        }
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n").take(8000)
    }

    fun stripHtml(s: String): String = s.replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace(Regex("[ \\t]+"), " ").trim()

    suspend fun download(url: String): String = downloader?.invoke(url) ?: "Downloads aren't available."
}
