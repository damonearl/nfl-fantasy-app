#!/usr/bin/env bash
# Exports every live channel (and optionally movies) from an Xtream IPTV account to CSV.
#
#   scripts/xtream-csv.sh [-f ts|m3u8] [-m] [-o out.csv] '<get.php or player_api.php link>'
#
#   -f  stream format for the links (default: ts)
#   -m  include movies (VOD) as well as live channels
#   -o  output file (default: streams.csv)
#
# The link is the usual "http://host:port/get.php?username=…&password=…" playlist URL; quote it.
# Columns: type, category, name, stream_id, epg_id, url. The URLs contain your credentials.
set -euo pipefail

format=ts
movies=0
out=streams.csv
while getopts "f:mo:h" opt; do
  case $opt in
    f) format=$OPTARG ;;
    m) movies=1 ;;
    o) out=$OPTARG ;;
    *) sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
  esac
done
shift $((OPTIND - 1))
link=${1:-${XTREAM_URL:-}}
[ -n "$link" ] || { echo "usage: $0 [-f ts|m3u8] [-m] [-o out.csv] '<playlist link>'" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is required (brew install jq)" >&2; exit 1; }

param() { printf '%s\n' "${link#*\?}" | tr '&' '\n' | sed -n "s/^$1=//p" | head -1; }
user=$(param username)
pass=$(param password)
base=$(printf '%s\n' "${link%%\?*}" | sed -E 's#/(get|player_api)\.php$##')
[ -n "$user" ] && [ -n "$pass" ] || { echo "The link needs username= and password= parameters." >&2; exit 1; }

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# Many panels refuse curl's default user agent.
api() { curl -sSf -A "VLC/3.0.20 LibVLC/3.0.20" --max-time 300 "$base/player_api.php?username=$user&password=$pass${1:+&action=$1}" -o "$2"; }

# Log in; an https link that's refused falls back to plain http, which most panels serve.
if ! api "" "$tmp/account.json" 2>/dev/null; then
  case $base in
    https://*) base="http://${base#https://}"; api "" "$tmp/account.json" ;;
    *) echo "Couldn't reach $base" >&2; exit 1 ;;
  esac
fi
jq -e '.user_info.auth == 1' "$tmp/account.json" >/dev/null \
  || { echo "Login rejected; check the username and password." >&2; exit 1; }
jq -r '.user_info | "Account \(.status), expires \(.exp_date | tonumber? | todate // "never"), \(.max_connections) connection(s)"' "$tmp/account.json" >&2

# Writes CSV rows for one stream type: live streams play from /live/, movies from /movie/ with their own extension.
export_type() {
  local type=$1 categories=$2 streams=$3 path=$4
  echo "Fetching ${type}…" >&2
  api "$categories" "$tmp/cats.json"
  api "$streams" "$tmp/streams.json"
  jq -r --slurpfile cats "$tmp/cats.json" --arg type "$type" --arg base "$base" --arg path "$path" \
    --arg user "$user" --arg pass "$pass" --arg format "$format" '
    ($cats[0] | map({key: (.category_id | tostring), value: .category_name}) | from_entries) as $names
    | .[]
    | [$type, ($names[.category_id | tostring] // ""), .name, (.stream_id | tostring), (.epg_channel_id // ""),
       "\($base)/\($path)/\($user)/\($pass)/\(.stream_id).\(if $path == "movie" then (.container_extension // "mp4") else $format end)"]
    | @csv' "$tmp/streams.json"
}

{
  echo '"type","category","name","stream_id","epg_id","url"'
  export_type live get_live_categories get_live_streams live
  [ "$movies" = 1 ] && export_type movie get_vod_categories get_vod_streams movie
  true
} > "$out"

echo "Wrote $(($(wc -l < "$out") - 1)) streams to $out" >&2
