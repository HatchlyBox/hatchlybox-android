#!/usr/bin/env bash
# Refreshes after taps must never wait for network, ADB, or capability probes.
# Only this fixed list can be scheduled; explicit CLI checks still run immediately.
poll_spec() {
  case ${1:-} in
    github) POLL_TTL=1800; POLL_ARGS=(github status) ;;
    sync) POLL_TTL=120; POLL_ARGS=(github sync) ;;
    community) POLL_TTL=1800; POLL_ARGS=(play community) ;;
    keys) POLL_TTL=600; POLL_ARGS=(keys-state) ;;
    isolation) POLL_TTL=600; POLL_ARGS=(isolation) ;;
    adb) POLL_TTL=30; POLL_ARGS=(adb) ;;
    doctor) POLL_TTL=120; POLL_ARGS=(doctor) ;;
    agents) POLL_TTL=120; POLL_ARGS=(agents) ;;
    *) return 1 ;;
  esac
}
poll_due() {
  local stamp=0 current
  read -r stamp 2>/dev/null < "$CACHE/polls/$1.at" || true
  [[ $stamp =~ ^[0-9]+$ ]] || stamp=0
  printf -v current "%(%s)T" -1
  (( current < stamp || current - stamp >= POLL_TTL ))
}
poll_cached() {
  local name=${1:-}
  poll_spec "$name" || return 1
  mkdir -p "$CACHE/polls"
  if poll_due "$name"; then
    # Detach all streams: the front end's shell can return while the check runs.
    setsid -f "$DATA/bin/hatchlybox" _poll "$name" </dev/null >/dev/null 2>&1 6>&- 7>&- 9>&-
  fi
  if [ -f "$CACHE/polls/$name.out" ]; then cat "$CACHE/polls/$name.out"
  elif [ "$name" = agents ]; then echo '?'
  fi
}
poll_worker() (
  local name=${1:-} tmp rc
  poll_spec "$name" || return 1
  mkdir -p "$CACHE/polls"
  exec 8> "$CACHE/polls/$name.lock"
  flock -n 8 || exit 0
  poll_due "$name" || exit 0
  # Record attempts too, so offline failures cannot trigger a retry storm.
  date +%s > "$CACHE/polls/$name.at"
  tmp=$(mktemp "$CACHE/polls/.$name.XXXX") || exit 1
  trap 'rm -f "$tmp"' EXIT
  timeout 100 "$DATA/bin/hatchlybox" "${POLL_ARGS[@]}" > "$tmp" 2> "$CACHE/polls/$name.err"
  rc=$?
  # Preserve the last known result on failure; atomically publish successful checks.
  if [ "$rc" = 0 ]; then mv "$tmp" "$CACHE/polls/$name.out"; fi
  exit "$rc"
)
# Everything the front end polls in one process: each front end refresh (after every tap) then costs
# one shell instead of nine, so a tap's own command isn't queued behind the polls.
poll_all() {
  local n args=()
  for n in community keys isolation github sync adb doctor agents; do
    args+=(--arg "$n" "$(poll_cached "$n")")
  done
  args+=(--arg worker "$(health)")
  jq -nc '$ARGS.named | map_values(gsub("^\\s+|\\s+$"; ""))' "${args[@]}"
}
