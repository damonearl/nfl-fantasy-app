package com.damonearl.fantasymultiview.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.damonearl.fantasymultiview.data.FantasyPlayer
import com.damonearl.fantasymultiview.data.FantasySide
import com.damonearl.fantasymultiview.data.GameState
import com.damonearl.fantasymultiview.data.NflGame

@Composable
fun TeamPanel(
    label: String,
    side: FantasySide?,
    accent: Color,
    gameFor: (String) -> NflGame?,
    leading: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxHeight()
            .background(Palette.Panel)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(accent, RoundedCornerShape(1.dp)))
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(label, color = accent, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text(
                    side?.name ?: "—",
                    color = Palette.Text,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                side?.total?.pts() ?: "0.0",
                color = if (leading) accent else Palette.Text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        val starters = side?.starters.orEmpty()
        val playing = starters.count { gameFor(it.proTeam)?.state == GameState.IN }
        val yetToPlay = starters.count { gameFor(it.proTeam)?.state == GameState.PRE }
        Text(
            listOfNotNull(side?.projected?.let { "proj ${it.pts()}" }, "$playing live", "$yetToPlay left").joinToString(" · "),
            color = Palette.Muted,
            fontSize = 7.sp,
            maxLines = 1,
        )
        Spacer(Modifier.height(3.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))

        Column(modifier = Modifier.padding(top = 2.dp)) {
            starters.forEach { player -> PlayerRow(player, gameFor(player.proTeam), accent) }
        }
    }
}

@Composable
private fun PlayerRow(player: FantasyPlayer, game: NflGame?, accent: Color) {
    val live = game?.state == GameState.IN
    val done = game == null || game.state == GameState.POST

    // Flash the row when points go up, and show the delta while it fades.
    val flash = remember { Animatable(0f) }
    var previous by remember { mutableDoubleStateOf(player.points) }
    var delta by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(player.points) {
        val change = player.points - previous
        previous = player.points
        if (change > 0.05) {
            delta = change
            flash.snapTo(1f)
            flash.animateTo(0f, tween(durationMillis = 4000))
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(3.dp))
            .background(Palette.Gain.copy(alpha = 0.22f * flash.value))
            .padding(horizontal = 3.dp, vertical = 1.dp)
            .alpha(if (done && !live) 0.6f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            player.slot,
            color = Palette.Muted,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.width(22.dp),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    shortName(player),
                    color = Palette.Text,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                player.injuryStatus?.let {
                    Text(" " + it.take(1), color = Palette.Warn, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (live) {
                    Box(Modifier.size(4.dp).background(Palette.Live, CircleShape))
                    Spacer(Modifier.width(3.dp))
                }
                Text(
                    gameLine(player, game),
                    color = if (live) Palette.Text.copy(alpha = 0.85f) else Palette.Faint,
                    fontSize = 7.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                player.points.pts(),
                color = if (live) accent else Palette.Text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            if (flash.value > 0.01f) {
                Text(
                    "+${delta.pts()}",
                    color = Palette.Gain.copy(alpha = flash.value),
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** "C. Stroud"; team defenses and single names stay as-is. */
private fun shortName(player: FantasyPlayer): String {
    val parts = player.name.split(' ')
    if (player.position == "D/ST" || parts.size < 2) return player.name
    return "${parts.first().first()}. ${parts.drop(1).joinToString(" ")}"
}

private fun gameLine(player: FantasyPlayer, game: NflGame?): String {
    if (player.proTeam.isEmpty()) return player.position
    if (game == null) return "BYE"
    val opponent = if (game.home.abbrev == player.proTeam) "v ${game.away.abbrev}" else "@ ${game.home.abbrev}"
    return "$opponent · ${game.statusText()}"
}
