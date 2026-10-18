package com.damonearl.fantasymultiview.data

import java.time.Instant

data class FantasyPlayer(
    val id: Int,
    val name: String,
    val position: String,
    val slot: String,
    val slotId: Int,
    /** NFL team abbreviation in ESPN scoreboard form, e.g. "KC", "WSH". Empty for free agents. */
    val proTeam: String,
    val points: Double,
    val projected: Double?,
    val injuryStatus: String?,
)

data class FantasySide(
    val teamId: Int,
    val name: String,
    val abbrev: String,
    val total: Double,
    val projected: Double?,
    val starters: List<FantasyPlayer>,
    val bench: List<FantasyPlayer>,
)

data class FantasyMatchup(
    val mine: FantasySide,
    val opponent: FantasySide?,
)

data class FantasyTeamInfo(
    val id: Int,
    val name: String,
    val abbrev: String,
    val owners: List<String>,
)

data class FantasyLeague(
    val leagueName: String?,
    val scoringPeriod: Int,
    val matchupPeriod: Int,
    val teams: List<FantasyTeamInfo>,
    /** Null until the user's team is known (configured team id, or matched via SWID). */
    val matchup: FantasyMatchup?,
)

data class NflTeam(
    val abbrev: String,
    /** Nickname, e.g. "Chiefs". */
    val name: String,
    /** City, e.g. "Kansas City". */
    val location: String,
    val score: Int?,
)

enum class GameState { PRE, IN, POST }

data class NflGame(
    val id: String,
    val home: NflTeam,
    val away: NflTeam,
    val state: GameState,
    /** ESPN short status, e.g. "4:12 - 3rd", "Halftime", "Final". */
    val detail: String,
    val start: Instant?,
    val broadcast: String?,
    val possession: String?,
    val downDistance: String?,
    val redZone: Boolean,
) {
    fun involves(abbrev: String) = home.abbrev == abbrev || away.abbrev == abbrev
    val matchupLabel get() = "${away.abbrev} @ ${home.abbrev}"
}
