# wrap.sh: a creation's web version as an Android app with no agent (hatchlybox wrap), and the step from that to a full
# native app (hatchlybox native), sourced by hatchlybox. The wrapper is a fixed project (template/webshell over the usual
# template): the web version's export/ goes into its assets, and a WebView serves it offline from a private https
# address, with window.HatchlyboxNative for notifications, vibration and sharing. Nothing in it is written by an agent,
# so it builds almost every time. Its Android version is marked .wrapped: the web version stays the one to change,
# and a change to it is wrapped again in the same job (port_work). hatchlybox native makes it a real Kotlin app from the
# web version, in the same project with the same package, so it updates the wrapped app on the phone in place.

wrapped() { jq -e '.wrapped != null' "$APPS/$1/.hatchlybox.json" >/dev/null 2>&1; }

# xml_text <text>: the text for an Android string resource.
xml_text() { sed -e 's/&/\&amp;/g; s/</\&lt;/g; s/>/\&gt;/g' -e "s/'/\\\\'/g" -e 's/"/\\"/g' <<< "$1"; }

# wrap_icon <export>: the web app's own icon, a PNG (icon.png and the usual names, else the largest its manifest names).
wrap_icon() {
  local web=$1 f m
  for f in icon.png icons/icon-512.png icons/icon-192.png apple-touch-icon.png logo.png favicon.png; do
    [ -f "$web/$f" ] && { echo "$web/$f"; return; }
  done
  for m in manifest.json manifest.webmanifest site.webmanifest; do
    [ -f "$web/$m" ] || continue
    f=$(jq -r '[.icons[]? | select((.src // "") | test("\\.png$"; "i")) | {src, s: ((.sizes // "0x0") | split("x")[0] | tonumber? // 0)}] | max_by(.s) | .src // empty' "$web/$m" 2>/dev/null)
    f=${f#/}; f=${f#./}
    [ -n "$f" ] && [ -f "$web/$f" ] && [[ $f != *..* ]] && { echo "$web/$f"; return; }
  done
}

# wrap_project <slug>: makes the wrapper, or refreshes its web files, from the web version (no build).
wrap_project() {
  local slug=$1 web=$PORTS/$1/web/export dir=$APPS/$1 pkg label src icon
  [ -f "$web/index.html" ] || { echo "it has no finished web version (export/index.html) to put on the phone" >&2; return 1; }
  if [ -f "$dir/.hatchlybox.json" ] && ! wrapped "$slug"; then echo "it already has a full Android version" >&2; return 1; fi
  label=$(creation_label "$slug")
  if [ ! -f "$dir/.hatchlybox.json" ]; then scaffold "$slug" || return 1; fi
  pkg=$(jq -r .pkg "$dir/.hatchlybox.json")
  src=$dir/app/src/main/java/${pkg//.//}
  sed "s/__PKG__/$pkg/g" "$DATA/template/webshell/build.gradle.kts" > "$dir/app/build.gradle.kts"
  cp "$DATA/template/webshell/AndroidManifest.xml" "$dir/app/src/main/AndroidManifest.xml"
  mkdir -p "$src" && rm -f "$src"/*.kt && sed "s/__PKG__/$pkg/g" "$DATA/template/webshell/MainActivity.kt" > "$src/MainActivity.kt"
  printf '<resources>\n    <string name="app_name">%s</string>\n</resources>\n' "$(xml_text "$label")" > "$dir/app/src/main/res/values/strings.xml"
  rm -rf "$dir/app/src/main/assets/web" && mkdir -p "$dir/app/src/main/assets/web" && cp -a "$web/." "$dir/app/src/main/assets/web/" || return 1
  # The launcher icon: the web app's own, inset on the background (the template's shape stays for notifications).
  icon=$(wrap_icon "$web")
  if [ -n "$icon" ]; then
    mkdir -p "$dir/app/src/main/res/drawable-nodpi"
    cp "$icon" "$dir/app/src/main/res/drawable-nodpi/ic_web.png"
    printf '%s\n' '<?xml version="1.0" encoding="utf-8"?>' \
      '<inset xmlns:android="http://schemas.android.com/apk/res/android" android:drawable="@drawable/ic_web" android:inset="20%" />' \
      > "$dir/app/src/main/res/drawable/ic_launcher_fg.xml"
    sed -i 's|@drawable/ic_fg|@drawable/ic_launcher_fg|' "$dir/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml"
  fi
  local content; content=$(version_time "$slug" web)
  meta "$slug" '.label=$l | .wrapped={from: "web", content: $c} | .content=$c | .from="web"' --arg l "$label" --argjson c "${content:-$(now)}"
  save "$slug" "the web version as an Android app (wrapped)"
}

# wrap_build <slug>: wraps, builds and installs, in a job. No agent: a failed build ends it (see the log).
wrap_build() {
  local slug=$1 irc=1
  job building "making the Android app from the web version"
  wrap_project "$slug" >> "$LOG" 2>&1 || { job failed "couldn't make the Android app from the web version: see the log"; return 1; }
  meta "$slug" '.status="building" | .updated=$t | .outcomes={build:"running",install:"unverified",runtime:"unverified",github:"local-only"}' --argjson t "$(now)"
  build_fixing "$slug" "" do_build 'building the Android app' || { meta "$slug" '.status="failed" | .outcomes.build="failed"'; return 1; }
  artifact_record "$slug" apk "$CACHE/apks/$slug.apk" || return 1
  do_install "$slug" job; irc=$?
  if [ "$irc" -eq 0 ]; then
    job installing 'checking startup'
    case $(crash_check "$(jq -r .pkg "$APPS/$slug/.hatchlybox.json")" >/dev/null; echo $?) in
      0) meta "$slug" '.outcomes.runtime="startup-verified"' ;;
      3) meta "$slug" '.outcomes.runtime="crashed"'; job failed "it crashes on start: see the log"; return 1 ;;
      *) meta "$slug" '.outcomes.runtime="unavailable"' ;;
    esac
  fi
  case $irc in
    0) job done "on your phone as an app" ;;
    2) job ready "built; download and install it" ;;
    3) job ready "built; the installed copy has a different signature" ;;
    4) job ready "built; installing couldn't be checked: install it again" ;;
    *) meta "$slug" '.status="failed"'; job failed "installing failed: see the log"; return 1 ;;
  esac
}

wrap_cmd() {
  local slug=${1:-}
  [[ $slug =~ ^[a-z0-9][a-z0-9-]*$ ]] || { echo "usage: hatchlybox wrap <slug>" >&2; exit 1; }
  [ -f "$PORTS/$slug/web/export/index.html" ] || { echo "$slug has no finished web version to put on the phone" >&2; exit 1; }
  if [ -f "$APPS/$slug/.hatchlybox.json" ] && ! wrapped "$slug"; then echo "$slug already has a full Android version" >&2; exit 1; fi
  start wrap "$slug" "" ""
}

# native_cmd <slug> <agent>: the wrapped app becomes a real Kotlin app, made from the web version by the agent.
native_cmd() {
  local slug=${1:-} agent=${2:-} dir=$APPS/${1:-} pkg label tmp f
  [[ $slug =~ ^[a-z0-9][a-z0-9-]*$ ]] && [ -n "$agent" ] || { echo "usage: hatchlybox native <slug> <agent>" >&2; exit 1; }
  wrapped "$slug" || { echo "only an app made from its web version becomes native this way (hatchlybox make $slug android <agent> makes one)" >&2; exit 1; }
  can_start "$agent"
  pkg=$(jq -r .pkg "$dir/.hatchlybox.json"); label=$(creation_label "$slug")
  # The usual native project, in the same folder with the same package: the phone updates the wrapped app in place.
  tmp=$(mktemp -d); project "$tmp/p" "$pkg" "$slug" "$label" || { rm -rf "$tmp"; exit 1; }
  rm -rf "$dir/app" && cp -r "$tmp/p/app" "$dir/app"
  for f in build.gradle.kts settings.gradle.kts gradle gradlew gradlew.bat; do rm -rf "${dir:?}/$f"; cp -r "$tmp/p/$f" "$dir/$f"; done
  rm -rf "$tmp"
  printf '<resources>\n    <string name="app_name">%s</string>\n</resources>\n' "$(xml_text "$label")" > "$dir/app/src/main/res/values/strings.xml"
  meta "$slug" 'del(.wrapped) | .from="web"'
  save "$slug" "start the native app from the web version" || exit 1
  export HATCHLYBOX_CONTENT; HATCHLYBOX_CONTENT=$(version_time "$slug" web)
  list_apps
  start app "$slug" "$agent" "$(android_from_prompt "$slug" web)"
}
