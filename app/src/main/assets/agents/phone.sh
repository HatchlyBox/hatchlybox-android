#!/bin/sh
# phone: the phone's actions, for agents (see "The phone's actions" in your instructions).
D=/root/.hatchlybox/phone
die() { echo "$*" >&2; exit 1; }
# Hands a request to Hatchlybox and waits for its answer (the user may have to confirm it); $2: how many
# half-seconds to wait (2 minutes unless given).
ask() {
  id=$(date +%s)-$$; mkdir -p "$D/requests" "$D/results"
  printf '%s' "$1" > "$D/requests/$id.tmp" && mv "$D/requests/$id.tmp" "$D/requests/$id.json"
  # Every 50 ms for the first 10 seconds (most answers come in well under that), then every half second.
  i=0; t=0; max=$(( ${2:-240} * 500 ))
  while [ ! -f "$D/results/$id.json" ] && [ $t -lt $max ]; do
    if [ $i -lt 200 ]; then sleep 0.05; t=$((t + 50)); else sleep 0.5; t=$((t + 500)); fi; i=$((i + 1))
  done
  if [ -f "$D/results/$id.json" ]; then
    r=$(cat "$D/results/$id.json"); rm -f "$D/results/$id.json"
    printf '%s\n' "$(printf '%s' "$r" | jq -r '.say')"
    printf '%s' "$r" | jq -r '. as $r | if $r.image and (($r.say | contains($r.image)) | not) then "Image: " + $r.image else empty end'
    printf '%s' "$r" | jq -e '.ok' >/dev/null
  else
    rm -f "$D/requests/$id.json"
    die "No answer in time: Hatchlybox runs actions only while its home screen is open on the phone, and confirm or secure ones wait for the user."
  fi
}
case "$1" in
  list)
    [ -f "$D/actions.json" ] || die "No list yet: open Hatchlybox on the phone once."
    jq -r '.[] | "\(.name)  [\(.level)]  \(.description)" + (if (.params | length) > 0 then "\n    details: " + (.params | map(.name + " (" + .type + (if .enum then ": " + (.enum | join("|")) else "" end) + ")") | join(", ")) else "" end)' "$D/actions.json" ;;
  show)
    [ -n "$2" ] || die "usage: phone show <action>"
    jq -e --arg n "$2" '.[] | select(.name == $n)' "$D/actions.json" || die "There's no action $2. phone list lists them." ;;
  do)
    [ -n "$2" ] || die "usage: phone do <action> '<details as JSON>'   e.g. phone do open_app '{\"app\": \"YouTube\"}'"
    args=$3; [ -n "$args" ] || args='{}'
    printf '%s' "$args" | jq -e 'type == "object"' >/dev/null 2>&1 || die "The details must be one JSON object, e.g. '{\"app\": \"YouTube\"}'."
    ask "$(jq -cn --arg a "$2" --argjson x "$args" '{action: $a, args: $x}')" ;;
  reload)
    ask '{"reload": true}' ;;
  screen)
    # Shortcuts for the screen_* actions: see "Using the screen" in your instructions.
    shift; v=$1; [ -n "$v" ] && shift
    case "$v" in
      look)   img=false; w=""
              while [ $# -gt 0 ]; do case "$1" in --image) img=true ;; --wait) shift; w=$1 ;; esac; shift; done
              ask "$(jq -cn --argjson i $img --arg w "$w" '{action: "screen_look", args: {image: $i, wait_for: $w}}')" ;;
      tap|long) [ -n "$1" ] || die "usage: phone screen $v <number|cell> [third]   e.g. phone screen tap 12, phone screen tap C7 top-left"
              ask "$(jq -cn --arg t "$*" --argjson l "$([ "$v" = long ] && echo true || echo false)" '{action: "screen_tap", args: {target: $t, long: $l}}')" ;;
      aim)    [ -n "$1" ] || die "usage: phone screen aim <cell> [third]"
              ask "$(jq -cn --arg t "$*" '{action: "screen_aim", args: {target: $t}}')" ;;
      type)   [ $# -gt 0 ] || die "usage: phone screen type \"<text>\" [--into <number|cell>] [--replace] [--enter]"
              t=$1; shift; into=""; rp=false; en=false
              while [ $# -gt 0 ]; do case "$1" in --into) shift; into=$1 ;; --replace) rp=true ;; --enter) en=true ;; esac; shift; done
              ask "$(jq -cn --arg t "$t" --arg i "$into" --argjson r $rp --argjson e $en '{action: "screen_type", args: {text: $t, into: $i, replace: $r, enter: $e}}')" ;;
      scroll) ask "$(jq -cn --arg d "${1:-down}" --arg t "${2:-}" '{action: "screen_scroll", args: {direction: $d, target: $t}}')" ;;
      key)    [ -n "$1" ] || die "usage: phone screen key back|home|recents|notifications|quick_settings"
              ask "$(jq -cn --arg k "$1" '{action: "screen_key", args: {key: $k}}')" ;;
      ask)    [ -n "$1" ] || die "usage: phone screen ask \"<question>\" [option] [option] [option]   e.g. phone screen ask \"Pickup or delivery?\" Pickup Delivery"
              q=$1; shift; o=""; for x in "$@"; do o="${o:+$o|}$x"; done
              ask "$(jq -cn --arg q "$q" --arg o "$o" '{action: "screen_ask", args: {question: $q, options: $o}}')" 1260 ;;
      done)   [ "${1:-}" = --say ] && shift
              ask "$(jq -cn --arg a "$*" '{action: "screen_done", args: {answer: $a}}')" ;;
      *) cat <<'USAGE'
phone screen look [--image] [--wait "text"]   the screen as a numbered outline (+ screenshot with the A-H x 1-12 grid)
phone screen tap <number|cell> [third]        tap [12] from the outline, or C7, or C7 top-left
phone screen tap C7+D7                        tap between cells, for a button across a grid line
phone screen long <number|cell> [third]       long press
phone screen aim <cell> [third]               zoomed image with a red crosshair where the tap would land
phone screen type "<text>" [--into <number|cell>] [--replace] [--enter]
phone screen scroll down|up|left|right [<number>]
phone screen key back|home|recents|notifications|quick_settings
phone screen ask "<question>" [opt]... (up to 5)  the user decides, on a card over the screen; prints their answer
phone screen done --say "<answer>"            give the screen back, back to Hatchlybox
USAGE
         [ -z "$v" ] || exit 1 ;;
    esac ;;
  *)
    cat <<'USAGE'
phone list                          every action: name, level, what it does, its details
phone show <action>                 one action in full (JSON)
phone do <action> '<details JSON>'  runs it on the phone; prints the result, exits 1 if not done
phone reload                        loads the actions in /root/actions again (after making one)
phone screen …                      see and use the screen in any app (phone screen, for its commands)
USAGE
    [ -z "$1" ] || exit 1 ;;
esac
