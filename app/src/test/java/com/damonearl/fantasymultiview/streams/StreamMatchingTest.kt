package com.damonearl.fantasymultiview.streams

import com.damonearl.fantasymultiview.data.GameState
import com.damonearl.fantasymultiview.data.NflGame
import com.damonearl.fantasymultiview.data.NflTeam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class StreamMatchingTest {

    private val game = NflGame(
        id = "1", state = GameState.IN, detail = "4:12 - 3rd", start = null, broadcast = "CBS",
        possession = null, downDistance = null, redZone = false,
        home = NflTeam("BUF", "Bills", "Buffalo", 17),
        away = NflTeam("KC", "Chiefs", "Kansas City", 21),
    )

    @Test fun `scores titles by teams named`() {
        assertEquals(2, GameTitleMatcher.score("NFL: Kansas City Chiefs at Buffalo Bills", game))
        assertEquals(1, GameTitleMatcher.score("Chiefs Pregame Live", game))
        assertEquals(0, GameTitleMatcher.score("Billsfold Network", game))
    }

    @Test fun `parses stream specs with headers`() {
        val s = StreamSource.parse("https://x.test/a.m3u8|Referer=https://r.test/&User-Agent=Foo%20Bar", "l", SourceKind.MANUAL)!!
        assertEquals("https://x.test/a.m3u8", s.url)
        assertEquals(mapOf("Referer" to "https://r.test/", "User-Agent" to "Foo Bar"), s.headers)
        assertNull(StreamSource.parse("  ", "l", SourceKind.MANUAL))
    }

    @Test fun `parses m3u with vlc options`() {
        val channels = M3uStreamProvider.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-name="NFL 1" group-title="NFL",Chiefs vs Bills
            #EXTVLCOPT:http-referrer=https://ref.test/
            https://cdn.test/1.m3u8
            #EXTINF:-1,Other Channel
            https://cdn.test/2.m3u8
            """.trimIndent()
        )
        assertEquals(2, channels.size)
        assertEquals("Chiefs vs Bills", channels[0].title)
        assertEquals(mapOf("Referer" to "https://ref.test/"), channels[0].headers)
        assertEquals(emptyMap<String, String>(), channels[1].headers)
    }

    @Test fun `m3u titles may contain commas`() {
        val channels = M3uStreamProvider.parse(
            """
            #EXTINF:-1 tvg-name="NFL, 05" group-title="NFL",NFL 05: Chiefs vs Bills, 1:00 PM ET
            https://cdn.test/5.m3u8
            """.trimIndent()
        )
        assertEquals("NFL 05: Chiefs vs Bills, 1:00 PM ET", channels.single().title)
    }

    @Test fun `parses xtream links`() {
        val account = XtreamProvider.Account.parse("https://panel.test/get.php?username=me&password=pw&type=m3u_plus&output=ts")!!
        assertEquals("https://panel.test/player_api.php?username=me&password=pw&action=get_live_streams&category_id=7",
            account.api("get_live_streams", "category_id" to "7").toString())
        assertEquals("https://panel.test/live/me/pw/42.ts", account.stream("42", "ts").toString())
        assertEquals("http://panel.test:8080/sub/live/me/pw/1.m3u8",
            XtreamProvider.Account.parse("http://panel.test:8080/sub/player_api.php?username=me&password=pw")!!.stream("1", "m3u8").toString())
        assertNull(XtreamProvider.Account.parse("https://cdn.test/playlist.m3u"))
        assertNull(XtreamProvider.Account.parse("https://panel.test/get.php?password=pw"))
    }

    @Test fun `picks xtream categories that can carry nfl games`() {
        listOf("US| NFL PPV", "US| CBS ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ", "US| SPORT ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ", "US| PEACOCK PPV ⱽᴵᴾ", "US| PRIME ᴿᴬᵂ ⁶⁰ᶠᵖˢ")
            .forEach { assertTrue(it, XtreamProvider.relevantCategory(it)) }
        listOf("US| NFL REPLAY", "US| 24/7 PRIME VIDEO ᴿᴬᵂ ⁶⁰ᶠᵖˢ", "US| ESPN+ PPV ⱽᴵᴾ", "UK| SPORT ᴴᴰ", "US| MOVIES ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ", "US| NFHS PPV")
            .forEach { assertFalse(it, XtreamProvider.relevantCategory(it)) }
    }

    private val lineup = listOf(
        "US: CBS HD", "US: CBS (WSBK) BOSTON HD", "US: CBS 2 (WBBM) CHICAGO HD", "US: CBS 2 (KCBS) LOS ANGELES HD",
        "US: CBS SPORTS NETWORK HD", "US: FOX SPORTS 1 HD", "US: FOX 4 (WDAF) KANSAS CITY HD", "US: NBC SPORTS CHICAGO",
        "US: ESPN NEWS HD", "US: ESPN 2 HD", "US: ESPN HD", "US: NFL NETWORK HD", "NFL  | 03 -", "MLB: Chicago Cubs vs Giants",
    )

    private fun team(abbrev: String, name: String, location: String) = NflTeam(abbrev, name, location, null)

    private fun nflGame(id: String, away: NflTeam, home: NflTeam, broadcast: String, start: Instant = Instant.parse("2026-10-25T17:00:00Z")) =
        game.copy(id = id, away = away, home = home, broadcast = broadcast, state = GameState.PRE, start = start)

    @Test fun `matches regional network games to a home-market affiliate`() {
        val neChi = nflGame("2", team("NE", "Patriots", "New England"), team("CHI", "Bears", "Chicago"), "CBS")
        val other = nflGame("3", team("LAR", "Rams", "Los Angeles"), team("NYJ", "Jets", "New York"), "CBS")
        val all = listOf(neChi, other)
        assertEquals("US: CBS 2 (WBBM) CHICAGO HD", ChannelMatcher.best(lineup, { it }, neChi, all))
        assertEquals("US: CBS 2 (KCBS) LOS ANGELES HD", ChannelMatcher.best(lineup, { it }, other, all))
        // Two CBS games at once, neither team's market listed: can't tell which feed has it.
        val buf = nflGame("4", team("BUF", "Bills", "Buffalo"), team("MIA", "Dolphins", "Miami"), "CBS")
        assertNull(ChannelMatcher.best(lineup, { it }, buf, all + buf))
        // Alone on CBS in its window, so it's national: the generic feed.
        assertEquals("US: CBS HD", ChannelMatcher.best(lineup, { it }, buf, listOf(buf)))
    }

    @Test fun `matches national broadcasts and event channels`() {
        val kcDen = nflGame("5", team("KC", "Chiefs", "Kansas City"), team("DEN", "Broncos", "Denver"), "ESPN")
        assertEquals("US: ESPN HD", ChannelMatcher.best(lineup, { it }, kcDen, listOf(kcDen)))
        assertEquals("US: ESPN 2 HD", ChannelMatcher.best(lineup, { it }, kcDen.copy(broadcast = "ESPN2"), listOf(kcDen)))
        assertEquals("US: NFL NETWORK HD", ChannelMatcher.best(lineup, { it }, kcDen.copy(broadcast = "NFL Net"), listOf(kcDen)))
        val event = "NFL  | 03 - Kansas City Chiefs vs Denver Broncos @ 8:15 PM"
        assertEquals(event, ChannelMatcher.best(lineup + event, { it }, kcDen.copy(broadcast = "Prime Video"), listOf(kcDen)))
        // One team named isn't enough: "Giants" here is baseball.
        val nyg = nflGame("6", team("NYG", "Giants", "New York"), team("PHI", "Eagles", "Philadelphia"), "Prime Video")
        assertNull(ChannelMatcher.best(lineup, { it }, nyg, listOf(nyg)))
    }

    @Test fun `ranks every plausible channel for the stream list`() {
        val neChi = nflGame("2", team("NE", "Patriots", "New England"), team("CHI", "Bears", "Chicago"), "CBS")
        val event = "NFL  | 03 - Patriots vs Bears"
        // Event channel, home then away affiliates, then (alone on CBS) the generic feed.
        assertEquals(
            listOf(event, "US: CBS 2 (WBBM) CHICAGO HD", "US: CBS (WSBK) BOSTON HD", "US: CBS HD"),
            ChannelMatcher.ranked(lineup + event, { it }, neChi, listOf(neChi)),
        )
    }
}
