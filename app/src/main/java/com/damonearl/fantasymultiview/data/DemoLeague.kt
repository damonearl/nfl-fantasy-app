package com.damonearl.fantasymultiview.data

/**
 * League id "demo": a synthetic matchup built from this week's real NFL games, so the layout and
 * streams can be tried without an ESPN league. Player points track the real team scores.
 */
object DemoLeague {
    const val ID = "demo"

    private val lineup = listOf("QB" to 0, "RB" to 2, "RB" to 2, "WR" to 4, "WR" to 4, "TE" to 6, "FLEX" to 23, "D/ST" to 16, "K" to 17)

    fun build(games: List<NflGame>, week: Int): FantasyLeague {
        // Spread teams from live games first so the grid has something to show.
        val teams = games.sortedBy { listOf(GameState.IN, GameState.PRE, GameState.POST).indexOf(it.state) }
            .flatMap { listOf(it.home, it.away) }
        val mineTeams = teams.filterIndexed { i, _ -> i % 2 == 0 }
        val oppTeams = teams.filterIndexed { i, _ -> i % 2 == 1 }

        fun side(id: Int, name: String, pool: List<NflTeam>): FantasySide {
            val starters = lineup.mapIndexed { i, (slot, slotId) ->
                val team = pool.getOrNull(i % pool.size.coerceAtLeast(1))
                val score = team?.score ?: 0
                FantasyPlayer(
                    id = id * 100 + i,
                    name = "${team?.name ?: "Free"} ${if (slot == "FLEX") "WR2" else slot}",
                    position = if (slot == "FLEX") "WR" else slot,
                    slot = slot,
                    slotId = slotId,
                    proTeam = team?.abbrev.orEmpty(),
                    points = score * (0.35 + (i % 4) * 0.12),
                    projected = 8.0 + (i % 5) * 3.5,
                    injuryStatus = null,
                )
            }
            return FantasySide(id, name, name.take(4).uppercase(), starters.sumOf { it.points }, starters.sumOf { it.projected ?: 0.0 }, starters, emptyList())
        }

        val mine = side(1, "Demo Dynasty", mineTeams)
        val opp = side(2, "Rival Squad", oppTeams)
        return FantasyLeague(
            leagueName = "Demo League",
            scoringPeriod = week,
            matchupPeriod = week,
            teams = listOf(FantasyTeamInfo(1, mine.name, mine.abbrev, emptyList()), FantasyTeamInfo(2, opp.name, opp.abbrev, emptyList())),
            matchup = FantasyMatchup(mine, opp),
        )
    }
}
