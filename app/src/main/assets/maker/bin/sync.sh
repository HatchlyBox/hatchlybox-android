# Sync between the user's own devices. Sourced by hatchlybox.
#
# Every device has an id (sync-device.json), and every creation one id shared by all its versions (.id in
# .hatchlybox.json and each .port.json), so the same creation is known on every device whatever its slug.
# A sync is two-way and needs nothing in the cloud: each side says what it has (manifest), the other packs
# what's missing (pack: git history for the Android version, the folder for any other system), and each
# side merges what it got (apply). Builds don't travel, except an APK for a device that can't build Android:
# every device builds its own (and signs with its own key). Changed on both devices since they last met:
# both stay, the other one on a branch, and the user picks (resolve).
# hatchlybox sync send|receive (Sync.java) does it over Wi-Fi, with a code that is also the encryption key.

# The device's sync identity. Not device.json: the phone app keeps its own description there (for visual device).
DEVICE=$DATA/sync-device.json

new_id() { head -c 8 /dev/urandom | od -An -tx1 | tr -d ' \n'; }

# device_json: {id, name}. A front end names it (the phone's model) with HATCHLYBOX_DEVICE_NAME.
device_json() {
  # Made once; made again if it was ever lost or isn't an id (paired devices then pair again).
  jq -e '.id | test("^[0-9a-f]{16}$")' "$DEVICE" >/dev/null 2>&1 \
    || jq -nc --arg id "$(new_id)" --arg n "$(hostname 2>/dev/null || echo device)" '{id: $id, name: $n}' > "$DEVICE"
  jq -c --arg n "${HATCHLYBOX_DEVICE_NAME:-}" 'if $n != "" then .name = $n else . end' "$DEVICE"
}

# all_slugs: every creation here, one per line.
all_slugs() {
  local d
  { for d in "$APPS"/*/.hatchlybox.json; do [ -f "$d" ] && basename "$(dirname "$d")"; done
    for d in "$PORTS"/*/*/.port.json; do [ -f "$d" ] && basename "$(dirname "$(dirname "$d")")"; done; } | sort -u
}

# creation_id <slug>: its id, given to every version that hasn't got it (json_edit, not meta: meta lists
# the apps, which asks for the ids again).
creation_id() {
  local slug=$1 id f
  id=$(for f in "$APPS/$slug/.hatchlybox.json" "$PORTS/$slug"/*/.port.json; do [ ! -f "$f" ] || jq -r '.id // empty' "$f" 2>/dev/null; done | grep -m1 . || true)
  [ -n "$id" ] || id=$(new_id)
  for f in "$APPS/$slug/.hatchlybox.json" "$PORTS/$slug"/*/.port.json; do
    [ -f "$f" ] && [ "$(jq -r '.id // empty' "$f" 2>/dev/null)" != "$id" ] && json_edit "$f" --arg i "$id" '.id = $i'
  done
  echo "$id"
}

ensure_ids() { local s; while read -r s; do [ -n "$s" ] && creation_id "$s" >/dev/null; done < <(all_slugs); true; }

# slug_of_id <id>: the creation here with that id.
slug_of_id() {
  local s f ids; while read -r s; do
    [ -n "$s" ] || continue
    ids=$(for f in "$APPS/$s/.hatchlybox.json" "$PORTS/$s"/*/.port.json; do [ ! -f "$f" ] || jq -r '.id // empty' "$f" 2>/dev/null; done)
    grep -qxF -- "$1" <<< "$ids" && { echo "$s"; return; }
  done < <(all_slugs)
  true
}

# main_branch <slug>: the Android version's own branch (the one a waiting change branched off).
main_branch() {
  if pending "$1"; then pget "$1" base; else git -C "$APPS/$1" branch --show-current; fi
}

# sync_busy <slug>: a job works on it now, so it's neither packed nor changed.
sync_busy() { job_active && [ "$(jq -r '.slug // empty' "$JOB" 2>/dev/null)" = "$1" ]; }

# can_build_android: this device can build an Android version itself.
can_build_android() { [ -x "$JAVA_HOME/bin/java" ] && sdk_ok "$ANDROID_HOME"; }

# sync_manifest: what's here: {device, builds, creations: [{id, slug, android: {head, recent}, ports: {system: {content}}}]}.
sync_manifest() {
  ensure_ids
  local slug id android ports b d
  local creations; creations=$(while read -r slug; do
      [ -n "$slug" ] || continue
      id=$(creation_id "$slug"); android=null
      if [ -d "$APPS/$slug/.git" ] && [ -f "$APPS/$slug/.hatchlybox.json" ]; then
        b=$(main_branch "$slug")
        android=$(jq -nc --arg h "$(git -C "$APPS/$slug" rev-parse -q --verify "refs/heads/$b")" \
          --argjson r "$(git -C "$APPS/$slug" rev-list -n 300 "refs/heads/$b" 2>/dev/null | jq -Rsc 'split("\n") | map(select(. != ""))')" '{head: $h, recent: $r}')
      fi
      ports=$(for d in "$PORTS/$slug"/*/; do
          [ -f "$d/.port.json" ] && jq -c --arg s "$(basename "$d")" '{key: $s, value: {content: (.content // .changed // .created // 0)}}' "$d/.port.json"
        done | jq -sc 'from_entries')
      jq -nc --arg id "$id" --arg s "$slug" --argjson a "$android" --argjson p "${ports:-{\}}" '{id: $id, slug: $s, android: $a, ports: $p}'
    done < <(all_slugs) | jq -sc .)
  jq -nc --argjson d "$(device_json)" --argjson c "$creations" --argjson b "$(can_build_android && echo '["android"]' || echo '[]')" \
    '{device: $d, builds: $b, creations: $c}'
}

# The Android version's details that belong to the creation, not to this device (no install, Play or
# GitHub state, no paths), for the other device.
SYNC_META='{id, slug, pkg, label, created, updated, content, history, from, pkgNamed, description, agent} | with_entries(select(.value != null))'

# sync_pack <their manifest> <out.tar>: what they're missing, as one tar.
sync_pack() {
  local theirs=$1 out=$2 w slug id t dir b head th apk d sys ours tc
  w=$(mktemp -d "$CACHE/.pack.XXXX") || return 1
  ensure_ids
  while read -r slug; do
    [ -n "$slug" ] || continue
    sync_busy "$slug" && continue
    id=$(creation_id "$slug"); dir=$w/$id
    t=$(jq -c --arg id "$id" '[.creations[] | select(.id == $id)][0] // {}' "$theirs")
    if [ -d "$APPS/$slug/.git" ] && [ -f "$APPS/$slug/.hatchlybox.json" ]; then
      b=$(main_branch "$slug"); head=$(git -C "$APPS/$slug" rev-parse -q --verify "refs/heads/$b")
      th=$(jq -r '.android.head // empty' <<< "$t")
      # They have this commit already (it's in their recent history): nothing to send.
      if [ -n "$head" ] && ! jq -e --arg h "$head" '(.android.recent // []) | index($h) != null' <<< "$t" >/dev/null; then
        mkdir -p "$dir"
        # Behind us: only the commits since theirs. Unknown here (ahead of us, or both changed): all of it.
        if [ -n "$th" ] && git -C "$APPS/$slug" cat-file -e "$th^{commit}" 2>/dev/null && git -C "$APPS/$slug" merge-base --is-ancestor "$th" "$head"; then
          git -C "$APPS/$slug" bundle create -q "$dir/android.bundle" "refs/heads/$b" "^$th" 2>> "$LOG"
        else
          git -C "$APPS/$slug" bundle create -q "$dir/android.bundle" "refs/heads/$b" 2>> "$LOG"
        fi || { rm -rf "$dir"; continue; }
        jq -c --arg b "$b" "$SYNC_META"' + {branch: $b}' "$APPS/$slug/.hatchlybox.json" > "$dir/android.json"
        # A device that can't build Android gets the APK, when it's this code's build.
        apk=$(jq -r '.apk // empty' "$APPS/$slug/.hatchlybox.json")
        if ! jq -e '.builds | index("android")' "$theirs" >/dev/null && [ -f "$apk" ] \
          && jq -e --arg s "$slug" '.[] | select(.slug == $s) | .state.key | IN("installed","ready","update","downloaded","crashed","blocked","live","testers")' "$CACHE/apps.json" >/dev/null 2>&1; then
          cp "$apk" "$dir/android.apk"
        fi
      fi
    fi
    for d in "$PORTS/$slug"/*/; do
      [ -f "$d/.port.json" ] || continue
      sys=$(basename "$d")
      ours=$(jq -r '.content // .changed // .created // 0' "$d/.port.json"); tc=$(jq -r --arg s "$sys" '.ports[$s].content // -1' <<< "$t")
      # Newer here, and finished (one an agent never finished has nothing to give).
      [ "${ours%.*}" -gt "${tc%.*}" ] && [ -n "$(jq -r '.changed // empty' "$d/.port.json")" ] || continue
      mkdir -p "$dir/ports"
      tar -C "$PORTS/$slug" -czf "$dir/ports/$sys.tgz" "$sys" 2>> "$LOG" || rm -f "$dir/ports/$sys.tgz"
    done
    [ -d "$dir" ] && jq -nc --arg id "$id" --arg s "$slug" --arg l "$(creation_label "$slug")" '{id: $id, slug: $s, label: $l}' > "$dir/creation.json"
  done < <(all_slugs)
  device_json > "$w/device.json"
  tar -C "$w" -cf "$out" . && rm -rf "$w"
}

# sync_apply <in.tar>: merge what the other device sent. Prints {from, added, updated, conflicts, skipped}
# (labels), and lists the apps.
sync_apply() {
  local w from c cd id slug label new b bin head t sys d local_c in_c note
  local added=() updated=() conflicts=() skipped=()
  w=$(mktemp -d "$CACHE/.apply.XXXX") || return 1
  tar -C "$w" -xf "$1" || { rm -rf "$w"; echo "the other device sent something unreadable" >&2; return 1; }
  from=$(jq -r '.name // "the other device"' "$w/device.json" 2>/dev/null)
  ensure_ids
  for c in "$w"/*/creation.json; do
    [ -f "$c" ] || continue
    cd=$(dirname "$c"); id=$(jq -r .id "$c"); label=$(jq -r .label "$c")
    [[ $id =~ ^[0-9a-f]{16}$ ]] || continue
    slug=$(slug_of_id "$id"); new=""
    if [ -z "$slug" ]; then
      slug=$(jq -r .slug "$c"); [[ $slug =~ ^[a-z0-9][a-z0-9-]*$ ]] || slug=$(slugify "$label")
      { [ -e "$APPS/$slug" ] || [ -e "$PORTS/$slug" ]; } && slug=$(new_slug "$slug")
      new=1
    fi
    if sync_busy "$slug"; then skipped+=("$label (busy here)"); continue; fi
    note=""
    # The Android version.
    if [ -f "$cd/android.bundle" ]; then
      b=$(jq -r '.branch // "master"' "$cd/android.json")
      if [ ! -d "$APPS/$slug/.git" ]; then
        if git clone -q -b "$b" "$cd/android.bundle" "$APPS/$slug" 2>> "$LOG"; then
          git -C "$APPS/$slug" remote remove origin 2>/dev/null
          hygiene "$APPS/$slug"
          jq -c --arg s "$slug" --arg id "$id" --argjson t "$(now)" '. + {id: $id, slug: $s, status: "built", synced: $t} | del(.branch)' "$cd/android.json" > "$APPS/$slug/.hatchlybox.json"
          if [ -f "$cd/android.apk" ]; then
            mkdir -p "$CACHE/apks"; cp "$cd/android.apk" "$CACHE/apks/$slug.apk"
            json_edit "$APPS/$slug/.hatchlybox.json" --arg a "$CACHE/apks/$slug.apk" --argjson t "$(now)" '.apk = $a | .built = $t'
          fi
          note=added
        else rm -rf "${APPS:?}/$slug"; skipped+=("$label (couldn't read its Android version)"); fi
      elif pending "$slug"; then
        skipped+=("$label (a change waits here: keep or undo it, then sync again)")
      else
        save "$slug" "Save work before syncing" || true
        head=$(main_branch "$slug")
        if gq "$APPS/$slug" fetch -q "$cd/android.bundle" "+refs/heads/$b:refs/sync/incoming" 2>> "$LOG"; then
          bin=$(git -C "$APPS/$slug" rev-parse refs/sync/incoming); t=$(git -C "$APPS/$slug" rev-parse "refs/heads/$head")
          # Both devices' request history, once each.
          json_edit "$APPS/$slug/.hatchlybox.json" --slurpfile o "$cd/android.json" '.history = (((.history // []) + ($o[0].history // [])) | unique_by(.t) | sort_by(.t))'
          if [ "$bin" = "$t" ] || git -C "$APPS/$slug" merge-base --is-ancestor "$bin" "$t"; then :
          elif git -C "$APPS/$slug" merge-base --is-ancestor "$t" "$bin"; then
            gq "$APPS/$slug" checkout -q "$head" && gq "$APPS/$slug" merge -q --ff-only refs/sync/incoming >> "$LOG" 2>&1 && {
              json_edit "$APPS/$slug/.hatchlybox.json" --slurpfile o "$cd/android.json" --argjson t "$(now)" \
                '.label = ($o[0].label // .label) | .content = ($o[0].content // .content) | .updated = $t | .synced = $t | .outcomes.build = "needs-rebuild"'
              note=updated; }
          else
            t=$(now); local br; br="sync/$(slugify "$from" | cut -c1-24)"; [ "$br" != sync/ ] || br=sync/device; br="$br-$t"
            gq "$APPS/$slug" branch -q -f "$br" refs/sync/incoming
            json_edit "$APPS/$slug/.hatchlybox.json" --arg d "$from" --arg b "$br" --argjson t "$t" --slurpfile o "$cd/android.json" \
              '.sync.conflict = {device: $d, branch: $b, t: $t, label: ($o[0].label // null), content: ($o[0].content // null)}'
            conflicts+=("$label")
          fi
          git -C "$APPS/$slug" update-ref -d refs/sync/incoming 2>/dev/null
        else skipped+=("$label (its Android version didn't fit: sync again)"); fi
      fi
    fi
    # Versions for other systems: the newer one wins; the one it replaces is kept in sync-old/.
    for d in "$cd"/ports/*.tgz; do
      [ -f "$d" ] || continue
      sys=$(basename "$d" .tgz); [[ $sys =~ ^[a-z][a-z0-9-]*$ ]] || continue
      in_c=$(tar -xzOf "$d" "$sys/.port.json" 2>/dev/null | jq -r '.content // .changed // .created // 0' 2>/dev/null)
      [ -n "$in_c" ] || continue
      if [ -f "$PORTS/$slug/$sys/.port.json" ]; then
        local_c=$(jq -r '.content // .changed // .created // 0' "$PORTS/$slug/$sys/.port.json")
        [ "${in_c%.*}" -gt "${local_c%.*}" ] || continue
        mkdir -p "$CACHE/sync-old"; mv "$PORTS/$slug/$sys" "$CACHE/sync-old/$slug-$sys-$(now)"
        note=${note:-updated}
      else note=${note:-$([ -n "$new" ] && echo added || echo updated)}; fi
      mkdir -p "$PORTS/$slug"
      if tar -C "$PORTS/$slug" -xzf "$d" "$sys" 2>> "$LOG"; then json_edit "$PORTS/$slug/$sys/.port.json" --arg i "$id" '.id = $i'
      else skipped+=("$label for $(system_name "$sys") (unreadable)"); fi
    done
    case $note in added) added+=("$label") ;; updated) updated+=("$label") ;; esac
  done
  rm -rf "$w"
  list_apps
  local j; j() { printf '%s\n' "$@" | jq -Rsc 'split("\n") | map(select(. != ""))'; }
  jq -nc --arg f "$from" --argjson a "$(j "${added[@]}")" --argjson u "$(j "${updated[@]}")" --argjson c "$(j "${conflicts[@]}")" --argjson s "$(j "${skipped[@]}")" \
    '{from: $f, added: $a, updated: $u, conflicts: $c, skipped: $s}'
}

# sync_resolve <slug> mine|theirs: after both devices changed it, keep this device's version or the other's.
# The one not kept stays on a branch (sync/kept-<time>), nothing is lost.
sync_resolve() {
  local slug=${1:-} pick=${2:-} d br head
  need_app "$slug"; d=$APPS/$slug
  br=$(jq -r '.sync.conflict.branch // empty' "$d/.hatchlybox.json")
  [ -n "$br" ] || { echo "$slug has nothing to choose between" >&2; return 1; }
  git_lock
  head=$(main_branch "$slug")
  case $pick in
    mine) gq "$d" branch -q -m "$br" "sync/kept-$(now)" 2>/dev/null || gq "$d" branch -q -D "$br"
      json_edit "$d/.hatchlybox.json" 'del(.sync.conflict)' ;;
    theirs)
      save "$slug" "Save work before taking the other device's version" || true
      gq "$d" branch -q -f "sync/kept-$(now)" "refs/heads/$head" && gq "$d" checkout -q "$head" && gq "$d" reset -q --hard "$br" || { echo "couldn't take the other version" >&2; return 1; }
      gq "$d" branch -q -D "$br"
      json_edit "$d/.hatchlybox.json" --argjson t "$(now)" '.label = (.sync.conflict.label // .label) | .content = (.sync.conflict.content // .content) | .updated = $t | .outcomes.build = "needs-rebuild" | del(.sync.conflict)' ;;
    *) echo "usage: hatchlybox sync resolve <slug> mine|theirs" >&2; return 1 ;;
  esac
  list_apps
  echo "kept $([ "$pick" = mine ] && echo "this device's" || echo "the other device's") version of $(creation_label "$slug")"
}

# ---- paired devices ----
# A code pairs two devices: both keep a key for each other (devices.tsv: id, key, name, paired, last synced;
# private to this device, never synced), and sync by themselves after that (hatchlybox sync serve).
DEVICES=$DATA/devices.tsv
SYNC_TRIGGER=$CACHE/sync-trigger

# sync_changed: something changed here: hatchlybox sync serve calls the paired devices soon.
sync_changed() { touch "$SYNC_TRIGGER" 2>/dev/null || true; }

# sync_remember: from Sync.java after pairing, on stdin {id, name, key}.
sync_remember() {
  local j id key name
  j=$(head -c 4096) && id=$(jq -r .id <<< "$j") && key=$(jq -r .key <<< "$j") || return 1
  name=$(jq -r '.name // "device"' <<< "$j" | tr -d '\t\r\n' | cut -c1-64)
  [[ $id =~ ^[0-9a-f]{16}$ && $key =~ ^[0-9a-f]{64}$ ]] || { echo "not a device" >&2; return 1; }
  ( umask 077; touch "$DEVICES"
    { grep -v "^$id"$'\t' "$DEVICES" || true; printf '%s\t%s\t%s\t%s\t%s\n' "$id" "$key" "$name" "$(now)" "$(now)"; } > "$DEVICES.tmp" && mv "$DEVICES.tmp" "$DEVICES" )
}
sync_seen() {
  [[ ${1:-} =~ ^[0-9a-f]{16}$ ]] && [ -f "$DEVICES" ] || return 0
  ( umask 077; awk -F'\t' -v OFS='\t' -v id="$1" -v t="$(now)" '$1 == id { $5 = t } { print }' "$DEVICES" > "$DEVICES.tmp" && mv "$DEVICES.tmp" "$DEVICES" )
}
sync_forget() {
  [[ ${1:-} =~ ^[0-9a-f]{16}$ ]] || { echo "usage: hatchlybox sync forget <device id>" >&2; return 1; }
  [ -f "$DEVICES" ] || return 0
  ( umask 077; { grep -v "^$1"$'\t' "$DEVICES" || true; } > "$DEVICES.tmp" && mv "$DEVICES.tmp" "$DEVICES" )
  echo "forgot it: pair again with a code to sync with it"
}
# sync_devices: the paired devices, without their keys: [{id, name, paired, last}].
sync_devices() {
  [ -s "$DEVICES" ] || { echo '[]'; return; }
  jq -Rsc 'split("\n") | map(select(. != "") | split("\t") | {id: .[0], name: .[2], paired: (.[3] | tonumber? // 0), last: (.[4] | tonumber? // 0)})' "$DEVICES"
}
sync_relay() { echo "${HATCHLYBOX_RELAY:-$(setting relay)}"; }

# sync_autostart on|off: on a computer, hatchlybox sync serve starts with the user's session (systemd on
# Linux, launchd on macOS), so it syncs with the paired devices whenever both are on.
sync_autostart() {
  local on=${1:-} unit plist
  [[ $on =~ ^(on|off)$ ]] || { echo "usage: hatchlybox sync autostart on|off" >&2; return 1; }
  if [ "$HOST" = macos ]; then
    plist=$HOME/Library/LaunchAgents/app.hatchlybox.sync.plist
    launchctl unload "$plist" >/dev/null 2>&1 || true
    if [ "$on" = on ]; then
      mkdir -p "$(dirname "$plist")"
      cat > "$plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>app.hatchlybox.sync</string>
  <key>ProgramArguments</key><array><string>$DATA/bin/hatchlybox</string><string>sync</string><string>serve</string></array>
  <key>RunAtLoad</key><true/><key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$CACHE/sync-serve.log</string><key>StandardErrorPath</key><string>$CACHE/sync-serve.log</string>
</dict></plist>
PLIST
      launchctl load "$plist"
    else rm -f "$plist"; fi
  elif [ "$HOST" = linux ]; then
    command -v systemctl >/dev/null 2>&1 || { echo "no systemd here: run hatchlybox sync serve yourself (in a terminal or your session's autostart)" >&2; return 1; }
    unit=${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user/hatchlybox-sync.service
    if [ "$on" = on ]; then
      mkdir -p "$(dirname "$unit")"
      printf '[Unit]\nDescription=Hatchlybox: sync with paired devices\n\n[Service]\nExecStart=%s sync serve\nRestart=on-failure\nRestartSec=30\n\n[Install]\nWantedBy=default.target\n' "$DATA/bin/hatchlybox" > "$unit"
      systemctl --user daemon-reload && systemctl --user enable --now hatchlybox-sync.service
    else
      systemctl --user disable --now hatchlybox-sync.service 2>/dev/null || true; rm -f "$unit"; systemctl --user daemon-reload 2>/dev/null || true
    fi
  else
    echo "on this device the app keeps sync running" >&2; return 1
  fi
  echo "sync by itself: $on"
}

# sync_lock: one sync step at a time. Not work.lock: a job holds that while it runs, and a sync goes on
# meanwhile, leaving out only the creation the job works on (sync_busy).
sync_lock() { exec 4> "$CACHE/sync.lock"; flock -w 120 4 || { echo "another sync is still running" >&2; exit 1; }; }

# sync_cmd: hatchlybox sync send | receive <code> [--to host:port] | serve | now | devices | forget <id>
#   | visible | nearby | ask <host:port> [--peer <name>] (nearby devices, no code: both confirm six digits)
#   | resolve <slug> mine|theirs | autostart on|off; for Sync.java: id | manifest | pack <their manifest> <out>
#   | apply <tar> | remember (stdin) | seen <id>
sync_cmd() {
  mkdir -p "$CACHE"
  case ${1:-} in
    id) device_json ;;
    manifest) sync_manifest ;;
    pack) [ -f "${2:-}" ] && [ -n "${3:-}" ] || { echo "usage: hatchlybox sync pack <their manifest> <out.tar>" >&2; return 1; }
      sync_lock; sync_pack "$2" "$3" ;;
    apply) [ -f "${2:-}" ] || { echo "usage: hatchlybox sync apply <tar>" >&2; return 1; }
      sync_lock; sync_apply "$2" ;;
    resolve) shift; sync_lock; sync_resolve "$@" && sync_changed ;;
    remember) sync_remember ;;
    seen) sync_seen "${2:-}" ;;
    devices) sync_devices ;;
    forget) sync_forget "${2:-}" ;;
    now) sync_changed ;;
    autostart) sync_autostart "${2:-}" ;;
    send|receive|serve|visible|nearby|ask)
      command -v java >/dev/null 2>&1 || [ -x "$JAVA_HOME/bin/java" ] || { echo '{"state":"failed","msg":"Java is needed to sync: run hatchlybox setup"}'; return 1; }
      local java=java relay; [ -x "$JAVA_HOME/bin/java" ] && java=$JAVA_HOME/bin/java
      relay=$(sync_relay)
      exec "$java" -Xmx96m "$DATA/bin/Sync.java" "$@" --engine "$0" --name "$(device_json | jq -r .name)" --id "$(device_json | jq -r .id)" \
        --devices "$DEVICES" --trigger "$SYNC_TRIGGER" ${relay:+--relay "$relay"} ;;
    *) echo "usage: hatchlybox sync send | receive <code> | serve | now | devices | forget <id> | resolve <slug> mine|theirs | autostart on|off" >&2; return 1 ;;
  esac
}
