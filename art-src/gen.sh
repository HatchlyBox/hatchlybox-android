#!/bin/sh
# gen.sh <id> <scene> <palette>: one portrait wallpaper from Codex's image generator, saved as <id>.png.
cd "$(dirname "$0")"
[ -s "$1.png" ] && exit 0
timeout 900 codex exec --skip-git-repo-check -s danger-full-access "Use your image generation tool to make one portrait (1024x1536) phone home-screen wallpaper, then copy the PNG to $PWD/$1.png. Scene: $2. Palette: $3. Style: modern, detailed, beautiful digital painting, cinematic lighting, rich texture and depth. No text, no letters, no logos, no watermark, no people close up. Keep the lower two thirds calmer with fewer details so app icons and widgets on top stay readable. Do nothing else." > "$1.log" 2>&1
[ -s "$1.png" ] && echo "done $1" || echo "FAILED $1"
