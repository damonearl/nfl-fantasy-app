package com.damonearl.fantasymultiview.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Live NFL game status from ESPN's public scoreboard feed. */
class NflScoreboardClient(private val http: OkHttpClient, private val json: Json) {

    /** Games for a regular-season [week]; with no week, ESPN's current week. */
    suspend fun fetch(season: Int, week: Int?): List<NflGame> = withContext(Dispatchers.IO) {
        json.parseToJsonElement(load(season, week)).obj["events"]?.jsonArray.orEmpty().mapNotNull { parseEvent(it.jsonObject) }
    }

    /** ESPN's current NFL week number. */
    suspend fun currentWeek(): Int = withContext(Dispatchers.IO) {
        json.parseToJsonElement(load(null, null)).obj["week"]?.obj?.str("number")?.toIntOrNull() ?: 1
    }

    private fun load(season: Int?, week: Int?): String {
        val url = "https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard" +
            if (week != null) "?seasontype=2&week=$week&dates=$season" else ""
        return http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw EspnException("Scoreboard returned HTTP ${response.code}.")
            response.body?.string().orEmpty()
        }
    }

    private fun parseEvent(event: JsonObject): NflGame? {
        val comp = event["competitions"]?.jsonArray?.firstOrNull()?.obj ?: return null
        val competitors = comp["competitors"]?.jsonArray.orEmpty().map { it.obj }
        val homeJson = competitors.firstOrNull { it.str("homeAway") == "home" } ?: return null
        val awayJson = competitors.firstOrNull { it.str("homeAway") == "away" } ?: return null
        val idToAbbrev = competitors.associate { it.str("id") to it.obj["team"]?.obj?.str("abbreviation") }

        val status = comp["status"]?.obj ?: event["status"]?.obj
        val type = status?.get("type")?.obj
        val state = when (type?.str("state")) {
            "in" -> GameState.IN
            "post" -> GameState.POST
            else -> GameState.PRE
        }
        val situation = comp["situation"]?.obj
        return NflGame(
            id = event.str("id") ?: return null,
            home = parseTeam(homeJson, state),
            away = parseTeam(awayJson, state),
            state = state,
            detail = type?.str("shortDetail") ?: type?.str("detail").orEmpty(),
            start = event.str("date")?.let(::parseDate),
            broadcast = comp["broadcasts"]?.jsonArray?.firstOrNull()?.obj?.get("names")?.jsonArray
                ?.firstOrNull()?.jsonPrimitive?.contentOrNull,
            possession = situation?.str("possession")?.let { idToAbbrev[it] },
            downDistance = situation?.str("shortDownDistanceText"),
            redZone = situation?.get("isRedZone")?.jsonPrimitive?.booleanOrNull == true,
        )
    }

    private fun parseTeam(c: JsonObject, state: GameState): NflTeam {
        val team = c["team"]?.obj
        return NflTeam(
            abbrev = team?.str("abbreviation").orEmpty(),
            name = team?.str("name") ?: team?.str("shortDisplayName").orEmpty(),
            location = team?.str("location").orEmpty(),
            score = if (state == GameState.PRE) null else c.str("score")?.toIntOrNull(),
        )
    }

    private fun parseDate(s: String): Instant? = runCatching { Instant.parse(s) }.getOrNull()
        // ESPN sometimes omits seconds: "2026-10-18T17:00Z"
        ?: runCatching { OffsetDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmX")).toInstant() }.getOrNull()

    private val JsonElement.obj get() = jsonObject
    private fun JsonObject.str(key: String) = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
