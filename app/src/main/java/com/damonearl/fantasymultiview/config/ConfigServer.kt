package com.damonearl.fantasymultiview.config

import android.util.Log
import com.damonearl.fantasymultiview.data.DemoLeague
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
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Tiny LAN web server so settings (cookies, URLs) can be pasted from a phone instead of typed
 * with a remote. Every route lives under a random token shown only on the TV screen, and saved
 * secrets are never echoed back into the page.
 */
class ConfigServer(
    private val store: SettingsStore,
    private val teams: () -> List<FantasyTeamInfo>,
    private val games: () -> List<NflGame>,
) {
    val token: String = buildString {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val random = SecureRandom()
        repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) }
    }

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
        } catch (e: Exception) {
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
            val clearCookies = form["clear_cookies"] == "on"
            s.copy(
                leagueId = form["league_id"]?.trim()
                    ?.let { if (it.equals(DemoLeague.ID, ignoreCase = true)) DemoLeague.ID else it.filter(Char::isDigit) }
                    ?: s.leagueId,
                season = form["season"]?.trim()?.toIntOrNull() ?: s.season,
                teamId = form["team_id"]?.let { it.trim().toIntOrNull() } ?: s.teamId.takeIf { "team_id" !in form },
                espnS2 = when {
                    clearCookies -> ""
                    form["espn_s2"].isNullOrBlank() -> s.espnS2
                    // Sent back verbatim: the cookie value is already URL-encoded as the browser stores it.
                    else -> form.getValue("espn_s2").trim()
                },
                swid = when {
                    clearCookies -> ""
                    form["swid"].isNullOrBlank() -> s.swid
                    else -> form.getValue("swid").trim()
                },
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
        val teamList = teams()
        val upcoming = games().filter { it.state != GameState.POST }

        val teamField = if (teamList.isEmpty()) {
            """<input name="team_id" inputmode="numeric" value="${s.teamId ?: ""}" placeholder="Auto-detect from SWID">"""
        } else {
            val options = teamList.joinToString("") { t ->
                val sel = if (t.id == s.teamId) " selected" else ""
                """<option value="${t.id}"$sel>${esc(t.name)}</option>"""
            }
            val autoSel = if (s.teamId == null) " selected" else ""
            """<select name="team_id"><option value=""$autoSel>Auto-detect from SWID</option>$options</select>"""
        }
        val tileOptions = (1..4).joinToString("") { n ->
            """<option${if (n == s.maxTiles) " selected" else ""}>$n</option>"""
        }
        val channelRows = OTA_NETWORKS.joinToString("") { n ->
            """<label>$n channel<input name="ch_$n" value="${esc(s.antennaChannels[n].orEmpty())}" placeholder="auto (e.g. 2.1)"></label>"""
        }
        val gameRows = if (upcoming.isEmpty()) "<p class=muted>Games appear here once your league loads.</p>" else
            upcoming.joinToString("") { g ->
                val label = "${g.matchupLabel} · ${g.detail}${g.broadcast?.let { " · $it" } ?: ""}"
                """<label>${esc(label)}<input name="stream_${g.id}" value="${esc(s.manualStreams[g.id].orEmpty())}" placeholder="https://…/index.m3u8"></label>"""
            }

        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Fantasy Multiview setup</title><style>
:root{color-scheme:dark}body{font:16px system-ui,sans-serif;background:#0a0e13;color:#e8eef5;margin:0;padding:16px;max-width:640px;margin:auto}
h1{font-size:22px}h2{font-size:15px;text-transform:uppercase;letter-spacing:.08em;color:#8a97a8;margin:28px 0 8px}
label{display:block;margin:12px 0;font-size:14px;color:#c4cfdc}input,select{display:block;width:100%;box-sizing:border-box;margin-top:4px;padding:10px;border-radius:8px;border:1px solid #243040;background:#111821;color:#e8eef5;font-size:16px}
input[type=checkbox]{display:inline;width:auto;margin-right:8px}.muted{color:#8a97a8;font-size:13px}
button{margin-top:24px;width:100%;padding:14px;border:0;border-radius:10px;background:#3ba3ff;color:#04121f;font-weight:700;font-size:17px}
.ok{background:#12301f;border:1px solid #34d399;padding:10px;border-radius:8px}</style></head><body>
<h1>Fantasy Multiview</h1>${if (saved) "<p class=ok>Saved. The TV will refresh now.</p>" else ""}
<form method="post">
<h2>ESPN league</h2>
<label>League ID<input name="league_id" inputmode="numeric" value="${esc(s.leagueId)}" placeholder="From the fantasy.espn.com URL: leagueId=… (or &quot;demo&quot;)"></label>
<label>Season<input name="season" inputmode="numeric" value="${s.season}"></label>
<label>Your team$teamField</label>
<h2>Private league cookies</h2>
<p class=muted>On a computer logged in to ESPN, open DevTools → Application → Cookies → espn.com and copy <b>espn_s2</b> and <b>SWID</b>. ${if (s.hasCookies) "Cookies are saved; leave blank to keep them." else ""}</p>
<label>espn_s2<input name="espn_s2" autocomplete="off" placeholder="${if (s.espnS2.isNotBlank()) "saved" else ""}"></label>
<label>SWID<input name="swid" autocomplete="off" placeholder="${if (s.swid.isNotBlank()) "saved" else "{XXXXXXXX-…}"}"></label>
<label><input type="checkbox" name="clear_cookies">Clear saved cookies</label>
<h2>Antenna (HDHomeRun)</h2>
<label><input type="checkbox" name="antenna_enabled"${if (s.antennaEnabled) " checked" else ""}>Use over-the-air channels</label>
<label>Tuner IP<input name="antenna_host" value="${esc(s.antennaHost)}" placeholder="auto-discover"></label>
<label>Transcode profile (HDHomeRun EXTEND only)<input name="antenna_transcode" value="${esc(s.antennaTranscode)}" placeholder="blank = native, or mobile / heavy / internet720"></label>
$channelRows
<h2>Other sources</h2>
<label>M3U playlist URL<input name="m3u_url" value="${esc(s.m3uUrl)}" placeholder="https://…/playlist.m3u"></label>
<label>Screens<select name="max_tiles">$tileOptions</select></label>
<h2>Per-game stream overrides</h2>
<p class=muted>Optional. HLS/DASH/TS URL; add headers as <code>url|Referer=…&amp;User-Agent=…</code></p>
$gameRows
<button type="submit">Save</button></form></body></html>"""
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
