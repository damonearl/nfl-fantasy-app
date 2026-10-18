package com.damonearl.fantasymultiview.streams

import com.damonearl.fantasymultiview.data.GameState
import com.damonearl.fantasymultiview.data.NflGame
import com.damonearl.fantasymultiview.data.NflTeam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
