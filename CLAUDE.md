# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Fantasy Multiview: a single-module Android TV / Google TV app (Kotlin, Jetpack Compose, Media3 ExoPlayer, OkHttp) for watching NFL games involving the user's ESPN Fantasy matchup. It plays one game at a time, with a strip of the week's relevant games below it and live fantasy scores on the right.

## Commands

```sh
./gradlew :app:assembleDebug                      # build APK
./gradlew installDebug                            # build + install via adb (generic TVs)
./gradlew :app:testDebugUnitTest                  # JVM unit tests (JUnit 4, no Robolectric)
./gradlew :app:testDebugUnitTest --tests '*StreamMatchingTest'          # one class
./gradlew :app:testDebugUnitTest --tests '*StreamMatchingTest.parses*'  # one method
./gradlew :app:lint
```

Unit tests cover only pure logic (`EspnInput`, `FailedRequestLogger.redact`, M3U parsing / title matching). Keep logic Android-free in `companion object`s or `object`s when you want it testable.

Debugging on device: failed HTTP exchanges (status >= 400) are logged with credentials redacted under `adb logcat -s HttpDebug`. Provider errors are logged under `StreamResolver`, `XtreamProvider` and `M3uStreamProvider`.

## Architecture

**Data flow.** `AppViewModel` owns everything. It collects `SettingsStore.settings` with `collectLatest` and polls every 30 s: ESPN fantasy (`EspnFantasyClient`, unofficial v3 API) → ESPN scoreboard (`NflScoreboardClient`) → `weekGames` lists the games with a starter in them → `StreamResolver` finds every source per game → `chooseWatching` picks what's on screen → `PlayerPool.sync`. When settings change, the loop restarts. League ID `demo` swaps in `DemoLeague` (real games, fake rosters).

**What's on screen** (`AppViewModel`): the strip lists live games first (most starters, both sides), then upcoming games by kickoff, then finals. OK on a strip card (or on the video) calls `chooseStream`, which opens a `StreamChoice` dialog listing that game's sources. `watch` sets `picked`, which holds until its game leaves the schedule. With nothing picked, the first unfinished game that has a stream plays, and that automatic pick sticks until the game ends.

**Stream sources** (`streams/`): `StreamResolver` collects `StreamProvider.options` from every provider in priority order. Sources flagged `unsure` go last; for example, the local affiliate when its network has several games at once. The order is set in `AppViewModel`:
1. `ManualStreamProvider`: per-game URL from settings. Specs use Kodi-style headers, `url|Referer=…&User-Agent=…` (`StreamSource.parse`). A `tv:2.1` spec is skipped here and handled by the tuner provider.
2. `TvTunerProvider`: the TV's built-in antenna tuner via the Android TvProvider. It plays through a `TvView`, **not** ExoPlayer, so `TUNER` sources are excluded from `PlayerPool`. `TclChannels` covers TCL-specific quirks (writing a channel row, `dbprimarykey` tune extras).
3. `HdHomeRunProvider`: network tuner. It runs only if `antennaHost` is set or the TV has no built-in tuner. `OtaMatching` holds the network → channel logic shared with the TV tuner.
4. `XtreamProvider`: used when `m3uUrl` is an Xtream `get.php`/`player_api.php` link. It reads `player_api.php` JSON (only NFL and US network/sports categories, streamed with `decodeToSequence`), and falls back from https to http.
5. `M3uStreamProvider`: plain M3U playlists only; it skips Xtream links.

Both IPTV providers match through `ChannelMatcher`: an event channel naming both teams, else the network affiliate in either team's home market, else a national feed. They send `PLAYER_USER_AGENT`, and after a failure they wait 2 min before asking again, because IPTV panels ban clients that hammer them. Errors go to `UiState.playlistError`.

**Playback** (`player/PlayerPool`): one ExoPlayer for the game on screen, sharing the app's OkHttp client with per-source headers. Switching games releases the old player first, which matters for IPTV accounts allowed only one connection. On error the same source is retried after 5 s (10 s for IPTV); there's no fallback to the next candidate.

**Settings** live in two places that must stay in sync when you add a field:
- `data/SettingsStore.kt`: the `AppSettings` data class plus SharedPreferences load/save (maps are stored as JSON strings).
- `config/ConfigServer.kt`: a hand-rolled LAN HTTP server on port 8765, under a random path token persisted as `setup_token`. The phone setup page is an inline HTML string, and form fields are parsed in `save()`. The page must never echo saved secrets (cookies).
- `ui/SettingsScreen.kt`: the D-pad settings screen on the TV.

`MainActivity` shows `SettingsScreen` until a matchup is loaded, then `WatchScreen`.

## Gotchas

- ESPN's fantasy API is undocumented. Field-name changes between seasons belong in `EspnFantasyClient` only.
- `android:usesCleartextTraffic="true"` is intentional (HDHomeRun, LAN config server, many IPTV hosts).
- Antenna (ATSC 1.0) is MPEG-2, which many Google TV dongles can't decode in hardware. That's why there is the HDHomeRun transcode setting.
- `.claude/settings.json` holds an API token. Don't commit it.
