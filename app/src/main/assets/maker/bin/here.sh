# here.sh: installing a version for this computer's own system (Linux, Omarchy, macOS, Windows) on this computer, sourced by
# hatchlybox. The agent leaves export/install.sh (the contract is in port_prompt); hatchlybox here install runs a kept copy
# of export/ for this user only, with the folders it may use, and the script lists every path it made in the
# manifest. Uninstalling runs the version's uninstall.sh, if any, then removes those paths, but only inside the
# user's own app folders. A version is "installed" until its content is newer than what was installed ("update").
# hatchlybox here install|uninstall|open <slug> <system>.

# win_dir <LOCALAPPDATA|APPDATA>: that Windows folder as a WSL path (Hatchlybox on Windows runs in WSL), asked of
# Windows once and kept; HATCHLYBOX_WIN_<NAME> says otherwise (tests, or WSL without Windows interop).
win_dir() {
  local v="HATCHLYBOX_WIN_$1" f=$CACHE/windows-$1 d
  [ -n "${!v:-}" ] && { echo "${!v}"; return; }
  [ -s "$f" ] && { cat "$f"; return; }
  d=$(cmd.exe /c "echo %$1%" 2>/dev/null | tr -d '\r') && [ -n "$d" ] && [[ $d != *%* ]] && d=$(wslpath -u "$d" 2>/dev/null) && [ -d "$d" ] || return 1
  mkdir -p "$CACHE"; echo "$d" > "$f"; echo "$d"
}

# here_env: the folders an install script may use, as variables (one assignment per line).
here_env() {
  local share=${XDG_DATA_HOME:-$HOME/.local/share} config=${XDG_CONFIG_HOME:-$HOME/.config} l a
  # Windows (in WSL): the Windows user's own folders, Hatchlybox's part of them only.
  if [ "$HOST" = windows ] && l=$(win_dir LOCALAPPDATA) && a=$(win_dir APPDATA); then
    echo "HATCHLYBOX_WIN_APPS=$l/Programs/Hatchlybox"
    echo "HATCHLYBOX_WIN_MENU=$a/Microsoft/Windows/Start Menu/Programs/Hatchlybox"
  fi
  echo "HATCHLYBOX_BIN=$HOME/.local/bin"
  if [ "$(uname -s)" = Darwin ]; then
    echo "HATCHLYBOX_SHARE=$HOME/Library/Application Support"
    echo "HATCHLYBOX_APPS_DIR=$HOME/Applications"
  else
    echo "HATCHLYBOX_SHARE=$share"
  fi
  echo "HATCHLYBOX_CONFIG=$config"
}

# here_roots: the folders whose contents uninstalling may remove (never the folders themselves), one per line.
here_roots() {
  local v
  while IFS= read -r v; do echo "${v#*=}"; done < <(here_env)
}

# here_removable <path>: inside one of the roots (not a root, nothing like .. in it), and not this engine's own.
here_removable() {
  local p r
  case $1 in /*) ;; *) return 1 ;; esac
  case "/$1/" in */../*|*/./*) return 1 ;; esac
  p=$(realpath -m -- "$1")
  case $p in "$DATA"|"$DATA"/*|"$APPS"|"$APPS"/*|"$PORTS"|"$PORTS"/*|"$CACHE"|"$CACHE"/*) return 1 ;; esac
  while IFS= read -r r; do
    r=$(realpath -m -- "$r")
    [ "$p" != "$r" ] && [[ $p == "$r"/* ]] && return 0
  done < <(here_roots)
  return 1
}

# install_problems <export dir>: what's wrong with an install script for here.sh's contract, one per line (nothing: fine).
install_problems() {
  [ -f "$1/install.sh" ] || { echo "export/install.sh is missing: Hatchlybox installs the version by running it (the contract in your task)"; return; }
  ! grep -Eq '(^|[^[:alnum:]_-])(sudo|doas|pkexec)([^[:alnum:]_-]|$)' "$1/install.sh" "$1/uninstall.sh" 2>/dev/null \
    || echo "export/install.sh (or uninstall.sh) asks for administrator rights (sudo, doas, pkexec): Hatchlybox installs for the user only. Install into the folders it gives (\$HATCHLYBOX_BIN, \$HATCHLYBOX_SHARE, \$HATCHLYBOX_CONFIG) instead, and leave out anything that needs root"
  bash -n "$1/install.sh" 2>/dev/null || echo "export/install.sh has a syntax error: $(bash -n "$1/install.sh" 2>&1 | head -3)"
  [ ! -f "$1/uninstall.sh" ] || bash -n "$1/uninstall.sh" 2>/dev/null || echo "export/uninstall.sh has a syntax error"
}

# here_check <slug> <system>: this platform installs it and there is a finished export with an install script.
here_check() {
  local slug=$1 sys=$2 dir=$PORTS/$1/$2
  [[ " $INSTALLS " == *" $sys "* ]] || { echo "$(system_name "$sys") versions don't install on $HOST_NAME: export it instead" >&2; return 1; }
  [ -f "$dir/.port.json" ] || { echo "$slug has no $(system_name "$sys") version" >&2; return 1; }
  [ -f "$dir/export/install.sh" ] || { echo "its export has no install.sh: ask for a change (\"add the install script\"), then install" >&2; return 1; }
  # The contract: for this user only. A script that asks for root is refused rather than run.
  if grep -Eq '(^|[^[:alnum:]_-])(sudo|doas|pkexec)([^[:alnum:]_-]|$)' "$dir/export/install.sh" "$dir/export/uninstall.sh" 2>/dev/null; then
    echo "its install script asks for administrator rights (sudo): Hatchlybox installs for you only. Ask for a change without sudo." >&2; return 1
  fi
  bash -n "$dir/export/install.sh" 2>/dev/null || { echo "its install script has a syntax error: ask for a change" >&2; return 1; }
}

# here_remove <manifest> [keep]: removes the paths listed (deepest first), except those also in keep.
here_remove() {
  local p
  [ -f "$1" ] || return 0
  sort -ru "$1" | while IFS= read -r p; do
    [ -n "$p" ] || continue
    [ -n "${2:-}" ] && [ -f "$2" ] && grep -Fxq -- "$p" "$2" && continue
    if here_removable "$p"; then
      if [ -d "$p" ] && [ ! -L "$p" ]; then rm -rf -- "$p"; else rm -f -- "$p"; fi
    else
      echo "left alone (outside your app folders): $p" >> "$HERE_LOG"
    fi
  done
}

HERE_LOG=$CACHE/here.log

here_install() {
  local slug=$1 sys=$2 dir=$PORTS/$1/$2 keep=$DATA/installed/$1/$2 new rc=0 manifest
  here_check "$slug" "$sys" || return 1
  mkdir -p "$DATA/installed/$slug" "$CACHE"
  new=$(mktemp -d "$DATA/installed/$slug/.$sys.XXXX")
  cp -a "$dir/export/." "$new/"
  manifest=$(mktemp "$CACHE/.here.XXXX")
  echo "== $(date '+%F %T') install $slug ($sys)" >> "$HERE_LOG"
  local -a envs; mapfile -t envs < <(here_env)
  local v; for v in "${envs[@]}"; do mkdir -p "${v#*=}"; done
  ( cd "$new" && env "${envs[@]}" HATCHLYBOX_WIN_SHORTCUT="$DATA/bin/win-shortcut" HATCHLYBOX_MANIFEST="$manifest" timeout 10m bash ./install.sh < /dev/null ) >> "$HERE_LOG" 2>&1 || rc=$?
  if [ $rc -ne 0 ]; then
    # Undo what this run made, keeping whatever the version installed before still uses.
    here_remove "$manifest" "$keep.files"
    rm -rf "$new" "$manifest"
    echo "the install script failed (code $rc): see $HERE_LOG" >&2; return 1
  fi
  # An update: what the old version made and the new one doesn't, goes.
  here_remove "$keep.files" "$manifest"
  rm -rf "$keep"; mv "$new" "$keep"; mv "$manifest" "$keep.files"
  local content; content=$(version_time "$slug" "$sys")
  jq -c --argjson t "$(now)" --argjson c "${content:-0}" '.installed = {at: $t, content: $c}' "$dir/.port.json" > "$dir/.port.json.tmp" \
    && mv "$dir/.port.json.tmp" "$dir/.port.json"
  echo "installed $(creation_label "$slug") for $(system_name "$sys")"
}

here_uninstall() {
  local slug=$1 sys=$2 dir=$PORTS/$1/$2 keep=$DATA/installed/$1/$2
  [ -f "$keep.files" ] || [ -d "$keep" ] || { echo "it isn't installed here" >&2; return 1; }
  echo "== $(date '+%F %T') uninstall $slug ($sys)" >> "$HERE_LOG"
  local -a envs; mapfile -t envs < <(here_env)
  if [ -f "$keep/uninstall.sh" ]; then
    ( cd "$keep" && env "${envs[@]}" HATCHLYBOX_WIN_SHORTCUT="$DATA/bin/win-shortcut" timeout 5m bash ./uninstall.sh < /dev/null ) >> "$HERE_LOG" 2>&1 || echo "its uninstall script failed: removing its files anyway" >> "$HERE_LOG"
  fi
  here_remove "$keep.files"
  rm -rf "$keep" "$keep.files"
  rmdir "$DATA/installed/$slug" 2>/dev/null || true
  [ -f "$dir/.port.json" ] && jq -c 'del(.installed)' "$dir/.port.json" > "$dir/.port.json.tmp" && mv "$dir/.port.json.tmp" "$dir/.port.json"
  echo "uninstalled $(creation_label "$slug") ($(system_name "$sys"))"
}

# here_open: the installed version's run command (hatchlybox.json), started on its own.
here_open() {
  local slug=$1 sys=$2 keep=$DATA/installed/$1/$2 run
  [ -d "$keep" ] || { echo "install it first" >&2; return 1; }
  run=$(jq -r '.run // empty' "$keep/hatchlybox.json" 2>/dev/null)
  [ -n "$run" ] || { echo "it says nothing about how to open it: see RUN.md in its export" >&2; return 1; }
  local -a envs; mapfile -t envs < <(here_env)
  ( cd "$keep" && env "${envs[@]}" PATH="$HOME/.local/bin:$PATH" setsid -f bash -c "$run" < /dev/null >> "$HERE_LOG" 2>&1 )
  echo "opened $(creation_label "$slug")"
}

# remove_version <slug> <system>: that version (not Android's: hatchlybox delete) out of the library: uninstalled
# from this computer if it's installed, then moved to $DATA/trash/<slug>/<system>-<time>, where it can be brought
# back by moving it home again.
remove_version() {
  local slug=$1 sys=$2 dir=$PORTS/$1/$2 t
  [[ $slug =~ ^[a-z0-9][a-z0-9-]*$ ]] && [[ $sys =~ ^[a-z0-9][a-z0-9-]*$ ]] || { echo "usage: hatchlybox remove <slug> <system>" >&2; return 1; }
  [ "$sys" != android ] || { echo "the Android version is removed with: hatchlybox delete $slug" >&2; return 1; }
  [ -d "$dir" ] || { echo "$slug has no $(system_name "$sys") version" >&2; return 1; }
  if job_running && [ "$(jq -r .slug "$JOB")" = "$slug" ] && [ "$(jq -r '.arg // empty' "$JOB")" = "$sys" ]; then
    echo "it's being made right now: stop that first" >&2; return 1
  fi
  [ ! -d "$DATA/installed/$slug/$sys" ] && [ ! -f "$DATA/installed/$slug/$sys.files" ] || here_uninstall "$slug" "$sys" >/dev/null || return 1
  t=$(now); mkdir -p "$DATA/trash/$slug"
  mv "$dir" "$DATA/trash/$slug/$sys-$t" || return 1
  rmdir "$PORTS/$slug" 2>/dev/null || true
  echo "removed the $(system_name "$sys") version of $(creation_label "$slug") (kept in $DATA/trash/$slug/$sys-$t)"
}

here_cmd() {
  local what=${1:-} slug=${2:-} sys=${3:-}
  case $what in install|uninstall|open) ;; *) echo "usage: hatchlybox here install|uninstall|open <slug> <system>" >&2; return 1 ;; esac
  [[ $slug =~ ^[a-z0-9][a-z0-9-]*$ ]] && [[ $sys =~ ^[a-z0-9][a-z0-9-]*$ ]] && [ "$sys" != android ] \
    || { echo "usage: hatchlybox here $what <slug> <system> (Android versions: hatchlybox install <slug>)" >&2; return 1; }
  case $what in
    install) mutation_lock; here_install "$slug" "$sys" || return 1 ;;
    uninstall) mutation_lock; here_uninstall "$slug" "$sys" || return 1 ;;
    open) here_open "$slug" "$sys" || return 1; return 0 ;;
  esac
  list_apps
}
