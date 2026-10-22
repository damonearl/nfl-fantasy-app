package com.damonearl.fantasymultiview.streams

import android.util.Log
import com.damonearl.fantasymultiview.data.AppSettings
import com.damonearl.fantasymultiview.data.NflGame
import kotlinx.coroutines.CancellationException
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
    private var failedAt = 0L
    private var channels: List<Channel> = emptyList()

    @Volatile var lastError: String? = null
        private set

    override suspend fun find(game: NflGame, allGames: List<NflGame>, settings: AppSettings): StreamSource? =
        options(game, allGames, settings).firstOrNull()

    override suspend fun options(game: NflGame, allGames: List<NflGame>, settings: AppSettings): List<StreamSource> {
        // Xtream links are read through their JSON API instead; see XtreamProvider.
        if (settings.m3uUrl.isBlank() || XtreamProvider.Account.parse(settings.m3uUrl) != null) return emptyList()
        return ChannelMatcher.ranked(playlist(settings.m3uUrl), { it.title }, game, allGames).map {
            StreamSource(it.url, it.headers, label = it.title, kind = SourceKind.PLAYLIST)
        }
    }

    private suspend fun playlist(url: String): List<Channel> = mutex.withLock {
        val now = System.currentTimeMillis()
        val fresh = cachedUrl == url && now - cachedAt < CACHE_MS
        // After a failure, wait before asking again; IPTV hosts ban clients that hammer them.
        val backingOff = cachedUrl == url && now - failedAt < RETRY_MS
        if (!fresh && !backingOff) {
            if (cachedUrl != url) channels = emptyList()
            cachedUrl = url
            try {
                channels = withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(url).header("User-Agent", PLAYER_USER_AGENT).build()
                    http.newCall(request).execute().use { response ->
                        // Full request/response details are logged under the HttpDebug tag.
                        if (!response.isSuccessful) error("Playlist download failed: HTTP ${response.code} ${response.message}".trimEnd())
                        // Read line by line; playlists with VOD can be tens of MB.
                        response.body!!.charStream().buffered().useLines(::parse)
                    }
                }
                cachedAt = now
                lastError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "playlist download failed", e)
                failedAt = now
                lastError = e.message ?: e::class.simpleName
            }
        }
        channels
    }

    companion object {
        private const val TAG = "M3uStreamProvider"
        private const val CACHE_MS = 10 * 60_000L
        private const val RETRY_MS = 2 * 60_000L
        private val ATTR = Regex("""([\w-]+)="([^"]*)"""")

        fun parse(text: String): List<Channel> = parse(text.lineSequence())

        fun parse(lines: Sequence<String>): List<Channel> {
            val result = mutableListOf<Channel>()
            var title: String? = null
            val headers = mutableMapOf<String, String>()
            for (raw in lines) {
                val line = raw.trim()
                when {
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        val attrs = ATTR.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                        title = displayName(line).ifEmpty { attrs["tvg-name"].orEmpty() }
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

        /** The text after the first comma outside a quoted attribute; titles may contain commas themselves. */
        private fun displayName(extinf: String): String {
            var quoted = false
            extinf.forEachIndexed { i, c ->
                if (c == '"') quoted = !quoted
                if (c == ',' && !quoted) return extinf.substring(i + 1).trim()
            }
            return ""
        }
    }
}
