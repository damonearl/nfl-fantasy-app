package com.damonearl.fantasymultiview.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@Composable
fun SetupScreen(
    state: UiState,
    configUrl: String?,
    onSelectTeam: (Int) -> Unit,
    onDone: (() -> Unit)?,
) {
    val firstFocus = remember { FocusRequester() }
    val teams = state.league?.teams.orEmpty()
    LaunchedEffect(teams.isNotEmpty(), onDone != null) { runCatching { firstFocus.requestFocus() } }

    Row(
        Modifier.fillMaxSize().background(Palette.Bg).padding(40.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Fantasy Multiview", color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Watch every game your fantasy starters are in, with live points for you and your opponent.",
                color = Palette.Muted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(24.dp))
            Step("1", "Scan the code with your phone, or open the address below.")
            Step("2", "Enter your ESPN league ID. Private leagues also need the espn_s2 and SWID cookies.")
            Step("3", "Optional: turn on your HDHomeRun antenna, or add a playlist or per-game stream URLs.")
            Spacer(Modifier.height(16.dp))
            Text(
                configUrl ?: "Connect this TV to Wi-Fi or Ethernet to show the setup address.",
                color = Palette.Mine,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(20.dp))

            val status = when {
                !state.settings.isConfigured -> "Waiting for league ID…"
                state.error != null -> state.error
                state.league == null -> "Loading league…"
                state.matchup == null && teams.isNotEmpty() -> "Pick your team:"
                state.matchup != null -> "Connected: ${state.matchup!!.mine.name} vs ${state.matchup!!.opponent?.name ?: "BYE"}"
                else -> ""
            }
            Text(
                status,
                color = if (state.error != null) Palette.Warn else Palette.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            state.antennaError?.let {
                Text("Antenna: $it", color = Palette.Warn, fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))

            if (onDone != null) {
                FocusButton("Start watching", Modifier.focusRequester(firstFocus), onClick = onDone)
                Spacer(Modifier.height(10.dp))
            }
            if (teams.isNotEmpty()) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    items(teams, key = { it.id }) { team ->
                        val selected = team.id == state.matchup?.mine?.teamId
                        FocusButton(
                            text = (if (selected) "✓ " else "") + team.name,
                            modifier = if (onDone == null && team == teams.first()) Modifier.focusRequester(firstFocus) else Modifier,
                            onClick = { onSelectTeam(team.id) },
                        )
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .padding(14.dp),
            ) {
                configUrl?.let { QrCode(it, Modifier.size(220.dp)) } ?: Spacer(Modifier.size(220.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text("Scan to set up", color = Palette.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Step(number: String, text: String) {
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(number, color = Palette.Mine, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
        Text(text, color = Palette.Text, fontSize = 14.sp)
    }
}

@Composable
private fun QrCode(content: String, modifier: Modifier) {
    val matrix = remember(content) { QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0) }
    Canvas(modifier) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    drawRect(Color.Black, topLeft = Offset(x * cell, y * cell), size = Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}
