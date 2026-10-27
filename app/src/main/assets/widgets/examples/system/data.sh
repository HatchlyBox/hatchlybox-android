#!/bin/sh
# Processor use (two readings of /proc/stat half a second apart), memory and storage in use (%), the uptime,
# and the last 30 processor readings (kept in state.json).
read -r _ a1 b1 c1 d1 e1 f1 g1 h1 _ < /proc/stat
sleep 0.5
read -r _ a2 b2 c2 d2 e2 f2 g2 h2 _ < /proc/stat
busy=$(( (a2 + b2 + c2 + f2 + g2 + h2) - (a1 + b1 + c1 + f1 + g1 + h1) ))
all=$(( busy + (d2 + e2) - (d1 + e1) ))
cpu=$(( all > 0 ? busy * 100 / all : 0 ))
cores=$(grep -c '^processor' /proc/cpuinfo 2>/dev/null); [ "${cores:-0}" -gt 0 ] || cores=$(nproc 2>/dev/null || echo 1)
mem=$(awk '/MemTotal/ {t=$2} /MemAvailable/ {a=$2} END {if (t) printf "%d", (t-a)*100/t; else print 0}' /proc/meminfo)
memText=$(awk '/MemTotal/ {t=$2} /MemAvailable/ {a=$2} END {printf "%.1f of %.1f GB", (t-a)/1048576, t/1048576}' /proc/meminfo)
disk=$(df -P /root | awk 'NR==2 {gsub("%","",$5); print $5}')
diskText=$(df -P /root | awk 'NR==2 {printf "%.0f GB free", $4/1048576}')
up=$(awk '{d=int($1/86400); h=int($1%86400/3600); m=int($1%3600/60); if (d) printf "%dd %dh", d, h; else printf "%dh %dm", h, m}' /proc/uptime)
# The busiest programs right now, for the screen.
procs=$(ps -eo pcpu=,pmem=,comm= --sort=-pcpu 2>/dev/null | head -10 | jq -Rsc '[split("\n")[] | select(length > 0) | capture("^\\s*(?<cpu>[0-9.]+)\\s+(?<mem>[0-9.]+)\\s+(?<name>.+)$")]' 2>/dev/null)
[ -n "$procs" ] || procs='[]'
old=$(jq -c '.history // []' state.json 2>/dev/null || echo '[]')
jq -nc --argjson old "$old" --argjson cpu "${cpu:-0}" --argjson mem "${mem:-0}" --argjson disk "${disk:-0}" \
  --arg memText "$memText" --arg diskText "$diskText" --arg cpuText "$cores cores" --arg up "$up" --argjson procs "$procs" \
  '{cpu: $cpu, mem: $mem, disk: $disk, memText: $memText, diskText: $diskText, cpuText: $cpuText, up: $up, procs: $procs, history: (($old + [$cpu]) | .[-30:])}'
