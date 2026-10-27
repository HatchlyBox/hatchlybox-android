#!/bin/sh
# A fetch like fastfetch's: the distribution's logo and the system's facts, one per line.
. /etc/os-release 2>/dev/null
os="${PRETTY_NAME:-Linux}"
case "$ID" in
  alpine) logo='   /\ /\
  // \  \
 //   \  \
///    \  \
//      \  \
         \' ;;
  arch*) logo='      /\
     /  \
    /\   \
   /  __  \
  /  (  )  \
 / __|  |__\
/.`        `.\' ;;
  debian|ubuntu) logo='  _____
 /  __ \
|  /    |
|  \___-
-_
  --_' ;;
  *) logo='    .--.
   |o_o |
   |:_/ |
  //   \ \
 (|     | )
/`\_   _/`\
\___)=(___/' ;;
esac
user=$(id -un 2>/dev/null || echo root)
kernel=$(uname -r | cut -d- -f1)
up=$(awk '{d=int($1/86400); h=int($1%86400/3600); m=int($1%3600/60); if (d) printf "%dd %dh %dm", d, h, m; else if (h) printf "%dh %dm", h, m; else printf "%dm", m}' /proc/uptime)
if command -v apk >/dev/null; then pkgs="$(apk info 2>/dev/null | wc -l) (apk)"
elif command -v pacman >/dev/null; then pkgs="$(pacman -Q 2>/dev/null | wc -l) (pacman)"
elif command -v dpkg-query >/dev/null; then pkgs="$(dpkg-query -f '.\n' -W 2>/dev/null | wc -l) (dpkg)"
else pkgs="?"; fi
shell=$(basename "${SHELL:-sh}")
cores=$(grep -c '^processor' /proc/cpuinfo 2>/dev/null)
cpu=$(awk -F': *' '/^Hardware|^model name/ {print $2; exit}' /proc/cpuinfo 2>/dev/null)
cpu="${cpu:-$(uname -m)} (${cores:-?})"
mem=$(awk '/MemTotal/ {t=$2} /MemAvailable/ {a=$2} END {printf "%.1f / %.1f GiB", (t-a)/1048576, t/1048576}' /proc/meminfo)
disk=$(df -Ph /root | awk 'NR==2 {print $3 " / " $2 " (" $5 ")"}')
jq -nc --arg logo "$logo" --arg title "$user@hatchlybox" --arg os "$os" --arg kernel "$kernel" --arg up "$up" \
  --arg pkgs "$pkgs" --arg shell "$shell" --arg cpu "$cpu" --arg mem "$mem" --arg disk "$disk" \
  '{logo: $logo, title: $title, info: [
    {k: "OS", v: $os}, {k: "Kernel", v: $kernel}, {k: "Uptime", v: $up}, {k: "Packages", v: $pkgs},
    {k: "Shell", v: $shell}, {k: "CPU", v: $cpu}, {k: "Memory", v: $mem}, {k: "Disk", v: $disk}], error: ""}'
