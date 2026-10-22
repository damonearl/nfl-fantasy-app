package com.damonearl.fantasymultiview.streams

import android.util.Log
import com.damonearl.fantasymultiview.data.AppSettings
import com.damonearl.fantasymultiview.data.NflGame
import com.damonearl.fantasymultiview.data.NflTeam
import kotlinx.coroutines.CancellationException

/** Sent to IPTV hosts, many of which refuse OkHttp's default user agent. */
const val PLAYER_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

enum class SourceKind {
    MANUAL,
    /** HDHomeRun network tuner (an HTTP stream). */
    ANTENNA,
    /** The TV's built-in tuner; [StreamSource.url] is a channel URI played through a TvView. */
    TUNER,
    PLAYLIST,
}

data class StreamSource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val label: String,
    val kind: SourceKind,
    /** TV input that owns the channel, for [SourceKind.TUNER] sources. */
    val inputId: String? = null,
    /** Extra tune parameters some vendor tuners read, e.g. TCL's "dbprimarykey". */
    val tuneExtras: Map<String, Int> = emptyMap(),
    /** May be showing a different game, e.g. a local affiliate while its network has several games on. */
    val unsure: Boolean = false,
) {
    companion object {
        /** Parses "url" or Kodi-style "url|Referer=...&User-Agent=..." specs. */
        fun parse(spec: String, label: String, kind: SourceKind): StreamSource? {
            val trimmed = spec.trim()
            if (trimmed.isEmpty()) return null
            val url = trimmed.substringBefore('|')
            val headers = trimmed.substringAfter('|', "")
                .split('&')
                .mapNotNull { pair ->
                    val key = pair.substringBefore('=', "").trim()
                    val value = pair.substringAfter('=', "").trim()
                    if (key.isEmpty()) null else key to java.net.URLDecoder.decode(value, "UTF-8")
                }
                .toMap()
            return StreamSource(url, headers, label, kind)
        }
    }
}

interface StreamProvider {
    /** Returns the best stream for [game], or null if this provider has nothing for it. */
    suspend fun find(game: NflGame, allGames: List<NflGame>, settings: AppSettings): StreamSource?

    /** Every stream this provider has that plausibly carries [game], best first. */
    suspend fun options(game: NflGame, allGames: List<NflGame>, settings: AppSettings): List<StreamSource> =
        listOfNotNull(find(game, allGames, settings))
}

/** Asks providers in priority order and returns every match, best first; unsure ones go last. */
class StreamResolver(private val providers: List<StreamProvider>) {
    suspend fun candidates(game: NflGame, allGames: List<NflGame>, settings: AppSettings): List<StreamSource> =
        providers.flatMap { provider ->
            try {
                provider.options(game, allGames, settings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("StreamResolver", "${provider::class.simpleName} failed for ${game.matchupLabel}", e)
                emptyList()
            }
        }.distinctBy { it.url }.sortedBy { it.unsure }
}

/** Scores how well a channel/program title names a game: 2 = both teams, 1 = one team, 0 = neither. */
object GameTitleMatcher {
    fun score(title: String, game: NflGame): Int {
        val t = title.lowercase()
        return listOf(game.home, game.away).count { mentions(t, it) }
    }

    private fun mentions(title: String, team: NflTeam): Boolean {
        if (team.name.isBlank()) return false
        return Regex("""\b${Regex.escape(team.name.lowercase())}\b""").containsMatchIn(title)
    }
}
