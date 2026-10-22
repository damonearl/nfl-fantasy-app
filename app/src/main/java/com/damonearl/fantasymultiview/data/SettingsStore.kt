package com.damonearl.fantasymultiview.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.time.LocalDate

data class AppSettings(
    val leagueId: String = "",
    val season: Int = defaultSeason(),
    val teamId: Int? = null,
    val espnS2: String = "",
    val swid: String = "",
    val m3uUrl: String = "",
    /** Use an HDHomeRun network tuner for over-the-air channels. */
    val antennaEnabled: Boolean = false,
    /** HDHomeRun IP/host; blank = auto-discover on the local network. */
    val antennaHost: String = "",
    /** HDHomeRun EXTEND transcode profile ("mobile", "heavy", ...); blank = native stream. */
    val antennaTranscode: String = "",
    /** Network (e.g. "CBS") -> local guide number (e.g. "2.1"), for when guide data can't place a game. */
    val antennaChannels: Map<String, String> = emptyMap(),
    /** NFL game id -> stream spec ("url" or "url|Referer=...&User-Agent=..."). */
    val manualStreams: Map<String, String> = emptyMap(),
) {
    val isConfigured get() = leagueId.isNotBlank()
    val hasCookies get() = espnS2.isNotBlank() && swid.isNotBlank()

    companion object {
        /** NFL seasons are labelled by the year they start; Jan–Feb games belong to last year's season. */
        fun defaultSeason(today: LocalDate = LocalDate.now()) =
            if (today.monthValue >= 3) today.year else today.year - 1
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Path token for the setup page. Generated once and kept, so the setup address can be bookmarked. */
    val setupToken: String = prefs.getString(KEY_SETUP_TOKEN, null)
        ?: buildString {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val random = SecureRandom()
            repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) }
        }.also { prefs.edit().putString(KEY_SETUP_TOKEN, it).apply() }

    fun update(transform: (AppSettings) -> AppSettings) {
        save(_settings.updateAndGet(transform))
    }

    private fun load() = AppSettings(
        leagueId = prefs.getString(KEY_LEAGUE, "").orEmpty(),
        season = prefs.getInt(KEY_SEASON, AppSettings.defaultSeason()),
        teamId = prefs.getInt(KEY_TEAM, -1).takeIf { it >= 0 },
        espnS2 = prefs.getString(KEY_S2, "").orEmpty(),
        swid = prefs.getString(KEY_SWID, "").orEmpty(),
        m3uUrl = prefs.getString(KEY_M3U, "").orEmpty(),
        antennaEnabled = prefs.getBoolean(KEY_ANTENNA, false),
        antennaHost = prefs.getString(KEY_ANTENNA_HOST, "").orEmpty(),
        antennaTranscode = prefs.getString(KEY_ANTENNA_TRANSCODE, "").orEmpty(),
        antennaChannels = loadMap(KEY_ANTENNA_CHANNELS),
        manualStreams = loadMap(KEY_MANUAL),
    )

    private fun loadMap(key: String): Map<String, String> = prefs.getString(key, null)
        ?.let { runCatching { Json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
        .orEmpty()

    private fun save(s: AppSettings) {
        prefs.edit()
            .putString(KEY_LEAGUE, s.leagueId)
            .putInt(KEY_SEASON, s.season)
            .putInt(KEY_TEAM, s.teamId ?: -1)
            .putString(KEY_S2, s.espnS2)
            .putString(KEY_SWID, s.swid)
            .putString(KEY_M3U, s.m3uUrl)
            .putBoolean(KEY_ANTENNA, s.antennaEnabled)
            .putString(KEY_ANTENNA_HOST, s.antennaHost)
            .putString(KEY_ANTENNA_TRANSCODE, s.antennaTranscode)
            .putString(KEY_ANTENNA_CHANNELS, Json.encodeToString(s.antennaChannels))
            .putString(KEY_MANUAL, Json.encodeToString(s.manualStreams))
            .apply()
    }

    private companion object {
        const val KEY_LEAGUE = "league_id"
        const val KEY_SEASON = "season"
        const val KEY_TEAM = "team_id"
        const val KEY_S2 = "espn_s2"
        const val KEY_SWID = "swid"
        const val KEY_M3U = "m3u_url"
        const val KEY_MANUAL = "manual_streams"
        const val KEY_SETUP_TOKEN = "setup_token"
        const val KEY_ANTENNA = "antenna_enabled"
        const val KEY_ANTENNA_HOST = "antenna_host"
        const val KEY_ANTENNA_TRANSCODE = "antenna_transcode"
        const val KEY_ANTENNA_CHANNELS = "antenna_channels"
    }
}
