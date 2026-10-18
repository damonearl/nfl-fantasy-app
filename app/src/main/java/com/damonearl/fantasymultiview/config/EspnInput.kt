package com.damonearl.fantasymultiview.config

import com.damonearl.fantasymultiview.data.DemoLeague

/** Forgiving parsers for what people actually paste into the setup page. */
object EspnInput {

    data class League(val id: String, val season: Int?)

    /** Accepts a league id, "demo", or any fantasy.espn.com URL containing leagueId=… (and maybe seasonId=…). */
    fun parseLeague(input: String): League? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (text.equals(DemoLeague.ID, ignoreCase = true)) return League(DemoLeague.ID, null)
        Regex("""leagueId=(\d+)""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val season = Regex("""seasonId=(\d{4})""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toInt()
            return League(m.groupValues[1], season)
        }
        return text.takeIf { it.all(Char::isDigit) }?.let { League(it, null) }
    }

    data class Cookies(val espnS2: String?, val swid: String?)

    /**
     * Pulls espn_s2 and SWID out of whatever was pasted: the two bare values in any order,
     * "name=value" pairs, a whole Cookie header, or rows copied from a DevTools cookie table.
     */
    fun parseCookies(input: String): Cookies {
        val text = input.trim()
        val swid = SWID.find(text)?.groupValues?.get(1)?.let { "{$it}" }
        val s2 = Regex("""espn_s2\s*[=:\s]\s*"?([^\s";]{40,})""").find(text)?.groupValues?.get(1)
            ?: LONG_TOKEN.findAll(text).map { it.value }.firstOrNull { SWID.find(it) == null }
        return Cookies(s2, swid)
    }

    private val SWID = Regex("""\{?([0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12})}?""")
    private val LONG_TOKEN = Regex("""[A-Za-z0-9%+/=_.\-]{80,}""")
}
