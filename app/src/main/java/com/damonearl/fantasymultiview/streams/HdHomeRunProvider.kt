package com.damonearl.fantasymultiview.streams

import com.damonearl.fantasymultiview.data.AppSettings
import com.damonearl.fantasymultiview.data.GameState
import com.damonearl.fantasymultiview.data.NflGame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Over-the-air channels from an HDHomeRun network tuner.
 *
 * A game is placed on a channel by (1) the HDHomeRun guide listing a program naming both teams,
 * or (2) the game's broadcast network (CBS/FOX/NBC/ABC) mapped to a local channel — only when
 * that network has a single game in the window, since local affiliates air just one regional game.
 */
class HdHomeRunProvider(
    private val http: OkHttpClient,
    private val json: Json,
    /** Lets the built-in TV tuner take precedence; HDHomeRun is only searched for when this says so. */
    private val shouldRun: (AppSettings) -> Boolean = { true },
) : StreamProvider {

    private class Device(
        val host: String,
        val auth: String?,
        val tunerCount: Int,
        val lineup: List<LineupDto>,
        val guide: List<GuideChannelDto>,
        val fetchedAt: Long,
    )

    private val mutex = Mutex()
    private var device: Device? = null

    /** Tuners on the device, or null if no device has been reached yet. */
    val tunerCount: Int? get() = device?.tunerCount

    /** Why the tuner couldn't be reached on the last attempt, for display in setup. */
    @Volatile var lastError: String? = null
        private set

    override suspend fun find(game: NflGame, allGames: List<NflGame>, settings: AppSettings): StreamSource? {
        if (!settings.antennaEnabled || game.state == GameState.POST || !shouldRun(settings)) return null
        val dev = try {
            device(settings).also { lastError = null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e.message ?: "Couldn't reach the HDHomeRun"
            throw e
        }

        val channel = channelFromGuide(dev, game) ?: channelFromNetwork(dev, game, allGames, settings)
            ?: return null
        val url = channel.URL.toHttpUrlOrNull()?.newBuilder()?.apply {
            if (settings.antennaTranscode.isNotBlank()) setQueryParameter("transcode", settings.antennaTranscode.trim())
        }?.build()?.toString() ?: return null
        return StreamSource(url, label = "Antenna ${channel.GuideNumber} ${channel.GuideName}", kind = SourceKind.ANTENNA)
    }

    private fun channelFromGuide(dev: Device, game: NflGame): LineupDto? {
        val at = OtaMatching.airTime(game).epochSecond
        val match = dev.guide.firstOrNull { ch ->
            ch.Guide.any { p ->
                p.StartTime <= at && at < p.EndTime &&
                    OtaMatching.programMatches(listOfNotNull(p.Title, p.EpisodeTitle, p.Synopsis).joinToString(" "), game)
            }
        } ?: return null
        return dev.lineup.firstOrNull { it.GuideNumber == match.GuideNumber }
    }

    private fun channelFromNetwork(dev: Device, game: NflGame, allGames: List<NflGame>, settings: AppSettings): LineupDto? {
        val network = OtaMatching.uniqueNetwork(game, allGames) ?: return null
        val guideNumber = settings.antennaChannels[network]
            ?: dev.guide.firstOrNull { it.Affiliate.equals(network, ignoreCase = true) }?.GuideNumber
        return dev.lineup.firstOrNull { it.GuideNumber == guideNumber }
            ?: dev.lineup.firstOrNull { it.GuideName.contains(network, ignoreCase = true) }
    }

    private suspend fun device(settings: AppSettings): Device = mutex.withLock {
        val host = settings.antennaHost.trim()
        device?.takeIf {
            (host.isEmpty() || it.host == host) && System.currentTimeMillis() - it.fetchedAt < CACHE_MS
        }?.let { return it }

        withContext(Dispatchers.IO) {
            val discoverUrl = if (host.isNotEmpty()) "http://$host/discover.json" else {
                getJson<List<CloudDeviceDto>>(CLOUD_DISCOVER)
                    .firstOrNull { it.LineupURL != null }?.DiscoverURL
                    ?: error("No HDHomeRun tuner found on this network. Enter its IP address in settings.")
            }
            val info = getJson<DiscoverDto>(discoverUrl)
            val lineup = getJson<List<LineupDto>>(info.LineupURL ?: "${info.BaseURL}/lineup.json")
            val guide = info.DeviceAuth?.let { auth ->
                runCatching { getJson<List<GuideChannelDto>>("$GUIDE?DeviceAuth=$auth") }.getOrNull()
            }.orEmpty()
            Device(
                host = discoverUrl.toHttpUrlOrNull()?.host ?: host,
                auth = info.DeviceAuth,
                tunerCount = info.TunerCount.coerceAtLeast(1),
                lineup = lineup,
                guide = guide,
                fetchedAt = System.currentTimeMillis(),
            )
        }.also { device = it }
    }

    private inline fun <reified T> getJson(url: String): T =
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) error("HDHomeRun request failed: HTTP ${response.code}")
            json.decodeFromString(response.body?.string().orEmpty())
        }

    private companion object {
        const val CACHE_MS = 10 * 60_000L
        const val CLOUD_DISCOVER = "https://api.hdhomerun.com/discover"
        const val GUIDE = "https://api.hdhomerun.com/api/guide.php"
    }
}

// Field names mirror the HDHomeRun JSON API.
@Suppress("PropertyName")
@Serializable
private data class CloudDeviceDto(val DiscoverURL: String? = null, val LineupURL: String? = null)

@Suppress("PropertyName")
@Serializable
private data class DiscoverDto(
    val BaseURL: String = "",
    val LineupURL: String? = null,
    val DeviceAuth: String? = null,
    val TunerCount: Int = 2,
)

@Suppress("PropertyName")
@Serializable
private data class LineupDto(val GuideNumber: String = "", val GuideName: String = "", val URL: String = "")

@Suppress("PropertyName")
@Serializable
private data class GuideChannelDto(
    val GuideNumber: String = "",
    val GuideName: String = "",
    val Affiliate: String? = null,
    val Guide: List<ProgramDto> = emptyList(),
)

@Suppress("PropertyName")
@Serializable
private data class ProgramDto(
    val StartTime: Long = 0,
    val EndTime: Long = 0,
    val Title: String? = null,
    val EpisodeTitle: String? = null,
    val Synopsis: String? = null,
)
