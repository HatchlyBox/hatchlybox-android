#!/bin/sh
# Wifoz's game: ./game.sh [status|sniff|feed|pet|accuse <id>|new]. Saves game.json, prints the widget's state.
cd "$(dirname "$0")" || exit 1
g=$(cat game.json 2>/dev/null); [ -n "$g" ] || g=null
out=$(jq -n --argjson g "$g" --arg act "${1:-status}" --arg arg "$2" --argjson now "$(date +%s)" -f game.jq) || { echo '{"error":"the game file is broken: ./game.sh new starts again"}'; exit 1; }
printf '%s' "$out" | jq '.game' > game.json.tmp && mv game.json.tmp game.json
printf '%s' "$out" | jq -c '.show'
