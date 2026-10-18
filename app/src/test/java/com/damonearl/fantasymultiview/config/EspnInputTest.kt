package com.damonearl.fantasymultiview.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EspnInputTest {

    private val s2 = "AEBx%2Fq7Zt0abcDEF1234567890abcdefghijklmnopqrstuvwxyz%2B0123456789ABCDEFGHIJKLMNOPQRSTUV%3D%3D"
    private val guid = "1A2B3C4D-5E6F-4A1B-9C2D-3E4F5A6B7C8D"

    @Test fun `parses league urls and ids`() {
        assertEquals(
            EspnInput.League("123456", 2026),
            EspnInput.parseLeague("https://fantasy.espn.com/football/league?leagueId=123456&seasonId=2026"),
        )
        assertEquals(EspnInput.League("98765", null), EspnInput.parseLeague("https://fantasy.espn.com/football/team?leagueId=98765&teamId=3"))
        assertEquals(EspnInput.League("4242", null), EspnInput.parseLeague(" 4242 "))
        assertEquals(EspnInput.League("demo", null), EspnInput.parseLeague("Demo"))
        assertNull(EspnInput.parseLeague("my league"))
    }

    @Test fun `parses two bare values in either order`() {
        assertEquals(EspnInput.Cookies(s2, "{$guid}"), EspnInput.parseCookies("$s2\n{$guid}"))
        assertEquals(EspnInput.Cookies(s2, "{$guid}"), EspnInput.parseCookies("{$guid}  $s2"))
    }

    @Test fun `parses cookie header and devtools rows`() {
        assertEquals(
            EspnInput.Cookies(s2, "{$guid}"),
            EspnInput.parseCookies("country=us; SWID={$guid}; espn_s2=$s2; region=unknown"),
        )
        assertEquals(
            EspnInput.Cookies(s2, "{$guid}"),
            EspnInput.parseCookies("espn_s2\t$s2\t.espn.com\t/\n SWID\t{$guid}\t.espn.com\t/"),
        )
    }

    @Test fun `missing values come back null`() {
        assertEquals(EspnInput.Cookies(null, "{$guid}"), EspnInput.parseCookies(guid))
        assertEquals(EspnInput.Cookies(null, null), EspnInput.parseCookies(""))
    }
}
