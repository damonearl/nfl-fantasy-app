package com.damonearl.fantasymultiview.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class EspnException(message: String) : IOException(message)

/**
 * Reads league, matchup and live box-score data from ESPN's (unofficial) fantasy v3 API.
 * Private leagues need the espn_s2 + SWID cookies from a logged-in browser session.
 */
class EspnFantasyClient(private val http: OkHttpClient, private val json: Json) {

    suspend fun fetch(settings: AppSettings): FantasyLeague = withContext(Dispatchers.IO) {
        val base = "$HOST/apis/v3/games/ffl/seasons/${settings.season}/segments/0/leagues/${settings.leagueId.trim()}"

        val league = get<LeagueDto>("$base?view=mTeam&view=mSettings", settings)
        val week = league.scoringPeriodId
        val period = league.status.currentMatchupPeriod.takeIf { it > 0 } ?: week

        val teams = league.teams.map { t ->
            FantasyTeamInfo(id = t.id, name = t.displayName, abbrev = t.abbrev.orEmpty(), owners = t.owners)
        }
        val myTeamId = settings.teamId
            ?: normalizeSwid(settings.swid).takeIf { it.isNotEmpty() }?.let { swid ->
                teams.firstOrNull { team -> team.owners.any { it.equals(swid, ignoreCase = true) } }?.id
            }

        val matchup = myTeamId?.let { id ->
            val box = get<LeagueDto>(
                "$base?view=mMatchupScore&view=mScoreboard&scoringPeriodId=$week",
                settings,
                filter = """{"schedule":{"filterMatchupPeriodIds":{"value":[$period]}}}""",
            )
            val game = box.schedule.firstOrNull { it.home?.teamId == id || it.away?.teamId == id }
                ?: return@let null
            val (mine, opp) = if (game.home?.teamId == id) game.home to game.away else game.away!! to game.home
            val teamById = league.teams.associateBy { it.id }
            FantasyMatchup(
                mine = mine.toSide(teamById[mine.teamId], week),
                opponent = opp?.let { it.toSide(teamById[it.teamId], week) },
            )
        }

        FantasyLeague(
            leagueName = league.settings?.name,
            scoringPeriod = week,
            matchupPeriod = period,
            teams = teams,
            matchup = matchup,
        )
    }

    private inline fun <reified T> get(url: String, settings: AppSettings, filter: String? = null): T {
        val request = Request.Builder().url(url).apply {
            header("Accept", "application/json")
            if (settings.hasCookies) {
                header("Cookie", "espn_s2=${settings.espnS2.trim()}; SWID=${normalizeSwid(settings.swid)}")
            }
            filter?.let { header("x-fantasy-filter", it) }
        }.build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.code == 401 || response.code == 403 -> throw EspnException(
                    if (settings.hasCookies) "ESPN rejected the espn_s2/SWID cookies. They may have expired."
                    else "This league is private. Add your espn_s2 and SWID cookies."
                )
                response.code == 404 -> throw EspnException("League ${settings.leagueId} not found for ${settings.season}.")
                !response.isSuccessful -> throw EspnException("ESPN returned HTTP ${response.code}.")
                !body.trimStart().startsWith("{") -> throw EspnException("ESPN returned an unexpected response.")
            }
            return json.decodeFromString(body)
        }
    }

    private fun SideDto.toSide(team: TeamDto?, week: Int): FantasySide {
        val players = rosterForCurrentScoringPeriod?.entries.orEmpty().mapNotNull { it.toPlayer(week) }
        val (bench, starters) = players.partition { it.slotId == SLOT_BENCH || it.slotId == SLOT_IR }
        val projectedFromPlayers = starters.mapNotNull { it.projected }.takeIf { it.isNotEmpty() }?.sum()
        return FantasySide(
            teamId = teamId,
            name = team?.displayName ?: "Team $teamId",
            abbrev = team?.abbrev.orEmpty(),
            total = totalPointsLive ?: rosterForCurrentScoringPeriod?.appliedStatTotal ?: totalPoints,
            projected = totalProjectedPointsLive ?: projectedFromPlayers,
            starters = starters.sortedBy { SLOT_ORDER.indexOf(it.slotId).let { i -> if (i < 0) 99 else i } },
            bench = bench,
        )
    }

    private fun EntryDto.toPlayer(week: Int): FantasyPlayer? {
        val pool = playerPoolEntry ?: return null
        val p = pool.player
        val weekStats = p.stats.filter { it.scoringPeriodId == week }
        return FantasyPlayer(
            id = p.id,
            name = p.fullName,
            position = POSITIONS[p.defaultPositionId] ?: "",
            slot = SLOTS[lineupSlotId] ?: "",
            slotId = lineupSlotId,
            proTeam = PRO_TEAMS[p.proTeamId].orEmpty(),
            points = weekStats.firstOrNull { it.statSourceId == 0 }?.appliedTotal ?: pool.appliedStatTotal,
            projected = weekStats.firstOrNull { it.statSourceId == 1 }?.appliedTotal,
            injuryStatus = p.injuryStatus?.takeIf { it != "ACTIVE" && it != "NORMAL" },
        )
    }

    companion object {
        private const val HOST = "https://lm-api-reads.fantasy.espn.com"
        private const val SLOT_BENCH = 20
        private const val SLOT_IR = 21

        fun normalizeSwid(raw: String): String {
            val s = raw.trim().removePrefix("{").removeSuffix("}")
            return if (s.isEmpty()) "" else "{$s}"
        }

        private val SLOTS = mapOf(
            0 to "QB", 1 to "TQB", 2 to "RB", 3 to "R/W", 4 to "WR", 5 to "W/T", 6 to "TE", 7 to "OP",
            8 to "DT", 9 to "DE", 10 to "LB", 11 to "DL", 12 to "CB", 13 to "S", 14 to "DB", 15 to "DP",
            16 to "D/ST", 17 to "K", 18 to "P", 19 to "HC", 20 to "BE", 21 to "IR", 23 to "FLEX",
        )
        private val SLOT_ORDER = listOf(0, 1, 2, 4, 6, 3, 5, 23, 7, 16, 17, 18, 19, 8, 9, 10, 11, 12, 13, 14, 15)
        private val POSITIONS = mapOf(1 to "QB", 2 to "RB", 3 to "WR", 4 to "TE", 5 to "K", 7 to "P", 16 to "D/ST")

        /** ESPN fantasy proTeamId -> scoreboard abbreviation. */
        private val PRO_TEAMS = mapOf(
            1 to "ATL", 2 to "BUF", 3 to "CHI", 4 to "CIN", 5 to "CLE", 6 to "DAL", 7 to "DEN", 8 to "DET",
            9 to "GB", 10 to "TEN", 11 to "IND", 12 to "KC", 13 to "LV", 14 to "LAR", 15 to "MIA", 16 to "MIN",
            17 to "NE", 18 to "NO", 19 to "NYG", 20 to "NYJ", 21 to "PHI", 22 to "ARI", 23 to "PIT", 24 to "LAC",
            25 to "SF", 26 to "SEA", 27 to "TB", 28 to "WSH", 29 to "CAR", 30 to "JAX", 33 to "BAL", 34 to "HOU",
        )
    }
}

@Serializable
private data class LeagueDto(
    val scoringPeriodId: Int = 1,
    val status: StatusDto = StatusDto(),
    val settings: LeagueSettingsDto? = null,
    val teams: List<TeamDto> = emptyList(),
    val schedule: List<MatchupDto> = emptyList(),
)

@Serializable
private data class StatusDto(val currentMatchupPeriod: Int = 0)

@Serializable
private data class LeagueSettingsDto(val name: String? = null)

@Serializable
private data class TeamDto(
    val id: Int,
    val name: String? = null,
    val location: String? = null,
    val nickname: String? = null,
    val abbrev: String? = null,
    val owners: List<String> = emptyList(),
) {
    val displayName
        get() = name?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(location, nickname).joinToString(" ").ifBlank { "Team $id" }
}

@Serializable
private data class MatchupDto(
    val matchupPeriodId: Int = 0,
    val home: SideDto? = null,
    val away: SideDto? = null,
)

@Serializable
private data class SideDto(
    val teamId: Int,
    val totalPoints: Double = 0.0,
    val totalPointsLive: Double? = null,
    val totalProjectedPointsLive: Double? = null,
    val rosterForCurrentScoringPeriod: RosterDto? = null,
)

@Serializable
private data class RosterDto(
    val appliedStatTotal: Double? = null,
    val entries: List<EntryDto> = emptyList(),
)

@Serializable
private data class EntryDto(
    val playerId: Int = 0,
    val lineupSlotId: Int = 20,
    val playerPoolEntry: PoolEntryDto? = null,
)

@Serializable
private data class PoolEntryDto(
    val appliedStatTotal: Double = 0.0,
    val player: PlayerDto,
)

@Serializable
private data class PlayerDto(
    val id: Int,
    val fullName: String = "",
    val proTeamId: Int = 0,
    val defaultPositionId: Int = 0,
    val injuryStatus: String? = null,
    val stats: List<StatDto> = emptyList(),
)

@Serializable
private data class StatDto(
    val scoringPeriodId: Int = 0,
    val statSourceId: Int = 0,
    val appliedTotal: Double = 0.0,
)
