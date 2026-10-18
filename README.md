# Fantasy Multiview

A Google TV / Android TV app that watches your ESPN Fantasy Football matchup: up to four games
your starters are in, with live fantasy points for your lineup on the left and your opponent's on
the right.

- **Grid**: live games ranked by how many starters (yours + theirs) are in them, then the next
  kickoffs. Tiles keep their position as games end and new ones start.
- **Remote**: D-pad moves between tiles; audio follows focus. **OK** expands a tile, **Back**
  returns to the grid. **Up** reaches Settings.
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
| **Antenna (HDHomeRun)** | Reads the tuner's lineup. HDHomeRun guide data places a game on a channel when a program names both teams. Otherwise the game's network (CBS/FOX/NBC/ABC) is used, but only when that network has one game in the window, since local affiliates carry a single regional game. You can pin network → channel (e.g. `CBS = 2.1`). Antenna tiles are capped at the tuner count. |
| **M3U playlist** | Channel titles are matched against team nicknames. `#EXTVLCOPT` referrer/user-agent are honored. |

Antenna notes: US broadcasts (ATSC 1.0) are MPEG-2 video, which many Google TV dongles can't
decode in hardware (NVIDIA Shield and most TVs can). An HDHomeRun EXTEND can transcode to
H.264. Set a profile like `mobile` or `heavy` on the setup page. ATSC 3.0 channels using AC-4
audio generally won't play.

## Code map

```
data/      ESPN fantasy v3 client, ESPN scoreboard client, settings, demo league
streams/   StreamProvider interface + manual, HDHomeRun, M3U providers
player/    PlayerPool: one ExoPlayer per tile, focus-follows audio, per-tile resolution caps
config/    LAN config web server (phone setup page)
ui/        Compose screens: watch (rails + grid), setup (QR), theme
```

ESPN's fantasy API is unofficial and undocumented, so field names can change between seasons.
`EspnFantasyClient` is the one place to fix if they do.
