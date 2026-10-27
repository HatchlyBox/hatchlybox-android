#!/bin/sh
# Day of the year, days in it, days left, the week number, and the share of the year gone (%).
day=$(date +%j | sed 's/^0*//'); year=$(date +%Y); week=$(date +%V | sed 's/^0*//')
days=365; if [ $((year % 4)) -eq 0 ] && { [ $((year % 100)) -ne 0 ] || [ $((year % 400)) -eq 0 ]; }; then days=366; fi
jq -nc --argjson day "${day:-1}" --argjson days "$days" --argjson week "${week:-1}" \
  '{day: $day, days: $days, left: ($days - $day), week: $week, pct: ($day * 100 / $days | floor)}'
