package com.example.capillaflix

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.time.Instant
import java.util.concurrent.TimeUnit

data class Station(val id: String, val name: String, val url: String, val radio: Boolean = false, val referer: String = "")
data class Video(val id: String, val title: String, val channel: String, val published: Long, val live: Boolean = false) {
    val thumbnail get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
}
data class FeedResult(val videos: List<Video>, val failed: Int, val liveFailed: Int)

object Catalog {
    val tv = listOf(
        Station("ptv", "PTV Sevilla", "https://streamer.zapitv.com/PTV_sevilla/index.m3u8", referer = "https://www.ptvtelecom.com/"),
        Station("mv", "María Visión Sevilla", "https://1601580044.rsc.cdn77.org/live/_jcn_/amls:Italiatre/playlist.m3u8", referer = "https://www.mvsevilla.com/"),
        Station("7tv", "Sevilla 7TV", "https://especial7tv.gestec-video.com/hls/7TVSEVILLA.m3u8", referer = "https://www.7tvandalucia.es/"),
        Station("101", "101TV Sevilla", "https://liveingesta318.cdnmedia.tv/101televisionlive/smil:sevillaott.smil/playlist.m3u8?DVR", referer = "https://101play.tv/")
    )
    val handles = listOf("consejodehermandadesdesevi261", "PedacitosdePasión", "AromasDeIncienso", "macarenatelevision", "HermandadEsperanzadeTriana", "GranpoderEsp", "HdadLosGitanosSevilla", "HermandaddeSanGonzaloOficial", "siete_palabras", "hermandaddelaresurreccion", "hermandaddelamisionsevilla", "HERMANDADDELAMPAROSEVILLA", "ArcodelPostigo", "LIBETELTV", "hdadconsolacionpatronacarrion")
    fun radios(context: Context): List<Station> {
        val json = JSONArray(context.assets.open("radios.json").bufferedReader().use { it.readText() })
        return listOf(Station("onda", "Onda Cofrade", "https://stream.zeno.fm/pejcqbefxkzvv", true),
            Station("Iu3fNtCB", "Radio del Cofrade", "https://sonic.sistemahost.es/8066/stream", true)) +
            (0 until json.length()).map { json.getJSONObject(it).let { row ->
                Station(row.getString("id"), row.getString("name"), row.optString("url").ifBlank { "https://radio.garden/api/ara/content/listen/${row.getString("id")}/channel.mp3" }, true)
            } }
    }
}

class FeedRepository(private val context: Context) {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(22, TimeUnit.SECONDS).build()
    private val prefs = context.getSharedPreferences("youtube", Context.MODE_PRIVATE)
    private val bundledIds = JSONObject(context.assets.open("youtube_channels.json").bufferedReader().use { it.readText() })
    private val limit = Semaphore(4)
    // Separate from the old cache, which may still contain Shorts.
    private val feedCacheKey = "feed_without_shorts_v1"
    private fun get(url: String): String = client.newCall(Request.Builder().url(url)
        .header("User-Agent", "Mozilla/5.0").header("Cookie", "SOCS=CAI").header("Accept-Language", "es-ES,es;q=0.9").build()).execute().use {
        check(it.isSuccessful) { "HTTP ${it.code}" }; it.body!!.string()
    }
    fun cached(): List<Video> = runCatching {
        val a = JSONArray(prefs.getString(feedCacheKey, "[]")); (0 until a.length()).map {
            val v = a.getJSONObject(it); Video(v.getString("id"), v.getString("title"), v.getString("channel"), v.getLong("published"))
        }
    }.getOrDefault(emptyList())

    suspend fun refresh(): FeedResult = withContext(Dispatchers.IO) {
        supervisorScope {
            val results = Catalog.handles.map { handle -> async { limit.withPermit {
                var failed = false
                val uploads = try {
                    val key = "id_$handle"
                    val id = prefs.getString(key, null) ?: bundledIds.optString(handle).takeIf { it.isNotBlank() } ?: run {
                        val html = get("https://www.youtube.com/@$handle")
                        val found = Regex("\"externalId\":\"(UC[^\"]+)\"").find(html)?.groupValues?.get(1)
                            ?: Regex("https://www.youtube.com/channel/(UC[\\w-]+)").find(html)?.groupValues?.get(1)
                            ?: error("No se ha podido resolver @$handle")
                        prefs.edit().putString(key, found).apply(); found
                    }
                    val xml = get("https://www.youtube.com/feeds/videos.xml?channel_id=$id")
                    val parsed = parseYoutubeAtom(xml)
                    prefs.edit().putString("rss_$handle", xml).apply()
                    parsed
                } catch (e: CancellationException) { throw e } catch (_: Exception) {
                    failed = true; runCatching { parseYoutubeAtom(prefs.getString("rss_$handle", "")!!) }.getOrDefault(emptyList())
                }
                var liveFailed = false
                val live = try { parseYoutubeLive(get("https://www.youtube.com/@$handle/streams"), handle) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { liveFailed = true; emptyList() }
                Triple(uploads + live, failed, liveFailed)
            } } }.awaitAll()
            val videos = orderVideos(results.flatMap { it.first })
            val a = JSONArray(); videos.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title).put("channel", it.channel).put("published", it.published)) }
            prefs.edit().putString(feedCacheKey, a.toString()).apply()
            FeedResult(videos, results.count { it.second }, results.count { it.third })
        }
    }

}

internal fun parseYoutubeAtom(xml: String, p: XmlPullParser = Xml.newPullParser()): List<Video> {
    p.setInput(xml.reader())
    val videos = mutableListOf<Video>()
    var entry = false; var id = ""; var title = ""; var channel = ""; var date = 0L; var isShort = false
    while (p.eventType != XmlPullParser.END_DOCUMENT) {
        if (p.eventType == XmlPullParser.START_TAG) when (p.name) {
            "entry" -> { entry = true; id = ""; title = ""; date = 0; isShort = false }
            "link" -> if (entry && p.getAttributeValue(null, "rel") == "alternate") {
                val href = p.getAttributeValue(null, "href").orEmpty()
                if (href.startsWith("https://www.youtube.com/shorts/") || href.startsWith("https://youtube.com/shorts/")) isShort = true
            }
            "yt:videoId", "videoId" -> if (entry) id = p.nextText()
            "title" -> if (entry) title = p.nextText()
            "name" -> channel = p.nextText()
            "published" -> if (entry) date = Instant.parse(p.nextText()).toEpochMilli()
        }
        if (p.eventType == XmlPullParser.END_TAG && p.name == "entry") {
            if (id.isNotEmpty() && !isShort) videos += Video(id, title, channel, date)
            entry = false
        }
        p.next()
    }
    return videos
}

internal fun parseYoutubeLive(html: String, channel: String): List<Video> {
        val assignment = Regex("(?:var ytInitialData\\s*=\\s*|window\\[['\"]ytInitialData['\"]\\]\\s*=\\s*)(.*?);</script>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: error("YouTube no devuelve los datos de directos")
        val raw = if (assignment.startsWith("'")) decodeJavascriptString(assignment.removeSurrounding("'")) else assignment
        val out = mutableListOf<Video>()
        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val v = value.optJSONObject("videoRenderer") ?: value.optJSONObject("compactVideoRenderer") ?: value.optJSONObject("gridVideoRenderer")
                    if (v != null) {
                        // Only the LIVE overlay means broadcasting now; upcoming and ended streams are excluded.
                        val overlays = v.optJSONArray("thumbnailOverlays")?.toString().orEmpty()
                        if (Regex("\"style\":\"LIVE\"").containsMatchIn(overlays)) {
                            val title = v.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
                            val id = v.optString("videoId")
                            if (id.isNotBlank()) out += Video(id, title, channel, System.currentTimeMillis(), true)
                        }
                    }
                    value.keys().forEach { walk(value.opt(it)) }
                }
                is JSONArray -> (0 until value.length()).forEach { walk(value.opt(it)) }
            }
        }
        walk(JSONObject(raw)); return out
}

internal fun decodeJavascriptString(value: String): String {
    val out = StringBuilder(); var i = 0
    while (i < value.length) {
        val c = value[i++]
        if (c != '\\' || i >= value.length) { out.append(c); continue }
        when (val escaped = value[i++]) {
            'x', 'u' -> { val size = if (escaped == 'x') 2 else 4; require(i + size <= value.length); out.append(value.substring(i, i + size).toInt(16).toChar()); i += size }
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            't' -> out.append('\t')
            else -> out.append(escaped)
        }
    }
    return out.toString()
}

fun orderVideos(videos: List<Video>): List<Video> = videos.sortedWith(
    compareByDescending<Video> { it.live }.thenByDescending { it.published }
).distinctBy { it.id }

