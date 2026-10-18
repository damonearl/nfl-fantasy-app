package com.damonearl.fantasymultiview.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.damonearl.fantasymultiview.data.FantasyPlayer
import com.damonearl.fantasymultiview.data.GameState
import com.damonearl.fantasymultiview.data.NflGame
import com.damonearl.fantasymultiview.player.PlayerPool
import com.damonearl.fantasymultiview.player.TileStatus
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

@Composable
fun WatchScreen(state: UiState, players: PlayerPool, onOpenSettings: () -> Unit) {
    val matchup = state.matchup
    val gamesByTeam = remember(state.games) {
        state.games.flatMap { g -> listOf(g.home.abbrev to g, g.away.abbrev to g) }.toMap()
    }
    val mine = matchup?.mine
    val opp = matchup?.opponent

    Row(Modifier.fillMaxSize().background(Palette.Bg)) {
        TeamPanel(
            label = "YOU",
            side = mine,
            accent = Palette.Mine,
            gameFor = gamesByTeam::get,
            leading = mine != null && opp != null && mine.total > opp.total,
            modifier = Modifier.width(210.dp),
        )
        CenterColumn(
            state = state,
            players = players,
            onOpenSettings = onOpenSettings,
            modifier = Modifier.weight(1f),
        )
        TeamPanel(
            label = "OPPONENT",
            side = opp,
            accent = Palette.Opp,
            gameFor = gamesByTeam::get,
            leading = mine != null && opp != null && opp.total > mine.total,
            modifier = Modifier.width(210.dp),
        )
    }
}

@Composable
private fun CenterColumn(state: UiState, players: PlayerPool, onOpenSettings: () -> Unit, modifier: Modifier) {
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedId by rememberSaveable { mutableStateOf<String?>(null) }
    val tiles = state.tiles
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(id: String) = requesters.getOrPut(id) { FocusRequester() }

    // Drop the expanded view if that game left the grid.
    if (expandedId != null && tiles.none { it.game.id == expandedId }) expandedId = null

    LaunchedEffect(focusedId) { players.setAudioFocus(focusedId) }
    LaunchedEffect(expandedId) {
        players.setExpanded(expandedId)
        (expandedId ?: focusedId)?.let { id -> runCatching { requester(id).requestFocus() } }
    }
    LaunchedEffect(tiles.map { it.game.id }) {
        if (tiles.none { it.game.id == focusedId }) {
            tiles.firstOrNull()?.let { runCatching { requester(it.game.id).requestFocus() } }
        }
    }
    BackHandler(enabled = expandedId != null) { expandedId = null }

    val starters = remember(state.matchup) {
        state.matchup?.let { m ->
            m.mine.starters.map { it to true } + m.opponent?.starters.orEmpty().map { it to false }
        }.orEmpty()
    }

    Column(modifier.fillMaxHeight().padding(horizontal = 10.dp, vertical = 10.dp)) {
        TopBar(state, onOpenSettings)
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val visible = if (expandedId != null) tiles.filter { it.game.id == expandedId } else tiles
            if (visible.isEmpty()) {
                EmptyGrid(state)
            } else {
                val (cols, tileWidth) = bestGrid(visible.size, maxWidth, maxHeight, gap = 6.dp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    visible.chunked(cols).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { tile ->
                                val id = tile.game.id
                                key(id) {
                                    GameTileView(
                                        game = tile.game,
                                        sourceLabel = tile.stream?.label,
                                        player = if (tile.stream != null) players.player(id) else null,
                                        status = players.status[id],
                                        involved = starters.filter { (p, _) -> tile.game.involves(p.proTeam) },
                                        focused = focusedId == id,
                                        hasAudio = focusedId == id && tile.stream != null,
                                        modifier = Modifier
                                            .size(tileWidth, tileWidth * 9f / 16f)
                                            .focusRequester(requester(id))
                                            .onFocusChanged { if (it.isFocused) focusedId = id }
                                            .onKeyEvent { e ->
                                                val select = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                                                if (select && e.type == KeyEventType.KeyUp) {
                                                    expandedId = if (expandedId == id) null else id
                                                    true
                                                } else {
                                                    select
                                                }
                                            }
                                            .focusable(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (state.otherGames.isNotEmpty() && expandedId == null) {
            Spacer(Modifier.height(8.dp))
            OtherGames(state.otherGames, starters)
        }
    }
}

/** Chooses the column count that gives the largest 16:9 tiles for [count] tiles in the given space. */
private fun bestGrid(count: Int, width: Dp, height: Dp, gap: Dp): Pair<Int, Dp> =
    (1..count).map { cols ->
        val rows = (count + cols - 1) / cols
        val byWidth = (width - gap * (cols - 1)) / cols
        val byHeight = ((height - gap * (rows - 1)) / rows) * 16f / 9f
        cols to min(byWidth, byHeight)
    }.maxBy { it.second.value }

@Composable
private fun TopBar(state: UiState, onOpenSettings: () -> Unit) {
    val now by produceState(Instant.now()) {
        while (true) {
            delay(1_000)
            value = Instant.now()
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "WEEK ${state.league?.scoringPeriod ?: "—"}",
            color = Palette.Text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
        state.league?.leagueName?.let {
            Text("  ·  $it", color = Palette.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        val status = when {
            state.error != null -> state.error
            state.antennaError != null -> "Antenna: ${state.antennaError}"
            state.lastUpdated != null -> "Updated ${Duration.between(state.lastUpdated, now).seconds.coerceAtLeast(0)}s ago"
            state.loading -> "Loading…"
            else -> ""
        }
        Text(
            status,
            color = if (state.error != null || state.antennaError != null) Palette.Warn else Palette.Faint,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(10.dp))
        FocusButton("Settings", onClick = onOpenSettings)
    }
}

@Composable
fun FocusButton(text: String, modifier: Modifier = Modifier, accent: Color = Palette.Mine, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) accent else Palette.PanelRaised)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                val select = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (select && e.type == KeyEventType.KeyUp) onClick()
                select
            }
            .focusable()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            color = if (focused) Palette.Bg else Palette.Text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun GameTileView(
    game: NflGame,
    sourceLabel: String?,
    player: ExoPlayer?,
    status: TileStatus?,
    involved: List<Pair<FantasyPlayer, Boolean>>,
    focused: Boolean,
    hasAudio: Boolean,
    modifier: Modifier,
) {
    Box(
        modifier
            .background(Color.Black)
            .border(if (focused) 3.dp else 1.dp, if (focused) Palette.Focus else Palette.Line),
    ) {
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        useController = false
                        isFocusable = false
                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { it.player = player },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize().padding(if (focused) 3.dp else 1.dp),
            )
        } else {
            GameCard(game)
        }

        // Top-left: audio + source badges.
        Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (hasAudio) Badge("AUDIO", Palette.Mine, Palette.Bg)
            if (game.state == GameState.IN) Badge("LIVE", Palette.Live, Color.White)
            if (game.redZone) Badge("RED ZONE", Palette.Live.copy(alpha = 0.25f), Palette.Live)
        }
        when (status) {
            is TileStatus.Error -> Badge(status.message, Color.Black.copy(alpha = 0.7f), Palette.Warn, Modifier.align(Alignment.Center))
            TileStatus.Loading -> Badge("Loading stream…", Color.Black.copy(alpha = 0.6f), Palette.Muted, Modifier.align(Alignment.Center))
            else -> Unit
        }

        // Bottom: score bug + the fantasy players in this game.
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.9f))))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(game.scoreLine(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("  ${game.statusText()}", color = Palette.Muted, fontSize = 10.sp)
                game.downDistance?.takeIf { game.state == GameState.IN }?.let {
                    Text("  ·  ${game.possession ?: ""} $it", color = Palette.Muted, fontSize = 10.sp, maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                sourceLabel?.let { Text(it, color = Palette.Faint, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (involved.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 3.dp)) {
                    involved.take(6).forEach { (p, isMine) ->
                        val accent = if (isMine) Palette.Mine else Palette.Opp
                        Badge("${p.name.substringAfterLast(' ')} ${p.points.pts()}", accent.copy(alpha = 0.22f), accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun GameCard(game: NflGame) {
    // Leaves room for the badges above and the score bug overlay below.
    Column(
        Modifier.fillMaxSize().background(Palette.PanelRaised).padding(start = 10.dp, end = 10.dp, top = 30.dp, bottom = 52.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "${game.away.name} @ ${game.home.name}",
            color = Palette.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull(game.broadcast, "no stream found").joinToString(" · "),
            color = Palette.Faint,
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun Badge(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = fg,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(bg)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

@Composable
private fun OtherGames(games: List<NflGame>, starters: List<Pair<FantasyPlayer, Boolean>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        games.take(4).forEach { g ->
            val mine = starters.count { (p, m) -> m && g.involves(p.proTeam) }
            val theirs = starters.count { (p, m) -> !m && g.involves(p.proTeam) }
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Palette.Panel)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (g.state == GameState.IN) {
                    Box(Modifier.size(5.dp).background(Palette.Live, CircleShape))
                    Spacer(Modifier.width(4.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(g.scoreLine(), color = Palette.Text, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(g.statusText(), color = Palette.Faint, fontSize = 8.sp, maxLines = 1)
                }
                if (mine > 0) Text("$mine", color = Palette.Mine, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                if (theirs > 0) Text("  $theirs", color = Palette.Opp, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun EmptyGrid(state: UiState) {
    val next = state.otherGames.firstOrNull { it.state == GameState.PRE }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (state.matchup == null) "Loading your matchup…" else "None of your starters are playing right now",
            color = Palette.Text,
            fontSize = 14.sp,
        )
        next?.let {
            Text("Next kickoff: ${it.matchupLabel} · ${it.statusText()}", color = Palette.Muted, fontSize = 11.sp)
        }
    }
}
