package com.damonearl.fantasymultiview.streams

import com.damonearl.fantasymultiview.data.AppSettings
import com.damonearl.fantasymultiview.data.NflGame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Matches games to channels in a user-supplied M3U playlist (e.g. from an IPTV subscription). */
class M3uStreamProvider(private val http: OkHttpClient) : StreamProvider {

    data class Channel(val title: String, val url: String, val headers: Map<String, String>)

    private val mutex = Mutex()
    private var cachedUrl: String? = null
    private var cachedAt = 0L
    private var channels: List<Channel> = emptyList()

    override suspend fun find(game: NflGame, allGames: List<NflGame>, settings: AppSettings): StreamSource? {
        if (settings.m3uUrl.isBlank()) return null
        val best = playlist(settings.m3uUrl)
            .map { it to GameTitleMatcher.score(it.title, game) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first ?: return null
        return StreamSource(best.url, best.headers, label = best.title, kind = SourceKind.PLAYLIST)
    }

    private suspend fun playlist(url: String): List<Channel> = mutex.withLock {
        val fresh = cachedUrl == url && System.currentTimeMillis() - cachedAt < CACHE_MS
        if (!fresh) {
            channels = withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) error("Playlist returned HTTP ${response.code}")
                    parse(response.body?.string().orEmpty())
                }
            }
            cachedUrl = url
            cachedAt = System.currentTimeMillis()
        }
        channels
    }

    companion object {
        private const val CACHE_MS = 10 * 60_000L
        private val ATTR = Regex("""([\w-]+)="([^"]*)"""")

        fun parse(text: String): List<Channel> {
            val result = mutableListOf<Channel>()
            var title: String? = null
            val headers = mutableMapOf<String, String>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                when {
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        val attrs = ATTR.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                        title = line.substringAfterLast(',').trim().ifEmpty { attrs["tvg-name"].orEmpty() }
                        headers.clear()
                    }
                    line.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) ->
                        headers["Referer"] = line.substringAfter('=')
                    line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) ->
                        headers["User-Agent"] = line.substringAfter('=')
                    line.isNotEmpty() && !line.startsWith("#") && title != null -> {
                        val source = StreamSource.parse(line, title, SourceKind.PLAYLIST)
                        if (source != null) result += Channel(title, source.url, headers + source.headers)
                        title = null
                    }
                }
            }
            return result
        }
    }
}
