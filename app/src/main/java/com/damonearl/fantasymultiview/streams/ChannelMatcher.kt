package com.damonearl.fantasymultiview.streams

import com.damonearl.fantasymultiview.data.NflGame
import com.damonearl.fantasymultiview.data.NflTeam

/**
 * Picks the IPTV channels showing a game from channel names alone. IPTV lists name most channels by
 * network and city ("US: CBS 2 (WBBM) CHICAGO HD"); only event channels name the teams, and those
 * usually get their title shortly before kickoff.
 */
object ChannelMatcher {

    fun <T> best(channels: List<T>, name: (T) -> String, game: NflGame, allGames: List<NflGame>): T? =
        ranked(channels, name, game, allGames).firstOrNull()

    /** Channels that plausibly carry the game, most likely first. */
    fun <T> ranked(channels: List<T>, name: (T) -> String, game: NflGame, allGames: List<NflGame>): List<T> {
        val result = LinkedHashSet<T>()

        // Event channels naming both teams.
        val teams = listOf(game.home, game.away).filter { it.name.isNotBlank() }.map { word(it.name) }
        if (teams.size == 2) channels.filterTo(result) { ch -> teams.all { it.containsMatchIn(name(ch)) } }

        OtaMatching.network(game)?.let { network ->
            val pattern = NETWORK_CHANNEL.getValue(network)
            val affiliates = channels.filter { pattern.containsMatchIn(name(it)) }
            // A team's home-market affiliate always carries its game, even in a regional window.
            (markets(game.home) + markets(game.away)).map(::word).forEach { market ->
                affiliates.filterTo(result) { market.containsMatchIn(name(it)) }
            }
            // A game alone on its network in the window is national, so any affiliate has it;
            // the shortest names are usually the generic feeds ("US: CBS HD").
            if (OtaMatching.uniqueNetwork(game, allGames) != null) {
                result += affiliates.sortedBy { name(it).length }.take(GENERIC_FEEDS)
            }
        }

        game.broadcast?.let { broadcast ->
            NATIONAL.firstOrNull { it.first.containsMatchIn(broadcast) }?.let { (_, channel) ->
                result += channels.filter { channel.containsMatchIn(name(it)) }.sortedBy { name(it).length }.take(GENERIC_FEEDS)
            }
        }
        return result.take(MAX_OPTIONS)
    }

    /** Cities a team's local affiliates are named after. */
    fun markets(team: NflTeam): List<String> =
        MARKETS[team.location] ?: listOfNotNull(team.location.takeIf { it.isNotBlank() })

    private fun word(text: String) = Regex("""\b${Regex.escape(text)}\b""", RegexOption.IGNORE_CASE)

    private const val GENERIC_FEEDS = 3
    private const val MAX_OPTIONS = 12

    /** "CBS 2 (KCBS) LOS ANGELES", not "CBS SPORTS NETWORK" or "FOX NEWS". */
    private val NETWORK_CHANNEL = OtaMatching.NETWORKS.associateWith {
        Regex("""\b$it\b(?!\s*(SPORTS?|NEWS|BUSINESS|WEATHER|DEPORTES|SOCCER))""", RegexOption.IGNORE_CASE)
    }

    /** ESPN team locations that aren't the city their TV stations are named after. */
    private val MARKETS = mapOf(
        "Arizona" to listOf("Phoenix"),
        "Carolina" to listOf("Charlotte"),
        "Green Bay" to listOf("Green Bay", "Milwaukee"),
        "Minnesota" to listOf("Minneapolis"),
        "New England" to listOf("Boston"),
        "Tampa Bay" to listOf("Tampa"),
        "Tennessee" to listOf("Nashville"),
    )

    /** Broadcaster on the ESPN scoreboard -> its single national channel. */
    private val NATIONAL = listOf(
        Regex("""\bESPN\s?2\b""", RegexOption.IGNORE_CASE) to Regex("""\bESPN\s?2\b""", RegexOption.IGNORE_CASE),
        Regex("""\bESPN\b""", RegexOption.IGNORE_CASE) to
            Regex("""\bESPN\b(?!\s*(2|U\b|NEWS|DEPORTES|CLASSIC|\+|PLUS))""", RegexOption.IGNORE_CASE),
        Regex("""\bNFL\s*NET""", RegexOption.IGNORE_CASE) to Regex("""\bNFL\s*NETWORK\b""", RegexOption.IGNORE_CASE),
    )
}
