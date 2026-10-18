package com.damonearl.fantasymultiview.streams

import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.util.Log
import com.damonearl.fantasymultiview.data.AppSettings
import com.damonearl.fantasymultiview.data.GameState
import com.damonearl.fantasymultiview.data.NflGame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Antenna channels from the TV's own tuner, via Android's TV Input Framework; playback is a TvView.
 *
 * Channels come from the system TV provider (with guide data, so games can be matched by program
 * title), or on TCL TVs, which hide their channels from it, from TCL's own channel database (no
 * guide). Without a guide, a game is placed by the user's network -> channel settings; two games on
 * one network at once can't be told apart, so those need a per-game "tv:2.1" override.
 */
class TvTunerProvider(private val context: Context) : StreamProvider {

    private class Channel(
        val uri: String,
        val inputId: String,
        val number: String,
        val name: String,
        val affiliation: String?,
        /** System TV provider row, for guide lookups; null for TCL channels. */
        val providerId: Long?,
        val tuneExtras: Map<String, Int> = emptyMap(),
    )

    private class Program(val start: Long, val end: Long, val text: String)

    private val mutex = Mutex()
    private var channels: List<Channel> = emptyList()
    private var programs: Map<String, List<Program>> = emptyMap()
    private var fetchedAt = 0L

    /** Simultaneous channels the tuner can show; most TVs have one. */
    @Volatile var tunerCount = 1
        private set

    @Volatile var lastError: String? = null
        private set

    val hasTuner get() = channels.isNotEmpty()

    /** Scanned channels as (number, name), for picking network channels in setup. */
    val channelChoices: List<Pair<String, String>> get() = channels.map { it.number to it.name }

    override suspend fun find(game: NflGame, allGames: List<NflGame>, settings: AppSettings): StreamSource? {
        if (!settings.antennaEnabled || game.state == GameState.POST) return null
        load()
        if (channels.isEmpty()) return null

        // Per-game override, e.g. "tv:4.1".
        settings.manualStreams[game.id]?.trim()?.takeIf { it.startsWith(TV_PREFIX, ignoreCase = true) }?.let { spec ->
            return byNumber(spec.substring(TV_PREFIX.length))?.let(::source)
        }

        val at = OtaMatching.airTime(game).toEpochMilli()
        val channel = channels.firstOrNull { ch ->
            programs[ch.uri].orEmpty().any { it.start <= at && at < it.end && OtaMatching.programMatches(it.text, game) }
        } ?: OtaMatching.uniqueNetwork(game, allGames)?.let { network ->
            settings.antennaChannels[network]?.let(::byNumber)
                ?: channels.firstOrNull { it.affiliation?.contains(network, ignoreCase = true) == true }
                ?: channels.firstOrNull { it.name.contains(network, ignoreCase = true) }
        }

        lastError = if (programs.isEmpty() && settings.antennaChannels.isEmpty()) {
            "Pick your local CBS/FOX/NBC/ABC channels in setup (More options → Antenna)."
        } else {
            null
        }
        return channel?.let(::source)
    }

    private fun byNumber(number: String): Channel? {
        val wanted = normalizeNumber(number)
        return channels.firstOrNull { wanted.isNotEmpty() && normalizeNumber(it.number) == wanted }
    }

    private fun source(ch: Channel) = StreamSource(
        url = ch.uri,
        label = "Antenna ${ch.number} ${ch.name}".trim(),
        kind = SourceKind.TUNER,
        inputId = ch.inputId,
        tuneExtras = ch.tuneExtras,
    )

    private suspend fun load() = mutex.withLock {
        if (System.currentTimeMillis() - fetchedAt < CACHE_MS) return@withLock
        withContext(Dispatchers.IO) {
            val tuners = context.getSystemService(TvInputManager::class.java)?.tvInputList.orEmpty()
                .filter { it.type == TvInputInfo.TYPE_TUNER }
            tunerCount = tuners.filter { it.tunerCount > 0 }.minOfOrNull { it.tunerCount } ?: 1
            val tunerIds = tuners.map { it.id }.toSet()

            var denied = false
            val published = try {
                queryChannels().filter { it.inputId in tunerIds }
            } catch (e: SecurityException) {
                denied = true
                emptyList()
            }
            channels = published.ifEmpty { tclChannels(tuners) }
            programs = channels.mapNotNull { ch -> ch.providerId?.let { ch.uri to queryPrograms(it) } }.toMap()
                .filterValues { it.isNotEmpty() }
            lastError = when {
                channels.isNotEmpty() -> null
                tuners.isEmpty() -> "This TV has no built-in tuner."
                denied -> "Allow Fantasy Multiview to read TV channels."
                else -> "No antenna channels yet. Run a channel scan in the TV's Live TV app."
            }
            Log.i(TAG, "tuners=${tuners.map { it.id }} channels=${channels.size} withGuide=${programs.size}")
            fetchedAt = System.currentTimeMillis()
        }
    }

    private fun tclChannels(tuners: List<TvInputInfo>): List<Channel> {
        // Hardware tuner inputs have ids ending in "/HW<n>"; app-provided inputs (Pluto etc.) don't.
        val input = tuners.firstOrNull { it.serviceInfo.packageName == TclChannels.TUNER_PACKAGE && "/HW" in it.id }
            ?: return emptyList()
        val entries = TclChannels.load(context).orEmpty()
        if (entries.isEmpty()) return emptyList()
        val carrier = TclChannels.carrierRowId(context) ?: return emptyList()
        return entries.map { entry ->
            Channel(
                uri = TvContract.buildChannelUri(carrier).toString(),
                inputId = input.id,
                number = entry.number,
                name = entry.name,
                affiliation = null,
                providerId = null,
                tuneExtras = mapOf(TclChannels.KEY_PARAM to entry.key),
            )
        }
    }

    private fun queryChannels(): List<Channel> {
        val projection = arrayOf(
            TvContract.Channels._ID,
            TvContract.Channels.COLUMN_INPUT_ID,
            TvContract.Channels.COLUMN_DISPLAY_NUMBER,
            TvContract.Channels.COLUMN_DISPLAY_NAME,
            TvContract.Channels.COLUMN_NETWORK_AFFILIATION,
            TvContract.Channels.COLUMN_TYPE,
        )
        val result = mutableListOf<Channel>()
        context.contentResolver.query(TvContract.Channels.CONTENT_URI, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                // Only channels from a real scan (antenna or cable), not app-provided "channels".
                val type = c.getString(5)
                if (type == TvContract.Channels.TYPE_OTHER || type == TvContract.Channels.TYPE_PREVIEW) continue
                val id = c.getLong(0)
                result += Channel(
                    uri = TvContract.buildChannelUri(id).toString(),
                    inputId = c.getString(1).orEmpty(),
                    number = c.getString(2).orEmpty(),
                    name = c.getString(3).orEmpty(),
                    affiliation = c.getString(4),
                    providerId = id,
                )
            }
        }
        return result
    }

    private fun queryPrograms(channelId: Long): List<Program> = runCatching {
        val now = System.currentTimeMillis()
        val uri = TvContract.buildProgramsUriForChannel(channelId, now - GUIDE_BACK_MS, now + GUIDE_AHEAD_MS)
        val projection = arrayOf(
            TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS,
            TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS,
            TvContract.Programs.COLUMN_TITLE,
            TvContract.Programs.COLUMN_EPISODE_TITLE,
            TvContract.Programs.COLUMN_SHORT_DESCRIPTION,
        )
        val result = mutableListOf<Program>()
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val text = listOfNotNull(c.getString(2), c.getString(3), c.getString(4)).joinToString(" ")
                result += Program(c.getLong(0), c.getLong(1), text)
            }
        }
        result
    }.getOrElse { emptyList() }

    /** "2-1", "2.1" and "2 1" all mean channel 2.1. */
    private fun normalizeNumber(number: String) = number.trim().replace(Regex("""[\s\-]+"""), ".")

    companion object {
        /** Per-game stream override that puts the TV tuner on a channel: "tv:4.1". */
        const val TV_PREFIX = "tv:"
        private const val TAG = "TvTunerProvider"
        private const val CACHE_MS = 5 * 60_000L
        private const val GUIDE_BACK_MS = 4 * 3_600_000L
        private const val GUIDE_AHEAD_MS = 8 * 3_600_000L
    }
}
