package com.damonearl.fantasymultiview.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.damonearl.fantasymultiview.config.ConfigServer
import com.damonearl.fantasymultiview.config.SetupStatus
import com.damonearl.fantasymultiview.data.AppSettings
import com.damonearl.fantasymultiview.data.DemoLeague
import com.damonearl.fantasymultiview.data.EspnFantasyClient
import com.damonearl.fantasymultiview.data.FailedRequestLogger
import com.damonearl.fantasymultiview.data.FantasyLeague
import com.damonearl.fantasymultiview.data.FantasyMatchup
import com.damonearl.fantasymultiview.data.GameState
import com.damonearl.fantasymultiview.data.NflGame
import com.damonearl.fantasymultiview.data.NflScoreboardClient
import com.damonearl.fantasymultiview.data.SettingsStore
import com.damonearl.fantasymultiview.player.PlayerPool
import com.damonearl.fantasymultiview.streams.HdHomeRunProvider
import com.damonearl.fantasymultiview.streams.M3uStreamProvider
import com.damonearl.fantasymultiview.streams.ManualStreamProvider
import com.damonearl.fantasymultiview.streams.SourceKind
import com.damonearl.fantasymultiview.streams.StreamResolver
import com.damonearl.fantasymultiview.streams.StreamSource
import com.damonearl.fantasymultiview.streams.TvTunerProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.time.Instant
import java.util.concurrent.TimeUnit

data class GameTile(val game: NflGame, val stream: StreamSource?)

data class UiState(
    val settings: AppSettings = AppSettings(),
    val league: FantasyLeague? = null,
    val games: List<NflGame> = emptyList(),
    val tiles: List<GameTile> = emptyList(),
    /** Relevant games that didn't make the grid (overflow, finished, upcoming). */
    val otherGames: List<NflGame> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val antennaError: String? = null,
    val lastUpdated: Instant? = null,
) {
    val matchup: FantasyMatchup? get() = league?.matchup
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addNetworkInterceptor(FailedRequestLogger())
        .build()
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    val settingsStore = SettingsStore(app)
    private val fantasy = EspnFantasyClient(http, json)
    private val scoreboard = NflScoreboardClient(http, json)
    private val tuner = TvTunerProvider(app)
    // HDHomeRun is only searched for when the TV's own tuner has nothing, or an address is set.
    private val antenna = HdHomeRunProvider(http, json, shouldRun = { it.antennaHost.isNotBlank() || !tuner.hasTuner })
    private val streams = StreamResolver(listOf(ManualStreamProvider(), tuner, antenna, M3uStreamProvider(http)))
    val players = PlayerPool(app, http)

    private val _state = MutableStateFlow(UiState(settings = settingsStore.settings.value))
    val state: StateFlow<UiState> = _state.asStateFlow()

    val configServer = ConfigServer(
        store = settingsStore,
        teams = { _state.value.league?.teams.orEmpty() },
        games = { _state.value.games },
        status = { setupStatus(_state.value) },
        antennaChoices = { tuner.channelChoices },
    )

    init {
        configServer.start()
        viewModelScope.launch {
            // collectLatest restarts the poll loop immediately whenever settings change.
            settingsStore.settings.collectLatest { settings ->
                _state.update {
                    // Drop results from the old league so setup shows "checking" until the new one answers.
                    val sameLeague = it.settings.leagueId == settings.leagueId && it.settings.season == settings.season
                    it.copy(settings = settings, error = null, league = it.league.takeIf { sameLeague })
                }
                if (!settings.isConfigured) return@collectLatest
                while (true) {
                    refresh(settings)
                    delay(POLL_MS)
                }
            }
        }
    }

    private fun setupStatus(state: UiState): SetupStatus {
        val matchup = state.matchup
        return when {
            !state.settings.isConfigured -> SetupStatus.NotStarted
            state.error != null -> SetupStatus.Failed(state.error)
            matchup != null -> SetupStatus.Connected("${matchup.mine.name} vs ${matchup.opponent?.name ?: "bye week"}")
            state.league != null && !state.loading -> SetupStatus.NeedTeam
            else -> SetupStatus.Checking
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsStore.update(transform)

    /** The TV tuner's scanned channels as (number, name); empty until the antenna is on and loaded. */
    val antennaChannels: List<Pair<String, String>> get() = tuner.channelChoices

    private suspend fun refresh(settings: AppSettings) {
        _state.update { it.copy(loading = true) }
        try {
            val (league, games) = if (settings.leagueId == DemoLeague.ID) {
                val week = scoreboard.currentWeek()
                val games = scoreboard.fetch(settings.season, week)
                DemoLeague.build(games, week) to games
            } else {
                val league = fantasy.fetch(settings)
                league to scoreboard.fetch(settings.season, league.scoringPeriod)
            }
            val (tiles, others) = layoutGames(league.matchup, games, settings)
            // Built-in tuner tiles play through a TvView, not ExoPlayer.
            players.sync(tiles.mapNotNull { t -> t.stream?.takeIf { it.kind != SourceKind.TUNER }?.let { t.game.id to it } }.toMap())
            _state.update {
                it.copy(
                    league = league, games = games, tiles = tiles, otherGames = others,
                    loading = false, error = null,
                    antennaError = when {
                        !settings.antennaEnabled -> null
                        tuner.hasTuner -> tuner.lastError
                        antenna.tunerCount != null -> antenna.lastError
                        else -> tuner.lastError ?: antenna.lastError
                    },
                    lastUpdated = Instant.now(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: e::class.simpleName) }
        }
    }

    /**
     * Picks games for the grid: live games with the most fantasy starters first, then the next
     * kickoffs. Games already on screen keep their position so tiles don't shuffle mid-game.
     */
    private suspend fun layoutGames(
        matchup: FantasyMatchup?,
        games: List<NflGame>,
        settings: AppSettings,
    ): Pair<List<GameTile>, List<NflGame>> {
        val starters = listOfNotNull(matchup?.mine, matchup?.opponent).flatMap { it.starters }
        val relevant = games.filter { g -> starters.any { g.involves(it.proTeam) } }
        fun stake(g: NflGame) = starters.count { g.involves(it.proTeam) }

        val live = relevant.filter { it.state == GameState.IN }.sortedByDescending(::stake)
        val upcoming = relevant.filter { it.state == GameState.PRE }.sortedBy { it.start }
        val chosen = (live + upcoming).take(settings.maxTiles).map { it.id }.toSet()

        val previousOrder = _state.value.tiles.map { it.game.id }.filter { it in chosen }
        val ordered = (previousOrder + (live + upcoming).map { it.id }.filter { it in chosen && it !in previousOrder })
            .map { id -> relevant.first { it.id == id } }

        // Antenna tiles are capped by tuner count; overflow games fall through to other sources.
        fun capacity(kind: SourceKind) = when (kind) {
            SourceKind.TUNER -> tuner.tunerCount
            SourceKind.ANTENNA -> antenna.tunerCount ?: Int.MAX_VALUE
            else -> Int.MAX_VALUE
        }
        val used = mutableMapOf<SourceKind, Int>()
        val tiles = ordered.map { game ->
            val candidates = streams.candidates(game, games, settings)
            val pick = candidates.firstOrNull { (used[it.kind] ?: 0) < capacity(it.kind) }
            pick?.let { used[it.kind] = (used[it.kind] ?: 0) + 1 }
            GameTile(game, pick)
        }
        val others = relevant.filter { it.id !in chosen }
            .sortedWith(compareBy({ OTHER_ORDER.indexOf(it.state) }, { it.start }))
        return tiles to others
    }

    override fun onCleared() {
        configServer.stop()
        players.releaseAll()
    }

    private companion object {
        const val POLL_MS = 30_000L
        val OTHER_ORDER = listOf(GameState.IN, GameState.PRE, GameState.POST)
    }
}
