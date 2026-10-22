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
import com.damonearl.fantasymultiview.streams.XtreamProvider
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

/** The stream list for a game picked from the strip; [options] is null while it loads. */
data class StreamChoice(val game: NflGame, val options: List<StreamSource>?)

data class UiState(
    val settings: AppSettings = AppSettings(),
    val league: FantasyLeague? = null,
    val games: List<NflGame> = emptyList(),
    /** Games with a fantasy starter in them: live (most starters first), then upcoming, then final. */
    val weekGames: List<NflGame> = emptyList(),
    /** The game on screen and the stream playing it. */
    val watching: GameTile? = null,
    /** The best stream each game has, for the game strip. */
    val available: Map<String, StreamSource> = emptyMap(),
    val choice: StreamChoice? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val antennaError: String? = null,
    val playlistError: String? = null,
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
    private val m3u = M3uStreamProvider(http)
    private val xtream = XtreamProvider(http, json)
    private val streams = StreamResolver(listOf(ManualStreamProvider(), tuner, antenna, xtream, m3u))
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

    /** The user's pick from the game strip; null follows the most relevant game. */
    private var picked: GameTile? = null
    private var autoGameId: String? = null
    private var optionsByGame: Map<String, List<StreamSource>> = emptyMap()

    /** Opens the stream list for [game], showing the last known options while fresh ones load. */
    fun chooseStream(game: NflGame) {
        _state.update { it.copy(choice = StreamChoice(game, optionsByGame[game.id])) }
        viewModelScope.launch {
            val options = streams.candidates(game, _state.value.games, settingsStore.settings.value)
            optionsByGame = optionsByGame + (game.id to options)
            _state.update { if (it.choice?.game?.id == game.id) it.copy(choice = StreamChoice(game, options)) else it }
        }
    }

    fun dismissChoice() = _state.update { it.copy(choice = null) }

    fun watch(game: NflGame, source: StreamSource) {
        val tile = GameTile(game, source)
        picked = tile
        play(tile)
        _state.update { it.copy(watching = tile, choice = null) }
    }

    private fun play(tile: GameTile?) {
        // Built-in tuner channels play through a TvView, not ExoPlayer.
        val stream = tile?.stream?.takeIf { it.kind != SourceKind.TUNER }
        players.sync(if (stream != null) mapOf(tile.game.id to stream) else emptyMap())
    }

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
            val week = weekGames(league.matchup, games)
            optionsByGame = week.associate { it.id to streams.candidates(it, games, settings) }
            val watching = chooseWatching(week)
            play(watching)
            _state.update {
                it.copy(
                    league = league, games = games, weekGames = week, watching = watching,
                    available = optionsByGame.mapNotNull { (id, options) -> options.firstOrNull()?.let { id to it } }.toMap(),
                    loading = false, error = null,
                    antennaError = when {
                        !settings.antennaEnabled -> null
                        tuner.hasTuner -> tuner.lastError
                        antenna.tunerCount != null -> antenna.lastError
                        else -> tuner.lastError ?: antenna.lastError
                    },
                    playlistError = when {
                        settings.m3uUrl.isBlank() -> null
                        XtreamProvider.Account.parse(settings.m3uUrl) != null -> xtream.lastError
                        else -> m3u.lastError
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

    private fun weekGames(matchup: FantasyMatchup?, games: List<NflGame>): List<NflGame> {
        val starters = listOfNotNull(matchup?.mine, matchup?.opponent).flatMap { it.starters }
        fun stake(g: NflGame) = starters.count { g.involves(it.proTeam) }
        val relevant = games.filter { stake(it) > 0 }
        return relevant.filter { it.state == GameState.IN }.sortedByDescending(::stake) +
            relevant.filter { it.state == GameState.PRE }.sortedBy { it.start } +
            relevant.filter { it.state == GameState.POST }.sortedBy { it.start }
    }

    /**
     * The user's pick while its game is on the schedule; otherwise the first unfinished game in
     * strip order that has a stream. An automatic pick sticks until its game ends, so the video
     * doesn't jump when another game goes live.
     */
    private fun chooseWatching(week: List<NflGame>): GameTile? {
        picked?.let { p -> week.firstOrNull { it.id == p.game.id }?.let { return GameTile(it, p.stream) } }
        picked = null
        val open = week.filter { it.state != GameState.POST }
        fun hasStream(g: NflGame) = optionsByGame[g.id].orEmpty().isNotEmpty()
        val game = open.firstOrNull { it.id == autoGameId && hasStream(it) }
            ?: open.firstOrNull(::hasStream)
            ?: open.firstOrNull()
            ?: return null
        autoGameId = game.id
        return GameTile(game, optionsByGame[game.id]?.firstOrNull())
    }

    override fun onCleared() {
        configServer.stop()
        players.releaseAll()
    }

    private companion object {
        const val POLL_MS = 30_000L
    }
}
