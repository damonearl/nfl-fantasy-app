package com.damonearl.fantasymultiview.streams

import com.damonearl.fantasymultiview.data.GameState
import com.damonearl.fantasymultiview.data.NflGame
import java.time.Duration
import java.time.Instant

/**
 * Shared rules for placing a game on an over-the-air channel. Local affiliates air one regional
 * game per network window, so a network alone only identifies a game when it's the sole game
 * that network has in the window.
 */
object OtaMatching {
    val NETWORKS = listOf("CBS", "FOX", "NBC", "ABC")

    /** When to look the game up in a guide: now if it's live, else just after kickoff. */
    fun airTime(game: NflGame): Instant =
        if (game.state == GameState.IN) Instant.now() else (game.start ?: Instant.now()).plusSeconds(300)

    fun network(game: NflGame): String? {
        val b = game.broadcast?.uppercase() ?: return null
        return NETWORKS.firstOrNull { Regex("""\b$it\b""").containsMatchIn(b) }
    }

    /** The game's network, if no other game airs on that network in the same window. */
    fun uniqueNetwork(game: NflGame, allGames: List<NflGame>): String? {
        val network = network(game) ?: return null
        val window = airTime(game)
        val competing = allGames.count { other ->
            network(other) == network && other.state != GameState.POST &&
                Duration.between(airTime(other), window).abs() < Duration.ofMinutes(90)
        }
        return network.takeIf { competing <= 1 }
    }

    /** A guide entry identifies the game only if it names both teams. */
    fun programMatches(text: String, game: NflGame) = GameTitleMatcher.score(text, game) == 2
}
