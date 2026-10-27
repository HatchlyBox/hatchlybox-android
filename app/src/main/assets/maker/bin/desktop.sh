# desktop.sh: the desktop front end (Linux, macOS, Windows through WSL), sourced by hatchlybox. hatchlybox desktop starts
# bin/Desk.java on 127.0.0.1 (once: a running one is reused) and opens the page from desktop/ in the user's own
# browser, signed in with that server's token. The launchers install.sh makes (an app menu entry, Hatchlybox.app,
# a Start menu shortcut) run it.
#   hatchlybox desktop [--no-open]     start it if needed, open it, print its address
#   hatchlybox desktop --wait          the same, then stays until the server stops (WSL: keeps the distribution running)
#   hatchlybox desktop stop            stop it

DESK_STATE=$CACHE/desktop.json
DESK_PORT=${HATCHLYBOX_DESK_PORT:-8790}

# open_url <url>: the system's browser (HATCHLYBOX_OPENER says otherwise: tests, or a front end of its own).
open_url() {
  if [ -n "${HATCHLYBOX_OPENER:-}" ]; then "$HATCHLYBOX_OPENER" "$1"
  elif grep -qis microsoft /proc/version && command -v cmd.exe >/dev/null 2>&1; then cmd.exe /c start "" "$1" >/dev/null 2>&1
  elif [ "$(android_os)" = macos ]; then open "$1"
  elif command -v xdg-open >/dev/null 2>&1; then xdg-open "$1" >/dev/null 2>&1
  else return 1; fi
}

# opener: what opens a folder (Desk's Show folder): the same programs.
opener() {
  if [ -n "${HATCHLYBOX_OPENER:-}" ]; then echo "$HATCHLYBOX_OPENER"
  elif grep -qis microsoft /proc/version && command -v explorer.exe >/dev/null 2>&1; then echo "$DATA/bin/win-open"
  elif [ "$(android_os)" = macos ]; then echo open
  else echo xdg-open; fi
}

# desk_build: this engine's server, as a short fingerprint (an updated Hatchlybox has another one).
desk_build() { sha256sum "$DATA/bin/Desk.java" | cut -c1-12; }

# desk_url: the running server's sign-in address (nothing: none answers, or it's from before an update).
desk_url() {
  local port token v
  [ -f "$DESK_STATE" ] || return 1
  port=$(jq -r .port "$DESK_STATE" 2>/dev/null); token=$(jq -r .token "$DESK_STATE" 2>/dev/null)
  [[ $port =~ ^[0-9]+$ ]] && [ -n "$token" ] || return 1
  v=$(curl -fsS --max-time 2 "http://127.0.0.1:$port/.version" 2>/dev/null) || return 1
  [ "${v%% *}" = hatchlybox-desktop ] || return 1
  # An older server (Hatchlybox was updated since it started): stopped, so a new one starts.
  if [ "$(jq -r '.build // empty' "$DESK_STATE" 2>/dev/null)" != "$(desk_build)" ]; then
    local pid; pid=$(jq -r .pid "$DESK_STATE" 2>/dev/null)
    [[ $pid =~ ^[0-9]+$ ]] && kill "$pid" 2>/dev/null
    rm -f "$DESK_STATE"; sleep 1; return 1
  fi
  echo "http://127.0.0.1:$port/?t=$token"
}

desktop_cmd() {
  local url ui=$DATA/desktop
  if [ "${1:-}" = stop ]; then
    local pid; pid=$(jq -r .pid "$DESK_STATE" 2>/dev/null)
    [[ $pid =~ ^[0-9]+$ ]] && kill "$pid" 2>/dev/null && echo "stopped" || echo "it isn't running"
    rm -f "$DESK_STATE"; return 0
  fi
  [ -f "$ui/index.html" ] || { echo "the desktop front end isn't installed (desktop/ in the engine): run install.sh again" >&2; return 1; }
  java17 "$JAVA_HOME" || { echo "the desktop front end needs a JDK 17: $(android_java_hint)" >&2; return 1; }
  if ! url=$(desk_url); then
    rm -f "$DESK_STATE"; mkdir -p "$CACHE"
    HATCHLYBOX_OPENER=$(opener) HATCHLYBOX_DESK_BUILD=$(desk_build) setsid -f "$JAVA_HOME/bin/java" -Xmx128m "$DATA/bin/Desk.java" "$DESK_PORT" "$DATA/bin/hatchlybox" "$ui" "$CACHE" "$PORTS" "$DESK_STATE" "$BACKUPS" \
      < /dev/null >> "$CACHE/desktop.log" 2>&1
    local i; for i in $(seq 1 120); do url=$(desk_url) && break; sleep 0.5; done
    [ -n "$url" ] || { echo "the desktop front end didn't start: see $CACHE/desktop.log" >&2; return 1; }
  fi
  echo "${url%%\?*}"
  [ "${1:-}" = --no-open ] || open_url "$url" || echo "open this in your browser: $url"
  if [ "${1:-}" = --wait ]; then while desk_url >/dev/null; do sleep 10; done; fi
  return 0
}
