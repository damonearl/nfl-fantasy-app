package com.damonearl.fantasymultiview.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import com.damonearl.fantasymultiview.data.GameState
import com.damonearl.fantasymultiview.data.NflGame
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Palette {
    val Bg = Color(0xFF0A0E13)
    val Panel = Color(0xFF10161E)
    val PanelRaised = Color(0xFF17202B)
    val Line = Color(0xFF243040)
    val Text = Color(0xFFE8EEF5)
    val Muted = Color(0xFF8A97A8)
    val Faint = Color(0xFF5B6878)
    val Mine = Color(0xFF3BA3FF)
    val Opp = Color(0xFFFF6B5E)
    val Live = Color(0xFFFF3B30)
    val Gain = Color(0xFF34D399)
    val Warn = Color(0xFFFBBF24)
    val Focus = Color(0xFFFFFFFF)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Palette.Bg,
            surface = Palette.Panel,
            primary = Palette.Mine,
            onBackground = Palette.Text,
            onSurface = Palette.Text,
        ),
    ) {
        // Material's default 24sp line height swamps the small type used at TV distance.
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified),
            content = content,
        )
    }
}

private val kickoffFormat = DateTimeFormatter.ofPattern("EEE h:mm a")

/** "Q3 4:12", "Halftime", "Final", or the local kickoff time for games that haven't started. */
fun NflGame.statusText(): String = when (state) {
    GameState.PRE -> start?.atZone(ZoneId.systemDefault())?.format(kickoffFormat) ?: detail
    else -> detail
}

fun NflGame.scoreLine(): String = when (state) {
    GameState.PRE -> "${away.abbrev} @ ${home.abbrev}"
    else -> "${away.abbrev} ${away.score ?: 0}  ${home.abbrev} ${home.score ?: 0}"
}

fun Double.pts(): String = String.format(java.util.Locale.US, "%.1f", this)
