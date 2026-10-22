package com.damonearl.fantasymultiview.streams

import android.util.Log
import com.damonearl.fantasymultiview.data.AppSettings
import com.damonearl.fantasymultiview.data.NflGame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream

/**
 * Live channels from an Xtream Codes panel, the API behind most IPTV subscriptions. It's recognised
 * from the usual "get.php?username=…&password=…" playlist link. Many panels refuse that M3U
 * download but still serve player_api, the JSON API IPTV apps use. Only NFL and US network/sports
 * categories are fetched, since a full list can be tens of MB.
 */
@OptIn(ExperimentalSerializationApi::class)
class XtreamProvider(private val http: OkHttpClient, private val json: Json) : StreamProvider {

    data class Account(val base: HttpUrl, val username: String, val password: String) {
        fun api(action: String? = null, vararg params: Pair<String, String>): HttpUrl = base.newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", username)
            .addQueryParameter("password", password)
            .apply {
                action?.let { addQueryParameter("action", it) }
                params.forEach { (k, v) -> addQueryParameter(k, v) }
            }
            .build()

        fun stream(id: String, format: String): HttpUrl = base.newBuilder()
            .addPathSegment("live").addPathSegment(username).addPathSegment(password).addPathSegment("$id.$format")
            .build()

        companion object {
            /** An Xtream account from a get.php or player_api.php link, or null for any other URL. */
            fun parse(link: String): Account? {
                val url = link.trim().toHttpUrlOrNull() ?: return null
                if (url.pathSegments.last().lowercase() !in setOf("get.php", "player_api.php")) return null
                val username = url.queryParameter("username")?.takeIf { it.isNotEmpty() } ?: return null
                val password = url.queryParameter("password") ?: return null
                val base = url.newBuilder().removePathSegment(url.pathSize - 1).query(null).fragment(null).build()
                return Account(base, username, password)
            }
        }
    }

    data class Channel(val name: String, val id: String)

    private val mutex = Mutex()
    private var cachedLink: String? = null
    private var fetchedAt = 0L
    private var failedAt = 0L
    /** The account as it last answered (its https link may have fallen back to http). */
    private var working: Account? = null
    private var format = "ts"
    private var channels: List<Channel> = emptyList()

    @Volatile var lastError: String? = null
        private set

    override suspend fun find(game: NflGame, allGames: List<NflGame>, settings: AppSettings): StreamSource? =
        options(game, allGames, settings).firstOrNull()

    override suspend fun options(game: NflGame, allGames: List<NflGame>, settings: AppSettings): List<StreamSource> {
        if (Account.parse(settings.m3uUrl) == null) return emptyList()
        val list = load(settings.m3uUrl)
        val account = working ?: return emptyList()
        return ChannelMatcher.ranked(list, { it.name }, game, allGames).map { channel ->
            StreamSource(
                url = account.stream(channel.id, format).toString(),
                headers = mapOf("User-Agent" to PLAYER_USER_AGENT),
                label = channel.name.trim(),
                kind = SourceKind.PLAYLIST,
            )
        }
    }

    private suspend fun load(link: String): List<Channel> = mutex.withLock {
        val now = System.currentTimeMillis()
        val stale = cachedLink != link || now - fetchedAt > CACHE_MS
        // After a failure, wait before asking again; panels ban clients that hammer them.
        val backingOff = cachedLink == link && now - failedAt < RETRY_MS
        if (stale && !backingOff) {
            if (cachedLink != link) {
                channels = emptyList()
                working = null
            }
            cachedLink = link
            try {
                withContext(Dispatchers.IO) { refresh(Account.parse(link)!!) }
                fetchedAt = now
                lastError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Xtream refresh failed", e)
                failedAt = now
                lastError = e.message ?: e::class.simpleName
            }
        }
        channels
    }

    private fun refresh(link: Account) {
        val (account, info) = login(link)
        val user = info["user_info"] as? JsonObject ?: error("Not an Xtream IPTV server")
        if (user.str("auth") != "1") error("IPTV login rejected; check the username and password in the playlist link")
        user.str("status")?.takeUnless { it.equals("Active", ignoreCase = true) }?.let { error("IPTV account is $it") }
        val formats = (user["allowed_output_formats"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

        val categories = get(account.api("get_live_categories")) { input ->
            objects(input).filter { relevantCategory(it.str("category_name").orEmpty()) }.mapNotNull { it.str("category_id") }.toList()
        }
        val found = if (categories.isNotEmpty()) {
            categories.flatMap { id ->
                get(account.api("get_live_streams", "category_id" to id)) { input -> objects(input).mapNotNull(::channel).toList() }
            }
        } else {
            // Unfamiliar category names: stream the whole list, keeping only plausible channels.
            get(account.api("get_live_streams")) { input -> objects(input).mapNotNull(::channel).filter { relevantName(it.name) }.toList() }
        }

        working = account
        format = if (formats.isEmpty() || "ts" in formats) "ts" else "m3u8"
        channels = found.distinctBy { it.id }
        Log.i(TAG, "${channels.size} channels from ${categories.size} categories, format=$format, maxConnections=${user.str("max_connections")}")
    }

    /** Account info, from the link's own address or, if an https one is refused, plain http (many panels serve only http). */
    private fun login(link: Account): Pair<Account, JsonObject> {
        val fallback = link.base.takeIf { it.isHttps }?.let { link.copy(base = it.newBuilder().scheme("http").build()) }
        return try {
            link to get(link.api()) { json.decodeFromStream<JsonObject>(it) }
        } catch (e: Exception) {
            fallback ?: throw e
            fallback to get(fallback.api()) { json.decodeFromStream<JsonObject>(it) }
        }
    }

    private fun <T> get(url: HttpUrl, read: (InputStream) -> T): T {
        val request = Request.Builder().url(url).header("User-Agent", PLAYER_USER_AGENT).build()
        return http.newCall(request).execute().use { response ->
            // Full request/response details are logged under the HttpDebug tag.
            if (!response.isSuccessful) error("IPTV server returned HTTP ${response.code}")
            read(response.body!!.byteStream())
        }
    }

    private fun objects(input: InputStream): Sequence<JsonObject> =
        json.decodeToSequence<JsonObject>(input, DecodeSequenceMode.ARRAY_WRAPPED)

    private fun channel(o: JsonObject): Channel? {
        val name = o.str("name")?.takeIf { it.isNotBlank() } ?: return null
        val id = o.str("stream_id")?.takeIf { it.isNotBlank() } ?: return null
        return Channel(name, id)
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        private const val TAG = "XtreamProvider"
        private const val CACHE_MS = 5 * 60_000L
        private const val RETRY_MS = 2 * 60_000L

        private val NFL = Regex("""\bNFL\b""", RegexOption.IGNORE_CASE)
        private val US = Regex("""^\W*(US|USA)\b""", RegexOption.IGNORE_CASE)
        private val BROADCASTERS = Regex(
            """\b(CBS|FOX|NBC|ABC|ESPN|SPORTS?|PRIME|AMAZON|PEACOCK|PARAMOUNT|NETFLIX|YOUTUBE)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val SKIP = Regex("""REPLAY|24/7|ESPN\s*\+""", RegexOption.IGNORE_CASE)
        private val EVENT = Regex("""\s(vs\.?|v|at|@)\s""", RegexOption.IGNORE_CASE)

        /** Categories that can carry NFL games: NFL ones, and US network, sports and streaming ones. */
        fun relevantCategory(name: String) =
            !SKIP.containsMatchIn(name) && (NFL.containsMatchIn(name) || US.containsMatchIn(name) && BROADCASTERS.containsMatchIn(name))

        fun relevantName(name: String) =
            NFL.containsMatchIn(name) || BROADCASTERS.containsMatchIn(name) || EVENT.containsMatchIn(name)
    }
}
