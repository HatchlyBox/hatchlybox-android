#!/bin/sh
cd "$(dirname "$0")"
while IFS='	' read -r id scene pal; do printf '%s\0%s\0%s\0' "$id" "$scene" "$pal"; done < prompts.tsv | xargs -0 -n3 -P3 ./gen.sh
