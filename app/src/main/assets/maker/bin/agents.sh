# agents.sh: the AI helpers (Claude Code, Codex, OpenCode) for front ends, sourced by hatchlybox: whether each is
# installed and signed in, the models it offers, and signing in, which happens in a terminal window (each helper's
# own login asks there: a browser to confirm, a code or a key).
#   hatchlybox agents                    the installed ones' names (as before)
#   hatchlybox agents status             each one as JSON: installed, signedIn (null: it can't tell), model, models
#   hatchlybox agents login <agent>      a terminal window running its login; JSON {terminal} or a failure in words

AGENTS_ALL="claude codex opencode"
# Every check is bounded: a helper waiting on its network must not hang the Settings page.
AGENT_CHECK_SECS=${AGENT_CHECK_SECS:-15}

# agent_signed_in <agent>: yes, no or unknown, from the helper itself; its login file when it can't say.
agent_signed_in() {
  local a=$1 out
  case $a in
    claude)
      # Signed out, it still prints its JSON (and exits 1).
      out=$(timeout "$AGENT_CHECK_SECS" claude auth status 2>/dev/null)
      if jq -e 'has("loggedIn")' <<< "$out" >/dev/null 2>&1; then
        jq -r 'if .loggedIn then "yes" else "no" end' <<< "$out"
      elif [ -s "$HOME/.claude/.credentials.json" ] || [ -n "${ANTHROPIC_API_KEY:-}" ]; then echo yes
      else echo unknown; fi ;;
    codex)
      # "Logged in using ChatGPT" / "Not logged in": its exit code says which.
      if timeout "$AGENT_CHECK_SECS" codex login status >/dev/null 2>&1; then echo yes
      elif [ -s "${CODEX_HOME:-$HOME/.codex}/auth.json" ]; then echo yes
      else echo no; fi ;;
    opencode)
      # Its own server answers for it (--standalone: a private one, not the background service); older versions
      # kept their logins in auth.json.
      if out=$(timeout "$AGENT_CHECK_SECS" opencode auth list --standalone --format json 2>/dev/null) && jq -e 'type == "array"' <<< "$out" >/dev/null 2>&1; then
        jq -r 'if length > 0 then "yes" else "no" end' <<< "$out"
      elif out=$(timeout "$AGENT_CHECK_SECS" opencode auth list --standalone 2>/dev/null); then
        if grep -qi 'no authenticated' <<< "$out"; then echo no; else echo yes; fi
      elif [ -s "$HOME/.local/share/opencode/auth.json" ] && jq -e 'length > 0' "$HOME/.local/share/opencode/auth.json" >/dev/null 2>&1; then echo yes
      else echo unknown; fi ;;
  esac
}

# agent_models <agent>: the models it offers, one "<id>\t<name>" per line (nothing: it lists none).
agent_models() {
  case $1 in
    claude)
      # Claude Code's aliases for its latest models, as its --model help names them; the long-standing ones
      # when an older version names none.
      local m
      m=$(claude --help 2>/dev/null | awk '/^ *--model[ <]/ {on = 1} on && /^ *-/ && !/--model[ <]/ {exit} on' | grep -oE "'[a-z][a-z0-9.-]*'" | tr -d "'" | awk '!seen[$0]++')
      [ -n "$m" ] || m=$'opus\nsonnet\nhaiku'
      while read -r m; do printf '%s\t%s\n' "$m" "$m"; done <<< "$m" ;;
    codex)
      # Codex keeps the models its account offers in models_cache.json (fetched when it starts, signed in); the
      # hidden ones aren't for choosing.
      jq -r '(.models // [])[] | select((.visibility // "list") == "list") | [(.slug // .id), (.display_name // .slug // .id)] | @tsv' \
        "${CODEX_HOME:-$HOME/.codex}/models_cache.json" 2>/dev/null ;;
    opencode)
      { timeout "$AGENT_CHECK_SECS" opencode models --standalone 2>/dev/null || timeout "$AGENT_CHECK_SECS" opencode models 2>/dev/null; } \
        | grep -E '^[A-Za-z0-9._-]+/[^[:space:]]+$' | awk '!seen[$0]++ {print $0 "\t" $0}' ;;
  esac
}

# agent_login_cmd <agent>: its own sign-in command.
agent_login_cmd() { case $1 in claude) echo "claude auth login" ;; codex) echo "codex login" ;; opencode) echo "opencode auth login" ;; esac; }

agents_status() {
  local a p signed models
  for a in $AGENTS_ALL; do
    if p=$(type -P "$a"); then
      signed=$(agent_signed_in "$a")
      models=$(agent_models "$a" | jq -Rn '[inputs | split("\t") | select(length == 2 and .[0] != "") | {id: .[0], name: .[1]}]')
    else p="" signed=no models='[]'; fi
    jq -nc --arg id "$a" --arg n "$(agent_label "$a")" --arg p "$p" --arg s "$signed" --arg m "$(setting "${a}Model")" \
      --arg login "$(agent_login_cmd "$a")" --argjson models "$models" \
      '{id: $id, name: $n, installed: ($p != ""), signedIn: (if $s == "yes" then true elif $s == "no" then false else null end),
        model: $m, models: $models, login: $login}'
  done | jq -sc .
}

# term_run <script>: a terminal window running that script, without waiting for it. Omarchy (and other desktops
# following the xdg-terminal-exec spec): xdg-terminal-exec; else $TERMINAL, then the usual terminals; macOS:
# Terminal; Windows (WSL): Windows Terminal, else a console window, running this Linux. HATCHLYBOX_TERMINAL: tests.
term_run() {
  local s=$1 t
  if [ -n "${HATCHLYBOX_TERMINAL:-}" ]; then "$HATCHLYBOX_TERMINAL" bash "$s"; echo "$HATCHLYBOX_TERMINAL"; return
  elif grep -qis microsoft /proc/version && command -v cmd.exe >/dev/null 2>&1; then
    local wsl=(wsl.exe); [ -z "${WSL_DISTRO_NAME:-}" ] || wsl+=(-d "$WSL_DISTRO_NAME")
    if command -v wt.exe >/dev/null 2>&1; then setsid -f wt.exe "${wsl[@]}" -- bash "$s" >/dev/null 2>&1 < /dev/null; echo "Windows Terminal"
    else setsid -f cmd.exe /c start "" "${wsl[@]}" -- bash "$s" >/dev/null 2>&1 < /dev/null; echo "a console window"; fi
    return
  elif [ "$(android_os)" = macos ]; then
    osascript -e "tell application \"Terminal\" to do script \"bash '${s//\'/}'\"" -e 'tell application "Terminal" to activate' >/dev/null || return 1
    echo Terminal; return
  fi
  for t in xdg-terminal-exec "${TERMINAL:-}" x-terminal-emulator ghostty alacritty kitty foot wezterm gnome-terminal konsole xfce4-terminal xterm; do
    [ -n "$t" ] && command -v "${t%% *}" >/dev/null 2>&1 || continue
    # How each one takes a command: after -e, after --, or as it is.
    case ${t##*/} in
      xdg-terminal-exec*|kitty*|foot*) setsid -f $t bash "$s" ;;
      gnome-terminal*|kgx*|ptyxis*) setsid -f $t -- bash "$s" ;;
      wezterm*) setsid -f $t start -- bash "$s" ;;
      *) setsid -f $t -e bash "$s" ;;
    esac >/dev/null 2>&1 < /dev/null || continue
    echo "${t%% *}"; return
  done
  return 1
}

# agents_login <agent>: its login in a terminal window. The window says what to do in words and stays open at the
# end, so what the login said can be read; the front end checks agents status until it's signed in.
agents_login() {
  local a=${1:-} p s name t
  [[ " $AGENTS_ALL " == *" $a "* ]] || { echo "sign in to which AI helper? claude, codex or opencode" >&2; return 1; }
  name=$(agent_label "$a")
  p=$(type -P "$a") || { echo "$name isn't installed on this computer: install it first, then sign in" >&2; return 1; }
  mkdir -p "$CACHE"; s=$CACHE/login-$a.sh
  # The helper by its full path: the terminal's PATH may not find it where Hatchlybox did.
  { echo '#!/usr/bin/env bash'
    echo "echo \"Signing in to $name. Follow its steps below (it may open your browser). When it says you're signed in, close this window and go back to Hatchlybox.\""
    echo echo
    printf '%q %s\n' "$p" "$(agent_login_cmd "$a" | cut -d' ' -f2-)"
    echo echo
    echo 'read -rp "Press Enter to close this window. " _'
  } > "$s"
  t=$(term_run "$s") || { echo "couldn't open a terminal window: open one yourself and run $(agent_login_cmd "$a")" >&2; return 1; }
  jq -nc --arg a "$a" --arg t "$t" '{agent: $a, terminal: $t}'
}

agents_cmd() {
  case ${1:-} in
    "") for a in $AGENTS_ALL; do command -v "$a" >/dev/null 2>&1 && printf '%s ' "$a"; done; echo ;;
    status) agents_status ;;
    login) agents_login "${2:-}" ;;
    *) echo "usage: hatchlybox agents [status | login <claude|codex|opencode>]" >&2; return 1 ;;
  esac
}
