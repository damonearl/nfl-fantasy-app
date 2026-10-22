package com.damonearl.fantasymultiview.ui

import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.net.Uri
import android.os.Bundle
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.damonearl.fantasymultiview.data.FantasyPlayer
import com.damonearl.fantasymultiview.data.GameState
import com.damonearl.fantasymultiview.data.NflGame
import com.damonearl.fantasymultiview.player.PlayerPool
import com.damonearl.fantasymultiview.player.TileStatus
import com.damonearl.fantasymultiview.streams.SourceKind
import com.damonearl.fantasymultiview.streams.StreamSource
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

private val PANEL_WIDTH = 170.dp
private const val VIDEO_KEY = "video"

@Composable
fun WatchScreen(
    state: UiState,
    players: PlayerPool,
    onChoose: (NflGame) -> Unit,
    onWatch: (NflGame, StreamSource) -> Unit,
    onDismissChoice: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val matchup = state.matchup
    val gamesByTeam = remember(state.games) {
        state.games.flatMap { g -> listOf(g.home.abbrev to g, g.away.abbrev to g) }.toMap()
    }
    val mine = matchup?.mine
    val opp = matchup?.opponent

    // Video starts at the top-left corner with everything else to the right and below: TCL's
    // tuner hides app drawing from the screen's top-left to the antenna video's bottom-right, so
    // with the antenna tile in the corner that area is just the video itself.
    Row(Modifier.fillMaxSize().background(Palette.Bg)) {
        CenterColumn(
            state = state,
            players = players,
            onChoose = onChoose,
            onOpenSettings = onOpenSettings,
            modifier = Modifier.weight(1f),
        )
        Column(Modifier.width(PANEL_WIDTH).fillMaxHeight().background(Palette.Panel)) {
            TeamPanel(
                label = "YOU",
                side = mine,
                accent = Palette.Mine,
                gameFor = gamesByTeam::get,
                leading = mine != null && opp != null && mine.total > opp.total,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
            TeamPanel(
                label = "OPPONENT",
                side = opp,
                accent = Palette.Opp,
                gameFor = gamesByTeam::get,
                leading = mine != null && opp != null && opp.total > mine.total,
                modifier = Modifier.weight(1f),
            )
        }
    }

    state.choice?.let { choice ->
        StreamChoiceDialog(
            choice = choice,
            current = state.watching?.stream?.takeIf { state.watching.game.id == choice.game.id },
            onPick = { onWatch(choice.game, it) },
            onDismiss = onDismissChoice,
        )
    }
}

@Composable
private fun CenterColumn(
    state: UiState,
    players: PlayerPool,
    onChoose: (NflGame) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier,
) {
    val watching = state.watching
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    // The card (or the video) that had focus, to return to when the stream list closes.
    var lastFocused by rememberSaveable { mutableStateOf<String?>(null) }
    val strip = rememberLazyListState()

    LaunchedEffect(state.choice == null, state.weekGames.isNotEmpty()) {
        if (state.choice != null) return@LaunchedEffect
        val target = lastFocused?.takeIf { it == VIDEO_KEY || state.weekGames.any { g -> g.id == it } }
            ?: watching?.game?.id
            ?: return@LaunchedEffect
        state.weekGames.indexOfFirst { it.id == target }.takeIf { it >= 0 }?.let { strip.scrollToItem(it) }
        withFrameNanos { }
        runCatching { requester(target).requestFocus() }
    }

    val starters = remember(state.matchup) {
        state.matchup?.let { m ->
            m.mine.starters.map { it to true } + m.opponent?.starters.orEmpty().map { it to false }
        }.orEmpty()
    }

    Column(modifier.fillMaxHeight()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val width = min(maxWidth, maxHeight * 16f / 9f)
            if (watching == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyTile(state) }
            } else {
                var focused by remember { mutableStateOf(false) }
                val id = watching.game.id
                GameTileView(
                    game = watching.game,
                    stream = watching.stream,
                    player = if (watching.stream != null) players.player(id) else null,
                    status = players.status[id],
                    involved = starters.filter { (p, _) -> watching.game.involves(p.proTeam) },
                    focused = focused,
                    modifier = Modifier
                        .size(width, width * 9f / 16f)
                        .focusRequester(requester(VIDEO_KEY))
                        .onFocusChanged {
                            focused = it.isFocused
                            if (it.isFocused) lastFocused = VIDEO_KEY
                        }
                        .onSelect { onChoose(watching.game) }
                        .focusable(),
                )
            }
        }
        if (state.weekGames.isNotEmpty()) {
            LazyRow(
                state = strip,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                items(state.weekGames, key = { it.id }) { g ->
                    GameChip(
                        game = g,
                        watching = g.id == watching?.game?.id,
                        source = state.available[g.id],
                        mine = starters.count { (p, m) -> m && g.involves(p.proTeam) },
                        theirs = starters.count { (p, m) -> !m && g.involves(p.proTeam) },
                        onClick = { onChoose(g) },
                        modifier = Modifier
                            .focusRequester(requester(g.id))
                            .onFocusChanged { if (it.isFocused) lastFocused = g.id },
                    )
                }
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            TopBar(state, onOpenSettings)
        }
    }
}

/** Runs [action] when OK is released on the focused element. */
private fun Modifier.onSelect(action: () -> Unit) = onKeyEvent { e ->
    val select = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
    if (select && e.type == KeyEventType.KeyUp) action()
    select
}

private fun sourceName(kind: SourceKind) = when (kind) {
    SourceKind.MANUAL -> "Link"
    SourceKind.ANTENNA, SourceKind.TUNER -> "Antenna"
    SourceKind.PLAYLIST -> "IPTV"
}

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
            state.playlistError != null -> "IPTV: ${state.playlistError}"
            state.lastUpdated != null -> "Updated ${Duration.between(state.lastUpdated, now).seconds.coerceAtLeast(0)}s ago"
            state.loading -> "Loading…"
            else -> ""
        }
        Text(
            status,
            color = if (state.error != null || state.antennaError != null || state.playlistError != null) Palette.Warn else Palette.Faint,
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
            .onSelect(onClick)
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
    stream: StreamSource?,
    player: ExoPlayer?,
    status: TileStatus?,
    involved: List<Pair<FantasyPlayer, Boolean>>,
    focused: Boolean,
    modifier: Modifier,
) {
    Box(
        modifier
            .background(Color.Black)
            .border(if (focused) 3.dp else 1.dp, if (focused) Palette.Focus else Palette.Line),
    ) {
        val tuner = stream?.takeIf { it.kind == SourceKind.TUNER }
        if (tuner != null) {
            TunerView(tuner, Modifier.fillMaxSize().padding(if (focused) 3.dp else 1.dp))
        } else if (player != null) {
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

        // Top-left: game badges; top-right: what OK does.
        Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (game.state == GameState.IN) Badge("LIVE", Palette.Live, Color.White)
            if (game.redZone) Badge("RED ZONE", Palette.Live.copy(alpha = 0.25f), Palette.Live)
        }
        if (focused) Badge("OK · change stream", Color.Black.copy(alpha = 0.7f), Palette.Text, Modifier.align(Alignment.TopEnd).padding(8.dp))
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
                stream?.let { Text(it.label, color = Palette.Faint, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (involved.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 3.dp)) {
                    involved.take(8).forEach { (p, isMine) ->
                        val accent = if (isMine) Palette.Mine else Palette.Opp
                        Badge("${p.name.substringAfterLast(' ')} ${p.points.pts()}", accent.copy(alpha = 0.22f), accent)
                    }
                }
            }
        }
    }
}

private const val TUNER_SURFACE_WIDTH = 1276
private const val TUNER_SURFACE_HEIGHT = 716

/** Shows a channel from the TV's built-in tuner. */
@Composable
private fun TunerView(source: StreamSource, modifier: Modifier) {
    var status by remember(source.url) { mutableStateOf<String?>("Tuning…") }
    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                TvView(ctx).apply {
                    isFocusable = false
                    setCallback(object : TvView.TvInputCallback() {
                        override fun onVideoAvailable(inputId: String) {
                            status = null
                        }

                        override fun onVideoUnavailable(inputId: String, reason: Int) {
                            status = when (reason) {
                                TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING,
                                TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING -> "Tuning…"
                                TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL -> "Weak antenna signal"
                                else -> "No signal on this channel"
                            }
                        }

                        override fun onConnectionFailed(inputId: String) {
                            status = "The TV tuner is busy or unavailable"
                        }

                        override fun onDisconnected(inputId: String) {
                            status = "The TV tuner disconnected"
                        }
                    })
                }
            },
            update = { view ->
                // TCL's tuner goes full screen (over the whole layout) when the video surface is
                // 1280x720 px or larger, but places the video by the view's on-screen frame. A small
                // fixed buffer keeps it in picture-in-picture while the view fills the tile.
                (0 until view.childCount).map(view::getChildAt).filterIsInstance<SurfaceView>()
                    .forEach { it.holder.setFixedSize(TUNER_SURFACE_WIDTH, TUNER_SURFACE_HEIGHT) }
                if (view.tag != source) {
                    view.tag = source
                    val extras = Bundle().apply { source.tuneExtras.forEach { (k, v) -> putInt(k, v) } }
                    view.tune(source.inputId ?: return@AndroidView, Uri.parse(source.url), extras)
                }
            },
            onRelease = { it.reset() },
            modifier = Modifier.fillMaxSize(),
        )
        status?.let { Badge(it, Color.Black.copy(alpha = 0.7f), Palette.Muted, Modifier.align(Alignment.Center)) }
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
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull(game.broadcast, "no stream found · pick another game below").joinToString(" · "),
            color = Palette.Faint,
            fontSize = 10.sp,
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

/** A game in the strip below the video: score, starters on each side, and what it can be watched on. */
@Composable
private fun GameChip(
    game: NflGame,
    watching: Boolean,
    source: StreamSource?,
    mine: Int,
    theirs: Int,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier
            .width(136.dp)
            .clip(shape)
            .background(if (focused) Palette.PanelRaised else Palette.Panel)
            .border(2.dp, if (focused) Palette.Focus else if (watching) Palette.Mine else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .onSelect(onClick)
            .focusable()
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (game.state == GameState.IN) {
                Box(Modifier.size(5.dp).background(Palette.Live, CircleShape))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                game.scoreLine(),
                color = Palette.Text,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (mine > 0) Text("$mine", color = Palette.Mine, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            if (theirs > 0) Text(" $theirs", color = Palette.Opp, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            listOfNotNull(game.statusText(), game.broadcast).joinToString(" · "),
            color = Palette.Faint,
            fontSize = 8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            when {
                watching -> "▶ Watching"
                source == null -> "No stream"
                source.unsure -> "${sourceName(source.kind)} · maybe"
                else -> sourceName(source.kind)
            },
            color = if (watching) Palette.Mine else if (source == null) Palette.Faint else Palette.Muted,
            fontSize = 8.sp,
            fontWeight = if (watching) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** The streams that can show [StreamChoice.game]; picking one puts it on screen. */
@Composable
private fun StreamChoiceDialog(
    choice: StreamChoice,
    current: StreamSource?,
    onPick: (StreamSource) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = remember { FocusRequester() }
    val options = choice.options
    val game = choice.game
    val selected = options?.indexOfFirst { it.url == current?.url }?.coerceAtLeast(0) ?: 0
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(480.dp).clip(RoundedCornerShape(10.dp)).background(Palette.PanelRaised).padding(16.dp)) {
            Text("${game.away.name} @ ${game.home.name}", color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(listOfNotNull(game.statusText(), game.broadcast).joinToString(" · "), color = Palette.Muted, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            when {
                options == null -> Text("Finding streams…", color = Palette.Muted, fontSize = 12.sp)
                options.isEmpty() -> Text(
                    "No streams found for this game. Set up IPTV or the antenna in Settings.",
                    color = Palette.Muted,
                    fontSize = 12.sp,
                )
                else -> LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(options, key = { _, o -> o.url }) { i, option ->
                        FocusButton(
                            text = (if (option.url == current?.url) "✓ " else "") + "${sourceName(option.kind)} · ${option.label}",
                            modifier = Modifier.fillMaxWidth().let { if (i == selected) it.focusRequester(initial) else it },
                            onClick = { onPick(option) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            FocusButton(
                "Cancel",
                modifier = if (options.isNullOrEmpty()) Modifier.focusRequester(initial) else Modifier,
                onClick = onDismiss,
            )
        }
    }
    LaunchedEffect(options == null, options?.isEmpty()) {
        withFrameNanos { }
        runCatching { initial.requestFocus() }
    }
}

@Composable
private fun EmptyTile(state: UiState) {
    Text(
        when {
            state.matchup == null -> "Loading your matchup…"
            state.weekGames.isEmpty() -> "None of your starters play this week"
            else -> "All of this week's games are final"
        },
        color = Palette.Text,
        fontSize = 14.sp,
    )
}
