#!/bin/sh
# Adds $1 glasses (0 just checks the day): a new day starts again from 0.
today=$(date +%F)
day=$(jq -r '.day // ""' state.json 2>/dev/null)
n=$(jq -r '.glasses // 0' state.json 2>/dev/null); [ "$day" = "$today" ] || n=0
n=$((n + ${1:-0})); [ $n -lt 0 ] && n=0
echo "{\"glasses\": $n, \"day\": \"$today\"}"
