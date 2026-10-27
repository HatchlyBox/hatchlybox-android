# route.sh: what an idea becomes when the person didn't say (hatchlybox create auto, hatchlybox route), sourced by hatchlybox.
# web is the default: it runs on every device, opens at once and is the most reliable to make, and a phone gets it as
# an app with one tap (hatchlybox wrap). android when the idea needs the phone itself: widgets, notifications, alarms,
# sensors, the camera, calls and messages, running in the background, Google Play. game: a real game (physics,
# levels, 3D…), made with Godot, which exports to every system; a small game stays web. A system the idea names wins.
# The app's IntentRouter.kt follows the same rules.

# route_words <text>: the idea in lower case, with Portuguese and Spanish words for the same things.
route_words() {
  tr '[:upper:]' '[:lower:]' <<< " $1 " | sed -E \
    -e 's/(jogo|juego)s?/game/g' -e 's/(aplicativo|aplicação|aplicación)/app/g' \
    -e 's/(notificaç(ão|ões)|notificaci(ón|ones))/notification/g' -e 's/(lembrete|recordatorio)s?/reminder/g' \
    -e 's/(alarme|alarma)s?/alarm/g' -e 's/(câmera|camara|cámara)/camera/g' -e 's/(localização|ubicación)/location/g' \
    -e 's/(ecrã inicial|tela inicial|pantalla de inicio)/home screen/g' -e 's/(sitio web|página web)/website/g' \
    -e 's/(estrat[eé]gia|estrategia)/strategy/g' -e 's/(carreras|corridas)/racing/g' -e 's/plataformas/platformer/g' \
    -e 's/(niveles|n[ií]veis)/levels/g' -e 's/f[ií]sica/physics/g' -e 's/(disparos|tiros)/shooter/g'
}

# route_has <words> <pattern>: the pattern (extended regex, whole words) is in the words.
route_has() { grep -Eq "(^|[^a-z0-9])($2)([^a-z0-9]|$)" <<< "$1"; }

# route_idea <idea>: one line, "<system> <why>". system: web, android, game, linux, omarchy, macos, windows, other.
route_idea() {
  local t; t=$(route_words "$1")
  # Named outright.
  if route_has "$t" 'omarchy|hyprland|waybar'; then echo "omarchy it names Omarchy"; return; fi
  if route_has "$t" 'windows|win1[01]|\.exe'; then echo "windows it names Windows"; return; fi
  if route_has "$t" 'macos|mac app|for (my )?mac|macbook'; then echo "macos it names macOS"; return; fi
  if route_has "$t" 'ios|iphone|ipad|raspberry pi|arduino|esp32|steam deck|playstation|xbox|nintendo|smartwatch|wear ?os'; then echo "other it names another system"; return; fi
  if route_has "$t" 'linux|gtk|gnome|kde' && ! route_has "$t" 'android'; then echo "linux it names Linux"; return; fi
  if route_has "$t" 'web ?apps?|websites?|web ?pages?|landing page|homepage|in the browser|browser game|pwa'; then echo "web it asks for the web"; return; fi
  # A real game: Godot. A small one (cards, puzzles, quizzes, a classic) is a web game.
  if route_has "$t" 'games?'; then
    if route_has "$t" '3d|physics|platformer|shooter|fps|first[- ]person|third[- ]person|rpg|racing|racer|open[- ]world|strategy|rts|tower defen[cs]e|roguelike|roguelite|metroidvania|survival|sandbox|levels|sprites?|tilemap|bullet hell|beat .?em up|fighting|multiplayer|godot|hex battles'; then
      echo "game a real game: Godot exports it to every system"; return
    fi
    if route_has "$t" 'android|apk|google play|play store'; then echo "game it asks for an Android game"; return; fi
    echo "web a small game runs anywhere"; return
  fi
  # It needs the phone itself.
  if route_has "$t" 'android|apk|google play|play store|widgets?|home ?screen|lock ?screen|launcher|wallpaper|notifications?|notify|notifies|reminders?|remind me|alarms?|wake me|in the background|runs? in background|sensors?|step counter|count(s|ing)? (my )?steps|pedometer|accelerometer|gyroscope|compass|camera|take (a )?photos?|scanner|scan (a |the )?(qr|barcode|document|receipt)s?|qr (code )?scanner|barcode|bluetooth|nfc|gps|location|where i am|nearby|calls?|sms|text messages?|contacts|vibrat(e|ion)|flashlight|torch|microphone|record (audio|sound|voice)|battery|offline maps|sleep tracking|share sheet|whatsapp'; then
    echo "android it needs the phone itself"; return
  fi
  # This computer's own kind of program, on a computer.
  if [ "$HOST" = linux ] || [ "$HOST" = macos ] || [ "$HOST" = windows ]; then
    if route_has "$t" 'tray|system tray|menu bar|status bar|keybinding|keyboard shortcut|cli|command[- ]line|terminal (tool|command)|daemon|desktop app'; then
      if [ "$HOST" = macos ]; then echo "macos a program for this Mac"; elif [ "$HOST" = windows ]; then echo "windows a program for this Windows computer"; elif [[ " $INSTALLS " == *" omarchy "* ]]; then echo "omarchy a program for this Omarchy computer"; else echo "linux a program for this computer"; fi
      return
    fi
  fi
  echo "web the default: it runs on every device"
}

route_cmd() {
  [ -n "${1:-}" ] || { echo "usage: hatchlybox route <idea>" >&2; return 1; }
  local r; r=$(route_idea "$*")
  jq -nc --arg s "${r%% *}" --arg w "${r#* }" '{system: $s, why: $w}'
}
