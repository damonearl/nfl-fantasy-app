package com.damonearl.fantasymultiview.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.damonearl.fantasymultiview.config.EspnInput
import com.damonearl.fantasymultiview.data.AppSettings
import com.damonearl.fantasymultiview.data.GameState
import com.damonearl.fantasymultiview.streams.OtaMatching
import com.damonearl.fantasymultiview.streams.TvTunerProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** A text value edited with the TV keyboard. */
private class TextEdit(
    val title: String,
    val hint: String,
    val initial: String,
    val keyboard: KeyboardType,
    val onSave: (String) -> Unit,
)

/** A choice made from a full list (for long option lists like channels). */
private class Picker(val title: String, val options: List<String>, val selected: Int, val onPick: (Int) -> Unit)

/**
 * Remote-friendly settings: up/down moves between rows, left/right changes a value, OK opens a
 * list or the keyboard. Things that are painful with a remote (cookies) stay on the phone page.
 */
@Composable
fun SettingsScreen(
    state: UiState,
    configUrl: String?,
    antennaChannels: List<Pair<String, String>>,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onDone: (() -> Unit)?,
) {
    val s = state.settings
    var editing by remember { mutableStateOf<TextEdit?>(null) }
    var picking by remember { mutableStateOf<Picker?>(null) }
    val firstRow = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstRow.requestFocus() } }

    Row(
        Modifier.fillMaxSize().background(Palette.Bg).padding(horizontal = 36.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (onDone != null) FocusButton("Back to games", onClick = onDone)
            }
            StatusLine(state)
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                leagueSection(state, firstRow, onUpdate, edit = { editing = it }, pick = { picking = it })
                section("Screen")
                item(key = "tiles") {
                    ChoiceRow(
                        label = "Games on screen",
                        options = (1..4).map { it.toString() },
                        selected = s.maxTiles - 1,
                        onSelect = { i -> onUpdate { it.copy(maxTiles = i + 1) } },
                    )
                }
                antennaSection(s, antennaChannels, onUpdate, edit = { editing = it }, pick = { picking = it })
                gamesSection(state, antennaChannels, onUpdate, pick = { picking = it })
                section("Other video")
                item(key = "playlist") {
                    ActionRow(
                        label = "Playlist (M3U) link",
                        value = s.m3uUrl.ifBlank { "Not set" },
                        onClick = {
                            editing = TextEdit("Playlist link", "An M3U playlist URL. Easier to paste from the phone page.", s.m3uUrl, KeyboardType.Uri) { v ->
                                onUpdate { it.copy(m3uUrl = v.trim()) }
                            }
                        },
                    )
                }
                item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
            }
        }
        PhonePanel(configUrl)
    }

    editing?.let { TextEditDialog(it, onDismiss = { editing = null }) }
    picking?.let { PickerDialog(it, onDismiss = { picking = null }) }
}

private fun LazyListScope.leagueSection(
    state: UiState,
    firstRow: FocusRequester,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    edit: (TextEdit) -> Unit,
    pick: (Picker) -> Unit,
) {
    val s = state.settings
    section("League")
    item(key = "league") {
        ActionRow(
            label = "ESPN league ID",
            value = when {
                s.leagueId.isBlank() -> "Not set"
                state.league?.leagueName != null -> "${s.leagueId} · ${state.league.leagueName}"
                else -> s.leagueId
            },
            modifier = Modifier.focusRequester(firstRow),
            onClick = {
                edit(TextEdit("ESPN league ID", "The number after leagueId= in your league's web address.", s.leagueId, KeyboardType.Number) { v ->
                    EspnInput.parseLeague(v)?.let { league ->
                        onUpdate {
                            it.copy(
                                leagueId = league.id,
                                season = league.season ?: it.season,
                                teamId = it.teamId.takeIf { _ -> league.id == it.leagueId },
                            )
                        }
                    }
                })
            },
        )
    }
    item(key = "season") {
        val current = AppSettings.defaultSeason()
        val seasons = ((current - 2)..current).toList().let { if (s.season in it) it else it + s.season }.sorted()
        ChoiceRow(
            label = "Season",
            options = seasons.map { it.toString() },
            selected = seasons.indexOf(s.season),
            onSelect = { i -> onUpdate { it.copy(season = seasons[i]) } },
        )
    }
    item(key = "team") {
        val teams = state.league?.teams.orEmpty()
        val options = listOf("Detect from cookies") + teams.map { it.name }
        val selected = s.teamId?.let { id -> teams.indexOfFirst { it.id == id } + 1 }?.coerceAtLeast(0) ?: 0
        fun choose(i: Int) = onUpdate { it.copy(teamId = if (i == 0) null else teams[i - 1].id) }
        ChoiceRow(
            label = "Your team",
            options = options,
            selected = selected,
            onSelect = ::choose,
            onOpen = { pick(Picker("Your team", options, selected, ::choose)) },
        )
    }
    item(key = "cookies") {
        ActionRow(
            label = "ESPN sign-in cookies",
            value = if (s.hasCookies) "Saved" else "Not set · add on the phone page",
            onClick = {},
        )
    }
}

private fun LazyListScope.antennaSection(
    s: AppSettings,
    channels: List<Pair<String, String>>,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    edit: (TextEdit) -> Unit,
    pick: (Picker) -> Unit,
) {
    section("Antenna")
    item(key = "antenna") {
        ChoiceRow(
            label = "Use the TV's antenna",
            options = listOf("Off", "On"),
            selected = if (s.antennaEnabled) 1 else 0,
            onSelect = { i -> onUpdate { it.copy(antennaEnabled = i == 1) } },
        )
    }
    if (!s.antennaEnabled) return
    if (channels.isEmpty()) {
        item(key = "antenna-loading") {
            ActionRow(label = "Channels", value = "Loading from the TV… (or run a channel scan in Live TV)", onClick = {})
        }
    }
    OtaMatching.NETWORKS.forEach { network ->
        item(key = "net-$network") {
            val options = listOf("Not set") + channels.map { (number, name) -> "$number $name".trim() }
            val current = s.antennaChannels[network]
            val selected = channels.indexOfFirst { it.first == current }.let { if (it < 0) 0 else it + 1 }
            fun choose(i: Int) = onUpdate {
                val pins = it.antennaChannels.toMutableMap()
                if (i == 0) pins.remove(network) else pins[network] = channels[i - 1].first
                it.copy(antennaChannels = pins)
            }
            ChoiceRow(
                label = "$network channel",
                options = if (current != null && selected == 0) listOf(current) + options.drop(1) else options,
                selected = selected,
                onSelect = ::choose,
                onOpen = { pick(Picker("$network channel", options, selected, ::choose)) },
            )
        }
    }
    item(key = "hdhr") {
        ActionRow(
            label = "HDHomeRun address",
            value = s.antennaHost.ifBlank { "Not used" },
            onClick = {
                edit(TextEdit("HDHomeRun address", "Only for an HDHomeRun network tuner. Leave blank otherwise.", s.antennaHost, KeyboardType.Uri) { v ->
                    onUpdate { it.copy(antennaHost = v.trim()) }
                })
            },
        )
    }
}

private fun LazyListScope.gamesSection(
    state: UiState,
    channels: List<Pair<String, String>>,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    pick: (Picker) -> Unit,
) {
    val s = state.settings
    val games = state.games.filter { it.state != GameState.POST }.sortedBy { it.start }
    if (games.isEmpty()) return
    section("This week's games")
    games.forEach { game ->
        item(key = "game-${game.id}") {
            val override = s.manualStreams[game.id]
            // Auto, then the antenna channels set for each network, then any link set from the phone.
            val antenna = if (s.antennaEnabled) {
                s.antennaChannels.entries.map { (network, number) ->
                    val name = channels.firstOrNull { it.first == number }?.second.orEmpty()
                    "Antenna $network $number $name".trim() to "${TvTunerProvider.TV_PREFIX}$number"
                }.distinctBy { it.second }
            } else {
                emptyList()
            }
            val choices = buildList {
                add("Auto" to null)
                addAll(antenna)
                if (override != null && antenna.none { it.second == override }) {
                    add((if (override.startsWith(TvTunerProvider.TV_PREFIX)) "Antenna ${override.removePrefix(TvTunerProvider.TV_PREFIX)}" else "Link from phone") to override)
                }
            }
            val selected = choices.indexOfFirst { it.second == override }.coerceAtLeast(0)
            fun choose(i: Int) = onUpdate {
                val streams = it.manualStreams.toMutableMap()
                choices[i].second?.let { v -> streams[game.id] = v } ?: streams.remove(game.id)
                it.copy(manualStreams = streams)
            }
            ChoiceRow(
                label = "${game.away.abbrev} @ ${game.home.abbrev}",
                sublabel = listOfNotNull(game.statusText(), game.broadcast).joinToString(" · "),
                options = choices.map { it.first },
                selected = selected,
                onSelect = ::choose,
                onOpen = { pick(Picker("${game.matchupLabel} video", choices.map { it.first }, selected, ::choose)) },
            )
        }
    }
}

private fun LazyListScope.section(title: String) {
    item(key = "section-$title") {
        Text(
            title.uppercase(),
            color = Palette.Muted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp, start = 4.dp),
        )
    }
}

/** A row whose value cycles with left/right; OK opens [onOpen] (a full list) or steps forward. */
@Composable
private fun ChoiceRow(
    label: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    sublabel: String? = null,
    onOpen: (() -> Unit)? = null,
) {
    val count = options.size.coerceAtLeast(1)
    val index = selected.coerceIn(0, count - 1)
    SettingRow(
        label = label,
        sublabel = sublabel,
        value = options.getOrElse(index) { "" },
        arrows = options.size > 1,
        modifier = modifier,
        onLeft = { onSelect((index - 1 + count) % count) },
        onRight = { onSelect((index + 1) % count) },
        onClick = onOpen ?: { onSelect((index + 1) % count) },
    )
}

@Composable
private fun ActionRow(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SettingRow(label = label, value = value, arrows = false, modifier = modifier, onClick = onClick)
}

@Composable
private fun SettingRow(
    label: String,
    value: String,
    arrows: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sublabel: String? = null,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) Palette.PanelRaised else Palette.Panel)
            .border(2.dp, if (focused) Palette.Mine else Color.Transparent, RoundedCornerShape(6.dp))
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) {
                    return@onKeyEvent e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                }
                when (e.key) {
                    Key.DirectionLeft -> onLeft?.let { it(); true } ?: false
                    Key.DirectionRight -> onRight?.let { it(); true } ?: false
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onClick(); true }
                    else -> false
                }
            }
            .focusable()
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            sublabel?.let { Text(it, color = Palette.Faint, fontSize = 10.sp) }
        }
        val arrowColor = if (focused && arrows) Palette.Mine else Color.Transparent
        Text("‹", color = arrowColor, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 6.dp))
        Text(
            value,
            color = if (focused) Palette.Text else Palette.Muted,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text("›", color = arrowColor, fontSize = 16.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun StatusLine(state: UiState) {
    val matchup = state.matchup
    val (text, color) = when {
        !state.settings.isConfigured -> "Not connected: set your ESPN league ID below." to Palette.Muted
        state.error != null -> state.error to Palette.Warn
        matchup != null -> "Connected: ${matchup.mine.name} vs ${matchup.opponent?.name ?: "bye week"}" to Palette.Gain
        state.league != null && !state.loading -> "League found. Pick your team below." to Palette.Warn
        else -> "Checking your league…" to Palette.Muted
    }
    Text(text, color = color, fontSize = 12.sp, maxLines = 2)
    state.antennaError?.let { Text("Antenna: $it", color = Palette.Warn, fontSize = 11.sp) }
}

@Composable
private fun PhonePanel(configUrl: String?) {
    Column(Modifier.width(230.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White).padding(10.dp)) {
            configUrl?.let { QrCode(it, Modifier.size(170.dp)) } ?: Spacer(Modifier.size(170.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text("Paste cookies and links from your phone", color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            configUrl ?: "Connect the TV to your network to use the phone page.",
            color = Palette.Mine,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun TextEditDialog(edit: TextEdit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(edit.initial, TextRange(edit.initial.length))) }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun save() {
        edit.onSave(text.text)
        onDismiss()
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(520.dp).clip(RoundedCornerShape(10.dp)).background(Palette.PanelRaised).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(edit.title, color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(edit.hint, color = Palette.Muted, fontSize = 11.sp)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = Palette.Text, fontSize = 16.sp),
                cursorBrush = SolidColor(Palette.Mine),
                keyboardOptions = KeyboardOptions(keyboardType = edit.keyboard, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Palette.Bg)
                    .border(1.dp, Palette.Line, RoundedCornerShape(6.dp))
                    .padding(12.dp)
                    .focusRequester(field),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FocusButton("Save", onClick = ::save)
                FocusButton("Cancel", onClick = onDismiss)
            }
        }
    }
    LaunchedEffect(Unit) {
        runCatching { field.requestFocus() }
        keyboard?.show()
    }
}

@Composable
private fun PickerDialog(picker: Picker, onDismiss: () -> Unit) {
    val selected = remember { FocusRequester() }
    val list = rememberLazyListState()
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(420.dp).clip(RoundedCornerShape(10.dp)).background(Palette.PanelRaised).padding(16.dp)) {
            Text(picker.title, color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            LazyColumn(state = list, modifier = Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(picker.options) { i, option ->
                    FocusButton(
                        text = (if (i == picker.selected) "✓ " else "") + option,
                        modifier = Modifier.fillMaxWidth().let { if (i == picker.selected) it.focusRequester(selected) else it },
                        onClick = {
                            picker.onPick(i)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        list.scrollToItem(picker.selected.coerceAtLeast(0))
        runCatching { selected.requestFocus() }
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
