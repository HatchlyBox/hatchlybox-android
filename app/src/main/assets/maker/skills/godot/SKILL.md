---
name: godot
description: Build a game with the Godot 4.7 engine instead of Kotlin - physics, sprites and animation, tilemaps, particles, cameras, sound, 2D or 3D - exported straight to an installable APK by Hatchlybox. Use for action games, platformers, shooters, racing, physics puzzles or anything with many moving objects; use the games skill (Compose Canvas) for simple taps-and-score games inside a normal app.
---

# Godot games

Godot 4.7.2 is installed on this phone (headless: no editor window), with its Android export
template. Working project: `example/` (a tap-the-targets game with a score, a timer, a saved best score
and safe-area handling). Copy it into `godot/` and build from there.

## How Hatchlybox builds it

- A project at **`godot/project.godot`** makes this a Godot app. Hatchlybox then imports it, checks every
  `.gd` script, and exports `godot/` with Godot's Android template to
  `app/build/outputs/apk/release/app-release.apk`, which it installs as usual. **No Gradle runs**, and the
  Kotlin `app/` sources are ignored: leave them, don't delete the folder.
- Hatchlybox sets the package name (`package/unique_name`) in `godot/export_presets.cfg` and turns off
  Godot's Gradle build. Keep an `Android` preset (copy the example's); don't change those two keys.
- The app's name is `config/name` in `project.godot`; its launcher icon is `config/icon` (an SVG or a
  512 px PNG, also used for the Play store icon). Make both fit the game. The package follows the name
  at the first build (`Realms of Wonder` → `com.hatchlybox.realms_of_wonder`), so name it before you return.
- Google Play works as for other apps: Hatchlybox sets `version/code` and `version/name` in the preset and
  turns the export into the app bundle Play needs. Don't set them yourself.

**Graphics:** read the `visual` skill too. It plans the look, renders the game without a screen
(`visual render`) and measures the screenshots, and its library has tested recipes for terrain, skies,
light, water, fog, particles, crowds, buildings, isometric maps and light 3D.

## Commands (run from the app folder)

```sh
godot --headless --path godot --import                      # import assets, build the class cache (first)
godot --headless --path godot --check-only -s res://main.gd  # parse one script: errors with file:line
godot --headless --path godot --quit-after 300               # run ~5 s without a window: runtime errors
```

Checking scripts is quick and worth doing before you return. Don't run `--export-release` in a managed
job: Hatchlybox exports after you exit and sends you any errors. If `godot` says it isn't installed, run
`hatchlybox godot` (about 180 MB, a few minutes).

## Project setup that works on phones

- `project.godot`: `renderer/rendering_method="gl_compatibility"` (works on every phone; the example has
  it), `window/stretch/mode="canvas_items"`, `window/stretch/aspect="expand"`, a portrait or landscape
  `window/handheld/orientation` (0 landscape, 1 portrait, 6 sensor), and
  `pointing/emulate_touch_from_mouse=true`.
- One game, every system: Hatchlybox exports the same project for the web, Linux, macOS and Windows (no extra work for
  you), and sets the renderers per system: phones and the web keep Compatibility, computers get Forward+ (Godot
  falls back to OpenGL where there's no Vulkan). Make the game look complete in Compatibility; Forward+-only polish
  in the WorldEnvironment (glow, SSAO, SDFGI, volumetric fog) shows on computers and is simply skipped elsewhere.
- Design for a 720×1280 (portrait) or 1280×720 (landscape) viewport; `expand` adds room on longer screens,
  so anchor UI to edges and read `get_viewport_rect().size` instead of assuming it.
- Keep HUD out of the status bar and notch: `DisplayServer.get_display_safe_area()` (see `_place_hud`).
- Touch: `InputEventScreenTouch` (pressed/released, `index` for multi-touch) and `InputEventScreenDrag`
  in `_unhandled_input`; on-screen buttons are `TouchScreenButton` or `Button` nodes. Android Back
  arrives as `NOTIFICATION_WM_GO_BACK_REQUEST`: pause or go back a screen, quit only from the menu.
- Pause when the app goes to the background: `NOTIFICATION_APPLICATION_PAUSED` / `_RESUMED`.
- Save progress, settings and high scores with `ConfigFile` or `FileAccess` under `user://`.

## GDScript 4 (not Godot 3)

- `extends Node2D`, `@onready var label: Label = $HUD/Score`, `@export var speed := 300.0`,
  `func _ready() -> void:`, typed `var x := 0.0`.
- Signals: `button.pressed.connect(_on_pressed)`, `signal died`, `died.emit()`. Timers:
  `await get_tree().create_timer(1.0).timeout`.
- Physics bodies: `CharacterBody2D` with `velocity` and `move_and_slide()` (no arguments),
  `RigidBody2D`, `Area2D` with `body_entered`. Tweens: `create_tween().tween_property(node, "position", to, 0.3)`.
- Scenes: `preload("res://enemy.tscn").instantiate()`, `get_tree().change_scene_to_file("res://menu.tscn")`.
- Not available: C# (only the standard build is installed), GDExtension/native plugins, Android plugins.

## Assets

Every game: search free asset libraries first, per asset type, as `assets.md` next to this skill says
(sources, licences, Godot import, one look, the `design/assets.md` manifest). What you can't find, draw with code (`_draw()`, `Polygon2D`, `Line2D`,
`GPUParticles2D`, `StyleBoxFlat` themes) or write the files yourself: SVG images (Godot imports them as textures), `.tres`
resources, fonts from the system (`SystemFont`). Sound: `AudioStreamGenerator` for synthesized effects
and music, or `.wav`/`.ogg` files you generate (Python's `wave` module is available). A game is done
when it has a start screen, clear controls, a score or goal, game over / win, restart, sound, and a
saved best score or progress.
