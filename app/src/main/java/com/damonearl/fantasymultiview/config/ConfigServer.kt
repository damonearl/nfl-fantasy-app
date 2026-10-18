package com.damonearl.fantasymultiview.config

import android.util.Log
import com.damonearl.fantasymultiview.data.FantasyTeamInfo
import com.damonearl.fantasymultiview.data.GameState
import com.damonearl.fantasymultiview.data.NflGame
import com.damonearl.fantasymultiview.data.SettingsStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/** Connection state shown at the top of the setup page. */
sealed interface SetupStatus {
    data object NotStarted : SetupStatus
    data object Checking : SetupStatus
    data class Failed(val message: String) : SetupStatus
    data object NeedTeam : SetupStatus
    data class Connected(val matchup: String) : SetupStatus
}

/**
 * Tiny LAN web server so settings (cookies, URLs) can be pasted from a phone instead of typed
 * with a remote. Every route lives under a random token shown only on the TV screen, and saved
 * secrets are never echoed back into the page.
 */
class ConfigServer(
    private val store: SettingsStore,
    private val teams: () -> List<FantasyTeamInfo>,
    private val games: () -> List<NflGame>,
    private val status: () -> SetupStatus,
) {
    private val token = store.setupToken

    @Volatile private var server: ServerSocket? = null

    val url: String?
        get() {
            val port = server?.localPort ?: return null
            val ip = localIpv4() ?: return null
            return "http://$ip:$port/$token"
        }

    fun start() {
        if (server != null) return
        val socket = runCatching { ServerSocket(PORT) }.getOrElse { ServerSocket(0) }
        server = socket
        thread(name = "config-server", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { client.use(::handle) }
            }
        }
    }

    fun stop() {
        server?.close()
        server = null
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            val (method, target) = requestLine.split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
            var contentLength = 0
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            val path = target.substringBefore('?')
            val out = client.getOutputStream()
            fun respond(status: String, body: String = "", extraHeaders: String = "") {
                val bytes = body.toByteArray(Charsets.UTF_8)
                out.write(
                    ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                        "Cache-Control: no-store\r\nConnection: close\r\n$extraHeaders\r\n").toByteArray(Charsets.ISO_8859_1)
                )
                out.write(bytes)
                out.flush()
            }

            when {
                path != "/$token" -> respond("404 Not Found", "Not found")
                method == "GET" -> respond("200 OK", page(saved = target.contains("saved=1")))
                method == "POST" && contentLength in 0..MAX_BODY -> {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    save(parseForm(String(buf, 0, read)))
                    respond("303 See Other", extraHeaders = "Location: /$token?saved=1\r\n")
                }
                else -> respond("400 Bad Request", "Bad request")
            }
        } catch (e: Throwable) {
            // A bad request must never take down the TV app; this runs on a bare background thread.
            Log.w("ConfigServer", "request failed", e)
        }
    }

    private fun save(form: Map<String, String>) {
        store.update { s ->
            // Keep overrides only for games still on the schedule, so old weeks don't pile up.
            val listed = games().map { it.id }.toSet()
            val manual = s.manualStreams.filterKeys { listed.isEmpty() || it in listed }.toMutableMap()
            form.filterKeys { it.startsWith("stream_") }.forEach { (key, value) ->
                val id = key.removePrefix("stream_")
                if (value.isBlank()) manual.remove(id) else manual[id] = value.trim()
            }
            val channels = s.antennaChannels.toMutableMap()
            OTA_NETWORKS.forEach { network ->
                val value = form["ch_$network"]?.trim() ?: return@forEach
                if (value.isEmpty()) channels.remove(network) else channels[network] = value
            }
            val league = form["league"]?.let(EspnInput::parseLeague)
            val cookies = EspnInput.parseCookies(form["cookies"].orEmpty())
            val clearCookies = form["clear_cookies"] == "on"
            val leagueChanged = league != null && league.id != s.leagueId
            s.copy(
                leagueId = league?.id ?: s.leagueId,
                season = league?.season ?: form["season"]?.trim()?.toIntOrNull() ?: s.season,
                // A different league means different team ids; fall back to SWID detection.
                teamId = if (leagueChanged) null else form["team_id"]?.trim()?.toIntOrNull() ?: s.teamId.takeIf { "team_id" !in form },
                espnS2 = if (clearCookies) "" else cookies.espnS2 ?: s.espnS2,
                swid = if (clearCookies) "" else cookies.swid ?: s.swid,
                m3uUrl = form["m3u_url"]?.trim() ?: s.m3uUrl,
                maxTiles = form["max_tiles"]?.toIntOrNull()?.coerceIn(1, 4) ?: s.maxTiles,
                antennaEnabled = form["antenna_enabled"] == "on",
                antennaHost = form["antenna_host"]?.trim() ?: s.antennaHost,
                antennaTranscode = form["antenna_transcode"]?.trim() ?: s.antennaTranscode,
                antennaChannels = channels,
                manualStreams = manual,
            )
        }
    }

    private fun page(saved: Boolean): String {
        val s = store.settings.value
        val st = status()
        val teamList = teams()
        val upcoming = games().filter { it.state != GameState.POST }

        val (statusClass, statusText) = when (st) {
            is SetupStatus.NotStarted -> "idle" to "Not connected yet. Start with step 1."
            is SetupStatus.Checking -> "idle" to "Checking your league…"
            is SetupStatus.Failed -> "bad" to st.message
            is SetupStatus.NeedTeam -> "warn" to "Found your league. Pick your team in step 3."
            is SetupStatus.Connected -> "good" to "Connected: ${st.matchup}. The TV is showing your games."
        }
        // Re-check shortly after a save, and keep polling while ESPN is being asked.
        val refresh = if (saved || st is SetupStatus.Checking) """<meta http-equiv="refresh" content="3;url=/$token">""" else ""

        val leagueValue = if (s.leagueId.isNotBlank()) s.leagueId else ""
        val cookieNote = if (s.hasCookies) {
            """<p class="saved">✓ Cookies saved. Paste new ones only if ESPN stops working.</p>
<label class=inline><input type="checkbox" name="clear_cookies"> Remove saved cookies</label>"""
        } else ""

        val teamStep = if (teamList.isEmpty()) {
            "<p class=muted>Your teams appear here once the league connects.</p>"
        } else {
            val autoSel = if (s.teamId == null) " selected" else ""
            val options = teamList.joinToString("") { t ->
                """<option value="${t.id}"${if (t.id == s.teamId) " selected" else ""}>${esc(t.name)}</option>"""
            }
            """<select name="team_id"><option value=""$autoSel>Detect automatically (needs cookies)</option>$options</select>"""
        }

        val tileOptions = (1..4).joinToString("") { n ->
            """<option${if (n == s.maxTiles) " selected" else ""}>$n</option>"""
        }
        val channelRows = OTA_NETWORKS.joinToString("") { n ->
            """<label>$n is on channel<input name="ch_$n" value="${esc(s.antennaChannels[n].orEmpty())}" placeholder="auto-detect (e.g. 2.1)"></label>"""
        }
        val gameRows = if (upcoming.isEmpty()) "<p class=muted>Games appear here once your league connects.</p>" else
            upcoming.joinToString("") { g ->
                val label = "${g.matchupLabel} · ${g.detail}${g.broadcast?.let { " · $it" } ?: ""}"
                """<label>${esc(label)}<input name="stream_${g.id}" value="${esc(s.manualStreams[g.id].orEmpty())}" placeholder="https://…/index.m3u8"></label>"""
            }

        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">$refresh
<title>Fantasy Multiview setup</title><style>
:root{color-scheme:dark}*{box-sizing:border-box}
body{font:16px/1.45 system-ui,sans-serif;background:#0a0e13;color:#e8eef5;margin:0 auto;padding:20px 16px 40px;max-width:600px}
h1{font-size:22px;margin:0 0 12px}
.status{padding:12px 14px;border-radius:10px;font-weight:600;margin-bottom:8px}
.idle{background:#17202b;color:#c4cfdc}.good{background:#12301f;color:#6ee7b7}.bad{background:#3a1717;color:#fca5a5}.warn{background:#33290f;color:#fcd34d}
.tip{font-size:14px;color:#8a97a8;margin:0 0 20px}
.step{background:#10161e;border:1px solid #1e2a38;border-radius:12px;padding:16px;margin:14px 0}
.step h2{font-size:17px;margin:0 0 4px}.num{display:inline-block;width:24px;height:24px;border-radius:12px;background:#3ba3ff;color:#04121f;text-align:center;font-size:14px;line-height:24px;margin-right:8px}
.opt{font-size:12px;font-weight:600;color:#8a97a8;text-transform:uppercase;letter-spacing:.06em;margin-left:6px}
ol{padding-left:20px;margin:8px 0;color:#c4cfdc;font-size:15px}li{margin:6px 0}
code,kbd{background:#1e2a38;border-radius:4px;padding:1px 5px;font-size:14px;color:#e8eef5}
label{display:block;margin:12px 0 0;font-size:14px;color:#c4cfdc}label.inline{display:flex;align-items:center;gap:8px}
input,select,textarea{display:block;width:100%;margin-top:6px;padding:12px;border-radius:8px;border:1px solid #2b3a4d;background:#0a0e13;color:#e8eef5;font:16px system-ui,sans-serif}
textarea{min-height:90px;font-family:ui-monospace,monospace;font-size:13px}
input[type=checkbox]{display:inline;width:20px;height:20px;margin:0}
.muted{color:#8a97a8;font-size:14px;margin:8px 0 0}.saved{color:#6ee7b7;font-size:14px;margin:10px 0 0}
details{margin-top:14px}summary{cursor:pointer;color:#3ba3ff;font-weight:600;padding:8px 0}
details details{margin-left:4px}details summary.small{font-size:14px;color:#8a97a8;font-weight:500}
button{margin-top:22px;width:100%;padding:15px;border:0;border-radius:10px;background:#3ba3ff;color:#04121f;font-weight:700;font-size:17px}
</style></head><body>
<h1>Fantasy Multiview</h1>
<div class="status $statusClass">${esc(statusText)}</div>
<p class=tip>Tip: open this same address on a computer. Step 2 is much easier there.</p>
<form method="post">

<div class=step><h2><span class=num>1</span>Your league</h2>
<ol>
<li>In a web browser, go to <b>fantasy.espn.com</b> and open your league.</li>
<li>Copy the page address. It contains <code>leagueId=</code> followed by a number.</li>
<li>Paste it here. The whole link is fine.</li>
</ol>
<input name="league" value="${esc(leagueValue)}" placeholder="https://fantasy.espn.com/football/league?leagueId=…" autocomplete="off">
<p class=muted>Just want to look around? Type <code>demo</code>.</p>
</div>

<div class=step><h2><span class=num>2</span>ESPN sign-in<span class=opt>private leagues only</span></h2>
<p class=muted style="margin-top:0">Most leagues are private. If step 1 shows an error saying your league is private, do this on a computer:</p>
<ol>
<li>Sign in at <b>fantasy.espn.com</b> in Chrome or Edge.</li>
<li>Press <kbd>F12</kbd> (Mac: <kbd>⌥ Option</kbd> <kbd>⌘ Cmd</kbd> <kbd>I</kbd>) and click the <b>Application</b> tab.</li>
<li>In the left sidebar, open <b>Cookies</b> and click <b>https://fantasy.espn.com</b>.</li>
<li>Type <code>espn_s2</code> in the filter box. Click the row and copy its <b>Value</b> (it's long).</li>
<li>Do the same for <code>SWID</code> (it looks like <code>{1A2B3C…}</code>).</li>
<li>Paste both below, in either order.</li>
</ol>
<details><summary class=small>Using Safari or Firefox?</summary>
<p class=muted><b>Safari:</b> Settings → Advanced → turn on "Show features for web developers". Then Develop → Show Web Inspector → <b>Storage</b> → Cookies.<br>
<b>Firefox:</b> press <kbd>F12</kbd> → <b>Storage</b> tab → Cookies.</p></details>
<textarea name="cookies" placeholder="Paste espn_s2 and SWID here" autocomplete="off" spellcheck="false"></textarea>
$cookieNote
</div>

<div class=step><h2><span class=num>3</span>Your team</h2>
$teamStep
</div>

<details><summary>More options: screens, antenna, other video</summary>
<div class=step><h2>Screens</h2>
<label>Games shown at once<select name="max_tiles">$tileOptions</select></label>
<label>Season<input name="season" inputmode="numeric" value="${s.season}"></label>
</div>
<div class=step><h2>Antenna (HDHomeRun)</h2>
<label class=inline><input type="checkbox" name="antenna_enabled"${if (s.antennaEnabled) " checked" else ""}> Use over-the-air channels</label>
<label>Tuner IP address<input name="antenna_host" value="${esc(s.antennaHost)}" placeholder="leave blank to find it automatically"></label>
<label>Transcode profile (HDHomeRun EXTEND only)<input name="antenna_transcode" value="${esc(s.antennaTranscode)}" placeholder="blank, mobile, heavy…"></label>
$channelRows
</div>
<div class=step><h2>Playlist</h2>
<label>M3U playlist URL<input name="m3u_url" value="${esc(s.m3uUrl)}" placeholder="https://…/playlist.m3u"></label>
</div>
<div class=step><h2>Stream for a specific game</h2>
<p class=muted style="margin-top:0">HLS/DASH/TS link. To add headers: <code>url|Referer=…&amp;User-Agent=…</code></p>
$gameRows
</div>
</details>

<button type="submit">Save &amp; connect</button></form></body></html>"""
    }

    private fun parseForm(body: String): Map<String, String> = body.split('&').filter { it.isNotEmpty() }.associate { pair ->
        URLDecoder.decode(pair.substringBefore('='), "UTF-8") to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        private const val PORT = 8765
        private const val MAX_BODY = 64 * 1024
        private val OTA_NETWORKS = listOf("CBS", "FOX", "NBC", "ABC")

        fun localIpv4(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
