package com.damonearl.fantasymultiview.streams

import android.util.Log
import com.damonearl.fantasymultiview.data.AppSettings
import com.damonearl.fantasymultiview.data.NflGame
import com.damonearl.fantasymultiview.data.NflTeam
import kotlinx.coroutines.CancellationException

enum class SourceKind { MANUAL, ANTENNA, PLAYLIST }

data class StreamSource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val label: String,
    val kind: SourceKind,
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
    /** Returns a stream for [game], or null if this provider has nothing for it. */
    suspend fun find(game: NflGame, allGames: List<NflGame>, settings: AppSettings): StreamSource?
}

/** Asks providers in priority order and returns every match, best first. */
class StreamResolver(private val providers: List<StreamProvider>) {
    suspend fun candidates(game: NflGame, allGames: List<NflGame>, settings: AppSettings): List<StreamSource> =
        providers.mapNotNull { provider ->
            try {
                provider.find(game, allGames, settings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("StreamResolver", "${provider::class.simpleName} failed for ${game.matchupLabel}", e)
                null
            }
        }
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
