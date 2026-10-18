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
 * Antenna channels from the TV's own tuner, via Android's TV Input Framework. Channels and guide
 * data come from the system TV provider (filled in by the TV's channel scan); playback is a TvView.
 */
class TvTunerProvider(private val context: Context) : StreamProvider {

    private class Channel(val id: Long, val inputId: String, val number: String, val name: String, val affiliation: String?)
    private class Program(val start: Long, val end: Long, val text: String)

    private val mutex = Mutex()
    private var channels: List<Channel> = emptyList()
    private var programs: Map<Long, List<Program>> = emptyMap()
    private var fetchedAt = 0L

    /** Simultaneous channels the tuner can show; most TVs have one. */
    @Volatile var tunerCount = 1
        private set

    @Volatile var lastError: String? = null
        private set

    val hasChannels get() = channels.isNotEmpty()

    override suspend fun find(game: NflGame, allGames: List<NflGame>, settings: AppSettings): StreamSource? {
        if (!settings.antennaEnabled || game.state == GameState.POST) return null
        load()
        if (channels.isEmpty()) return null

        val at = OtaMatching.airTime(game).toEpochMilli()
        val channel = channels.firstOrNull { ch ->
            programs[ch.id].orEmpty().any { it.start <= at && at < it.end && OtaMatching.programMatches(it.text, game) }
        } ?: OtaMatching.uniqueNetwork(game, allGames)?.let { network ->
            val pinned = settings.antennaChannels[network]?.let(::normalizeNumber)
            channels.firstOrNull { pinned != null && normalizeNumber(it.number) == pinned }
                ?: channels.firstOrNull { it.affiliation?.contains(network, ignoreCase = true) == true }
                ?: channels.firstOrNull { it.name.contains(network, ignoreCase = true) }
        } ?: return null

        return StreamSource(
            url = TvContract.buildChannelUri(channel.id).toString(),
            label = "Antenna ${channel.number} ${channel.name}".trim(),
            kind = SourceKind.TUNER,
            inputId = channel.inputId,
        )
    }

    private suspend fun load() = mutex.withLock {
        if (System.currentTimeMillis() - fetchedAt < CACHE_MS) return@withLock
        withContext(Dispatchers.IO) {
            val tuners = context.getSystemService(TvInputManager::class.java)?.tvInputList.orEmpty()
                .filter { it.type == TvInputInfo.TYPE_TUNER }
            tunerCount = tuners.filter { it.tunerCount > 0 }.minOfOrNull { it.tunerCount } ?: 1
            val tunerIds = tuners.map { it.id }.toSet()

            channels = try {
                queryChannels().filter { it.inputId in tunerIds }
            } catch (e: SecurityException) {
                lastError = "Allow Fantasy Multiview to read TV channels."
                emptyList()
            }
            programs = channels.associate { it.id to queryPrograms(it.id) }
            lastError = when {
                tuners.isEmpty() -> "This TV has no built-in tuner."
                channels.isEmpty() -> lastError ?: "No antenna channels yet. Run a channel scan in the TV's Live TV app."
                else -> null
            }
            Log.i(TAG, "tuners=${tuners.map { it.id }} channels=${channels.size} withGuide=${programs.count { it.value.isNotEmpty() }}")
            fetchedAt = System.currentTimeMillis()
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
                result += Channel(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getString(3).orEmpty(), c.getString(4))
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

    private companion object {
        const val TAG = "TvTunerProvider"
        const val CACHE_MS = 5 * 60_000L
        const val GUIDE_BACK_MS = 4 * 3_600_000L
        const val GUIDE_AHEAD_MS = 8 * 3_600_000L
    }
}
