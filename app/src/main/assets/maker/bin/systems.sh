# One creation, made for any system. A creation lives where its versions are: its Android version in
# $APPS/<slug> (the Gradle project and .hatchlybox.json), every other version in $PORTS/<slug>/<system>
# (.port.json, the agent's work and export/: what to install there). Any version can be the first; the
# others are made from the newest one when they're needed (hatchlybox make), and a version older than another
# shows as such in creations.json, to bring up to date.

# system_name <system>: in words.
system_name() {
  case $1 in
    android) echo Android ;; web) echo Web ;; linux) echo Linux ;; omarchy) echo Omarchy ;; windows) echo Windows ;;
    macos) echo macOS ;; other) echo "another system" ;; *) tr '-' ' ' <<< "$1" | sed 's/^./\U&/' ;;
  esac
}

# system_brief <system> [description]: what a version for that system is, for the agent.
system_brief() {
  case $1 in
    web) echo "a web app: one folder that runs with no build step and no server, index.html at its top with its CSS, JavaScript and assets beside it (nothing loaded from the internet), mobile-first, saving its data in localStorage, with an icon.png (512 px) beside index.html. It runs inside Hatchlybox on phones and in any browser, and becomes an Android app with one tap: there, window.HatchlyboxNative offers notify(title, text), vibrate(ms) and share(text) (check it exists, and fall back to the web's own way)" ;;
    linux) echo "a native Linux desktop app (for example Python with GTK 4 and libadwaita), with a .desktop launcher entry and an icon" ;;
    omarchy) echo "an Omarchy plugin (Omarchy is Arch Linux with Hyprland): a small app or Waybar module that fits Omarchy's conventions, uses the current theme's colours from ~/.config/omarchy/current/theme, opens from a Hyprland keybinding or the app launcher, with an install.sh" ;;
    windows) echo "a Windows desktop app (for example C# with WinUI 3 or WPF, or Tauri), with a solution or project file that builds on Windows; it can't be built here, so make it correct by careful reading" ;;
    macos) echo "a macOS app (for example SwiftUI), with an Xcode project that builds on a Mac; it can't be built here, so make it correct by careful reading" ;;
    other) echo "an app for the system the request names, or the one that fits it best" ;;
    *) echo "${2:-an app for $(system_name "$1")}" ;;
  esac
}

# version_time <slug> <system>: when that version's content last really changed (.content): made new or
# changed on request, now; made from another version or brought up to date with it, that version's time.
# So a copy is never newer than what it was made from, and "older" means another version has more.
version_time() {
  if [ "$2" = android ]; then
    jq -r '(.content // .history[-1].t // .created // empty)' "$APPS/$1/.hatchlybox.json" 2>/dev/null
  else
    jq -r '(.content // .changed // .created // empty)' "$PORTS/$1/$2/.port.json" 2>/dev/null
  fi
}

# versions <slug>: the systems it's made for, one per line.
versions() {
  [ -f "$APPS/$1/.hatchlybox.json" ] && echo android
  local d; for d in "$PORTS/$1"/*/; do [ -f "$d/.port.json" ] && basename "$d"; done
  true
}

# newest_version <slug> [except system]: the finished version that changed last, to make the others from.
newest_version() {
  local s best='' bt=-1 t
  while read -r s; do
    [ -n "$s" ] && [ "$s" != "${2:-}" ] || continue
    # One whose agent never finished (its first job failed or runs now) has nothing to make others from.
    [ "$s" = android ] || [ -n "$(jq -r '.changed // empty' "$PORTS/$1/$s/.port.json" 2>/dev/null)" ] || continue
    t=$(version_time "$1" "$s"); t=${t:-0}
    [ "$t" -gt "$bt" ] && { best=$s; bt=$t; }
  done < <(versions "$1")
  echo "$best"
}

# version_dir <slug> <system>: where that version is.
version_dir() { if [ "$2" = android ]; then echo "$APPS/$1"; else echo "$PORTS/$1/$2"; fi; }

# port_prompt <slug> <system> <description> [request]: the agent's task for a version for another system:
# a new app (no other version), the same app from its newest version, a change, or bringing it up to date.
port_prompt() {
  local slug=$1 sys=$2 desc=${3:-} request=${4:-} dir src from label brief
  dir=$PORTS/$slug/$sys; brief=$(system_brief "$sys" "$desc")
  label=$(jq -r '.label // empty' "$dir/.port.json" 2>/dev/null)
  local finish="Finish with an export/ folder holding everything needed to run it on that system and nothing else (the built program or package, a zip, an installer, or what fits), plus RUN.md with the exact steps to install and run it there. If it can't be built on this phone, put the complete source with a one-command build script in export/ and say so in RUN.md. Install the tools you need here (apk add, pip, npm, cargo, dotnet and so on) and build or package it where you can."
  [ "$sys" = web ] && finish="Put the finished app in export/ with index.html at its top (export/ is what runs), and keep it working offline."
  case $sys in linux|omarchy|macos|windows) finish="$finish $(install_contract "$sys")" ;; esac
  if [ -n "$request" ] && [ -f "$dir/.port.json" ]; then
    printf 'Change this %s version of "%s" (it is in this folder): %s\n\nKeep what works. %s' "$(system_name "$sys")" "$label" "$request" "$finish"
    return
  fi
  src=$(newest_version "$slug" "$sys")
  if [ -z "$src" ]; then
    printf 'Make a new app from scratch: %s\n\nMake it as %s. There is no other version: design it yourself, carefully, like a professional studio (a clear concept, every feature working, a finished look). Build it in this folder. %s' \
      "$(jq -r '.idea // empty' "$dir/.port.json")" "$brief" "$finish"
    return
  fi
  from=$(version_dir "$slug" "$src")
  if [ -f "$dir/.port.json" ] && [ -n "$(jq -r '.changed // empty' "$dir/.port.json")" ]; then
    printf 'Bring this %s version of "%s" (it is in this folder) up to date with its newer %s version in %s: read both, and give this one every feature, screen, saved data and look the newer one has, in the conventions of %s. Don'"'"'t change anything in %s. %s' \
      "$(system_name "$sys")" "$label" "$(system_name "$src")" "$from" "$(system_name "$sys")" "$from" "$finish"
  else
    printf 'Make the app "%s" for another system: %s. Its %s version is in %s: read it to learn every feature, screen, saved data and the look. Build a complete, working version in this folder with the same features and the same look and feel, adapted to the platform'"'"'s conventions. Don'"'"'t change anything in %s. %s' \
      "$label" "$brief" "$(system_name "$src")" "$from" "$from" "$finish"
  fi
}

# install_contract <system>: what export/ needs so Hatchlybox installs it on a computer with one tap (here.sh).
install_contract() {
  local where="programs into \$HATCHLYBOX_BIN (~/.local/bin), its data, icon and launcher under \$HATCHLYBOX_SHARE (~/.local/share: the .desktop file in applications/, the icon in icons/hicolor/), settings under \$HATCHLYBOX_CONFIG"
  [ "$1" = macos ] && where="the .app into \$HATCHLYBOX_APPS_DIR (~/Applications), command-line tools into \$HATCHLYBOX_BIN, data under \$HATCHLYBOX_SHARE (~/Library/Application Support)"
  # Windows: Hatchlybox runs in WSL; install.sh still runs in bash there, and writes to the Windows side through these paths.
  [ "$1" = windows ] && where="the program (a self-contained .exe, with what it needs beside it) into a folder of its own in \$HATCHLYBOX_WIN_APPS (%LOCALAPPDATA%\\Programs\\Hatchlybox, as a WSL path under /mnt/c), a Start menu shortcut made with \"\$HATCHLYBOX_WIN_SHORTCUT\" <name> <path of the .exe> (it lands in \$HATCHLYBOX_WIN_MENU), its data where the program itself keeps it (%APPDATA%). Build the .exe here, in Linux (Go, Rust or .NET cross-compiling, or a web app with a small launcher): you can't run Windows programs from the sandbox"
  printf '%s' "Hatchlybox installs it on the user's computer with one tap, so export/ must also hold install.sh: Hatchlybox runs \`bash install.sh\` inside a copy of export/, as the user (never root: no sudo, no questions, no network unless it truly needs it), with these folders set. It installs for this user only: $where. It appends every file or folder it creates, one absolute path per line, to the file named by \$HATCHLYBOX_MANIFEST: Hatchlybox removes exactly those to uninstall. Running it again updates in place. If it changes a file that was already there (a line in a config), add uninstall.sh that undoes just that. Add hatchlybox.json with {\"run\": \"the command that opens it once installed\"}. Test install.sh here with those variables pointed at a scratch folder when the system allows."
}

# android_from_prompt <slug> <source system>: the agent's task for an Android version made from another one.
android_from_prompt() {
  local from; from=$(version_dir "$1" "$2")
  cat <<EOF
Make the Android version of this app in the current directory. Its $(system_name "$2") version is in $from: read it
to learn every feature, screen, saved data and the look, and build the same app for Android, adapted to Android's
conventions. Don't change anything in $from.

The project is already scaffolded (Kotlin, Jetpack Compose, Material 3). Read AGENTS.md first and follow it, then
read the skills in .claude/skills that fit this app (always design and see-it-run). Every feature working end to
end, saved data that survives a restart, a finished look and a fitting launcher icon. No placeholders.
Give the app the same name in app/src/main/res/values/strings.xml (app_name).
Finish the implementation and return; Hatchlybox builds, installs and checks startup after you exit.
EOF
}

# new_slug <idea>: a free name for a new creation, in $APPS and $PORTS alike.
new_slug() {
  local base slug i=2
  base=$(slugify "$1"); base=${base:-app}; slug=$base
  while [ -e "$APPS/$slug" ] || [ -e "$PORTS/$slug" ]; do slug="$base-$i"; i=$((i + 1)); done
  echo "$slug"
}

# create_cmd <system> <agent> <idea...>: a new creation, first made for that system ("android": hatchlybox new).
create_cmd() {
  local sys=${1:-} agent=${2:-}; shift 2 || true; local idea="$*" slug label
  { [[ $sys =~ ^[a-z][a-z0-9-]*$ ]] || [[ $sys =~ ^custom:.+ ]]; } && [ -n "$idea" ] || { echo "usage: hatchlybox create <system|custom:words> <agent> <idea>" >&2; exit 1; }
  # auto: the format that fits (route.sh): web unless it needs the phone, is a real game, or names a system.
  if [ "$sys" = auto ]; then sys=$(route_idea "$idea"); sys=${sys%% *}; fi
  # Another system, in the person's own words (custom:<words>): its own version, like make's.
  local desc=''
  if [[ $sys == custom:* ]]; then desc=${sys#custom:}; sys=$(slugify "$desc"); sys=${sys:-custom}; fi
  # game: a real game, made with Godot in an Android version, exported from there to every other system.
  local game=0
  if [ "$sys" = game ]; then sys=android game=1; idea="A real game, made with Godot (read the godot skill): $idea"; fi
  can_start "$agent"
  # A game is made in an Android project, but a computer without Android building exports it for itself.
  [ "$sys" != android ] || [ "$game" = 1 ] || need_android
  slug=$(new_slug "$idea")
  if [ "$sys" = android ]; then
    scaffold "$slug" && list_apps
    start app "$slug" "$agent" "$(new_prompt "$idea")"; return
  fi
  label=$(sed 's/-/ /g; s/^./\U&/' <<< "$slug")
  mkdir -p "$PORTS/$slug/$sys"
  jq -nc --arg s "$sys" --arg a "$agent" --arg i "$idea" --arg l "$label" --argjson t "$(now)" \
    '{target: $s, agent: $a, idea: $i, label: $l, created: $t}' > "$PORTS/$slug/$sys/.port.json"
  [ -z "$desc" ] || json_edit "$PORTS/$slug/$sys/.port.json" --arg d "$desc" '.description = $d' 
  list_apps
  start port "$slug" "$agent" "$sys"
}

# make_cmd <slug> <system> <agent> [request...]: the creation for that system. Not made yet: made from its
# newest version (Android: a new project, built and installed). Made already: with a request, that change;
# without one, brought up to date with the newest version. "custom:<words>" names a system of its own.
make_cmd() {
  local slug=${1:-} sys=${2:-} agent=${3:-}; shift 3 || true; local request="$*" desc='' label src
  [[ $slug =~ ^[a-z0-9][a-z0-9-]*$ ]] && [ -n "$sys" ] || { echo "usage: hatchlybox make <slug> <system> <agent> [request]" >&2; exit 1; }
  [ -n "$(versions "$slug")" ] || { echo "no creation $slug" >&2; exit 1; }
  if [[ $sys == custom:* ]]; then desc=${sys#custom:}; sys=$(slugify "$desc"); sys=${sys:-custom}; fi
  [[ $sys =~ ^[a-z][a-z0-9-]*$ ]] || { echo "bad system $sys" >&2; exit 1; }
  label=$(creation_label "$slug")
  # An Android version needs Android building here (a game's other systems export without it).
  if [ "$sys" = android ] && ! is_godot "$APPS/$slug"; then need_android; fi
  if [ "$sys" = android ]; then
    src=$(newest_version "$slug" android)
    # The web version wrapped: brought up to date by wrapping again (no agent); a change goes to the web version.
    if wrapped "$slug"; then
      if [ -z "$request" ]; then exec "$0" wrap "$slug"; else exec "$0" make "$slug" web "$agent" "$request"; fi
    fi
    # Made already: a change, as hatchlybox change makes it (before any lock: it takes its own).
    if [ -f "$APPS/$slug/.hatchlybox.json" ]; then
      if [ -z "$request" ]; then
        request="Bring this Android app up to date with its newer $(system_name "$src") version in $(version_dir "$slug" "$src"): give it every feature, screen, saved data and look the newer one has. Don't change anything there."
        # Up to date with it: the same content, so the same time (the worker inherits this).
        export HATCHLYBOX_CONTENT; HATCHLYBOX_CONTENT=$(version_time "$slug" "$src")
      fi
      exec "$0" change "$slug" "$agent" "$request"
    fi
    can_start "$agent"
    scaffold "$slug" || exit 1
    meta "$slug" '.label=$l | .from=$f' --arg l "$label" --arg f "$src"
    export HATCHLYBOX_CONTENT; HATCHLYBOX_CONTENT=$(version_time "$slug" "$src")
    list_apps
    start app "$slug" "$agent" "$(android_from_prompt "$slug" "$src")"; return
  fi
  # A Godot game exports to these with Godot itself (gexport.sh): no agent, the same game everywhere.
  if [ -z "$request" ] && [[ " $GODOT_EXPORTS " == *" $sys "* ]] && is_godot "$APPS/$slug"; then
    can_start ""; start gexport "$slug" "" "$sys"; return
  fi
  can_start "$agent"
  local dir=$PORTS/$slug/$sys
  if [ ! -f "$dir/.port.json" ]; then
    mkdir -p "$dir"
    jq -nc --arg s "$sys" --arg a "$agent" --arg l "$label" --arg d "$desc" --argjson t "$(now)" \
      '{target: $s, agent: $a, label: $l, created: $t} + (if $d != "" then {description: $d} else {} end)' > "$dir/.port.json"
  fi
  # A job carried on after a stop keeps the request it had.
  if [ -n "$request" ] || [ "${HATCHLYBOX_RESUMES:-0}" = 0 ]; then
    jq -c --arg r "$request" '.request = (if $r != "" then $r else null end)' "$dir/.port.json" > "$dir/.port.json.tmp" && mv "$dir/.port.json.tmp" "$dir/.port.json"
  fi
  list_apps
  start port "$slug" "$agent" "$sys"
}

# creation_label <slug>: its name, from whichever version has one.
creation_label() {
  local l; l=$(jq -r '.label // empty' "$APPS/$1/.hatchlybox.json" 2>/dev/null)
  [ -n "$l" ] || l=$(cat "$PORTS/$1"/*/.port.json 2>/dev/null | jq -rs 'map(.label // empty) | first // empty')
  echo "${l:-$(sed 's/-/ /g; s/^./\U&/' <<< "$1")}"
}

# web_check <export dir>: what stops a web app from working, one problem per line (nothing: fine). index.html
# there, its icon, every local file a page loads there, and every script parsing (node --check; a module as a module).
web_check() {
  local e=$1 js t err
  [ -f "$e/index.html" ] || { echo "export/index.html is missing (export/ is what runs)"; return; }
  # Its own icon, for the library and the home screen (and the Android app it can become).
  [ -n "$(wrap_icon "$e")" ] || echo "export/icon.png is missing: draw the app's own icon, 512×512 px, beside index.html"
  python3 -I - "$e" <<'PY'
import html.parser, os, sys, urllib.parse
root = os.path.realpath(sys.argv[1])
class P(html.parser.HTMLParser):
    def __init__(s): super().__init__(); s.refs = []
    def handle_starttag(s, tag, attrs):
        for k, v in attrs:
            if v and (k == "src" or (k == "href" and tag in ("link",)) or (k == "href" and tag == "a" and not v.startswith("#"))):
                s.refs.append(v)
for d, _, fs in os.walk(root):
    for f in fs:
        if not f.endswith((".html", ".htm")): continue
        page = os.path.join(d, f); p = P()
        try: p.feed(open(page, encoding="utf-8", errors="replace").read())
        except Exception: continue
        for r in p.refs:
            u = urllib.parse.urlsplit(r)
            if u.scheme or r.startswith(("//", "#", "data:", "mailto:", "javascript:", "tel:")): continue
            path = urllib.parse.unquote(u.path)
            if not path: continue
            target = os.path.realpath(os.path.join(root, path.lstrip("/")) if path.startswith("/") else os.path.join(d, path))
            if os.path.isdir(target): target = os.path.join(target, "index.html")
            if not os.path.exists(target):
                print(f"{os.path.relpath(page, root)} loads {r}, which isn't in export/")
PY
  command -v node >/dev/null 2>&1 || return 0
  t=$(mktemp -d)
  while IFS= read -r js; do
    if grep -qE '^[[:space:]]*(import|export)[[:space:]{*]' "$e/$js"; then cp "$e/$js" "$t/check.mjs"; err=$(node --check "$t/check.mjs" 2>&1)
    else err=$(node --check "$e/$js" 2>&1); fi
    [ $? -eq 0 ] || echo "$js doesn't parse: $(grep -m1 -E 'Error' <<< "$err")"
  done < <(cd "$e" && find . -type f \( -name '*.js' -o -name '*.mjs' \) -not -path '*/node_modules/*' -size -2048k | sed 's|^\./||' | sort)
  rm -rf "$t"
}

# port_work <slug> <agent> <system>: the worker's job for a version for another system: the agent makes,
# changes or updates it and builds it here where it can; export/ is what installs there.
port_work() {
  local slug=$1 agent=$2 sys=$3 dir=$PORTS/$1/$3 rc prompt request desc
  request=$(jq -r '.request // empty' "$dir/.port.json"); desc=$(jq -r '.description // empty' "$dir/.port.json")
  prompt=$(port_prompt "$slug" "$sys" "$desc" "$request")
  # Its content's time once done: a change is new; a copy of another version has that version's.
  local src content; src=$(newest_version "$slug" "$sys"); content=$(now)
  [ -n "$request" ] || [ -z "$src" ] || content=$(version_time "$slug" "$src")
  job agent "$agent is making it for $(system_name "$sys")"
  PORT_SYSTEM=$sys run_agent "$agent" "$dir" "$prompt"; rc=$?
  if [ "$rc" -ne 0 ]; then job failed "$(agent_failure "$agent" "$rc")"; return 1; fi
  # A web version is checked as a build is: problems go back to the agent (up to the fix rounds).
  if [ "$sys" = web ]; then
    local problems round=0
    while problems=$(web_check "$dir/export") && [ -n "$problems" ]; do
      round=$((round + 1))
      printf '== web check:\n%s\n' "$problems" >> "$LOG"
      [ $round -le "$FIX_ROUNDS" ] || { job failed "the web app still has problems after $FIX_ROUNDS fixes: see the log"; return 1; }
      job fixing "$(agent_label "${AGENT_USED:-$agent}") fixing the web app ($round/$FIX_ROUNDS)"
      PORT_SYSTEM=$sys run_agent "${AGENT_USED:-$agent}" "$dir" "The web app in export/ doesn't work yet. Fix these problems (keep everything else), then return:
$problems"; rc=$?
      [ "$rc" -eq 0 ] || { job failed "$(agent_failure "$agent" "$rc")"; return 1; }
    done
  fi
  # A computer's version is checked for one-tap installing (here.sh's contract): problems go back to the agent too.
  if [ -d "$dir/export" ]; then case $sys in linux|omarchy|macos|windows)
    local problems round=0
    while problems=$(install_problems "$dir/export") && [ -n "$problems" ]; do
      round=$((round + 1))
      printf '== install check:\n%s\n' "$problems" >> "$LOG"
      [ $round -le "$FIX_ROUNDS" ] || { job failed "its install script still has problems after $FIX_ROUNDS fixes: see the log"; return 1; }
      job fixing "$(agent_label "${AGENT_USED:-$agent}") fixing the install script ($round/$FIX_ROUNDS)"
      PORT_SYSTEM=$sys run_agent "${AGENT_USED:-$agent}" "$dir" "Hatchlybox can't install this version yet. Fix these problems (keep everything else), then return:
$problems"; rc=$?
      [ "$rc" -eq 0 ] || { job failed "$(agent_failure "$agent" "$rc")"; return 1; }
    done ;;
  esac; fi
  jq -c --argjson t "$(now)" --argjson c "${content:-$(now)}" --arg a "${AGENT_USED:-$agent}" '.changed = $t | .content = $c | .agent = $a | del(.request)' "$dir/.port.json" > "$dir/.port.json.tmp" && mv "$dir/.port.json.tmp" "$dir/.port.json"
  # Its phone app is this web version wrapped: wrap the new one too, in this job.
  if [ "$sys" = web ] && wrapped "$slug" && [ -f "$dir/export/index.html" ]; then wrap_build "$slug"; return; fi
  if [ -d "$dir/export" ]; then job done "made for $(system_name "$sys"): ready to $([ "$sys" = web ] && echo open || echo export)"
  else job done "made for $(system_name "$sys"): the source, to build there (see RUN.md)"; fi
}
