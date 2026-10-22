# Fantasy Multiview

A Google TV / Android TV app that watches your ESPN Fantasy Football matchup: one game on screen,
every game that matters this week in a strip below it, and live fantasy points down the right
side: your lineup on top, your opponent's below.

- **Game strip**: every game with one of your or your opponent's starters, live ones first (most
  starters first), then upcoming kickoffs, then finals. Each card shows the score, starter counts
  for each side, and what it can be watched on.
- **Remote**: left/right moves along the strip; **OK** on a game opens its stream list (only
  sources that plausibly carry that game) and switches the video once you pick one. **OK** on the
  video changes its stream. **Down** reaches Settings. Until you pick, the most relevant game with
  a stream plays.
- **Settings on the TV**: up/down moves between rows, left/right changes a value, OK opens a list
  or the keyboard. Covers league, team, antenna channels per network, and the IPTV link. ESPN
  cookies are pasted from the phone page (QR code on the settings screen).
- **Score rails**: total, projection, playing / yet-to-play counts, per-player game clock, and a
  green flash with the delta whenever a player scores.

## Setup

1. Build and sideload (Developer options → USB/network debugging on the TV):
   ```sh
   adb connect <tv-ip>:5555
   ./gradlew installDebug
   ```
2. Open the app and scan the QR code with your phone. It opens a setup page served by the TV
   (LAN only, guarded by a random token shown on screen).
3. Enter your **league ID** (from `fantasy.espn.com/football/league?leagueId=…`). For private
   leagues also paste the `espn_s2` and `SWID` cookies (desktop browser → DevTools →
   Application → Cookies → espn.com). With SWID set, your team is detected automatically.

Use league ID `demo` to preview the layout with real live NFL games and synthetic rosters.

## Video sources

Sources are tried in this order per game:

| Source | How it finds a game |
| --- | --- |
| **Per-game URL** | Paste an HLS/DASH/TS URL next to the game on the setup page. Headers: `url\|Referer=…&User-Agent=…` |
| **Antenna (HDHomeRun)** | Reads the tuner's lineup. HDHomeRun guide data places a game on a channel when a program names both teams. Otherwise the game's network (CBS/FOX/NBC/ABC) is used, but only when that network has one game in the window, since local affiliates carry a single regional game. You can pin network → channel (e.g. `CBS = 2.1`); the stream list also offers that channel when its network has several games on, marked as maybe. |
| **IPTV (Xtream or M3U)** | Paste the provider's playlist link. Xtream links (`…/get.php?username=…&password=…`) are read through the panel's JSON API, which works even when the provider blocks the M3U download. Only NFL and US network/sports categories are fetched. A game is matched to an event channel naming both teams, else its network's affiliate in either team's home market (e.g. `CBS 2 (WBBM) CHICAGO` for a Bears game on CBS), else the national feed (ESPN, NFL Network, or a network's only game in that window). Plain M3U playlists use the same matching; `#EXTVLCOPT` referrer/user-agent are honored. |

Antenna notes: US broadcasts (ATSC 1.0) are MPEG-2 video, which many Google TV dongles can't
decode in hardware (NVIDIA Shield and most TVs can). An HDHomeRun EXTEND can transcode to
H.264. Set a profile like `mobile` or `heavy` on the setup page. ATSC 3.0 channels using AC-4
audio generally won't play.

## Code map

```
data/      ESPN fantasy v3 client, ESPN scoreboard client, settings, demo league
streams/   StreamProvider interface + manual, TV tuner, HDHomeRun, Xtream, M3U providers
player/    PlayerPool: the ExoPlayer for the game on screen
config/    LAN config web server (phone setup page)
ui/        Compose screens: watch (video + game strip + score rails), settings (QR), theme
```

ESPN's fantasy API is unofficial and undocumented, so field names can change between seasons.
`EspnFantasyClient` is the one place to fix if they do.

## License

MIT. See [LICENSE](LICENSE).
