title: Typography and game UI
tags: typography font text ui hud menu buttons theme layout title logo readable
styles: all
cost: low
engine: godot4
summary: fonts, sizes, hierarchy and a Theme so menus and HUD look designed and stay readable

## Method
- **Two fonts at most**: a display face for titles (character) and a clean face for body/UI.
  Pixel games: a pixel font everywhere, at integer multiples of its native size.
- **Hierarchy by size and weight**: title 2–3× body, buttons 1.2×, captions 0.8×.
- **Contrast**: text against its background ≥ 4.5:1; over busy art add a panel, shadow or outline.
- **Godot Theme**: one `Theme` resource (or built in code) for all Controls: font, sizes, StyleBoxFlat
  panels/buttons with the palette's colours, corner radius matching the shape language.
- **Fonts available**: `SystemFont` (the phone's fonts, e.g. "sans-serif", "serif"), or `.ttf/.otf` files you
  write into the project (no downloads of commercial fonts). Outline text: `Label.label_settings` with
  `outline_size`, `shadow_size`.
- **Titles**: big, with an outline or shadow, maybe a gradient via a shader; animate in (scale/fade).

## Code
```gdscript
extends Control
# A themed menu built in code: palette colours, rounded buttons, readable sizes.
func _ready() -> void:
	var t := Theme.new()
	var font := SystemFont.new()
	font.font_names = PackedStringArray(["serif"])
	t.default_font = font
	t.default_font_size = 30
	var normal := StyleBoxFlat.new()
	normal.bg_color = Color("3b2f5c")
	normal.border_color = Color("f4b860")
	normal.set_border_width_all(3)
	normal.set_corner_radius_all(14)
	normal.content_margin_left = 24
	normal.content_margin_right = 24
	normal.content_margin_top = 12
	normal.content_margin_bottom = 12
	var pressed := normal.duplicate() as StyleBoxFlat
	pressed.bg_color = Color("7a4e7e")
	t.set_stylebox("normal", "Button", normal)
	t.set_stylebox("hover", "Button", normal)
	t.set_stylebox("pressed", "Button", pressed)
	t.set_color("font_color", "Button", Color("f6e7c1"))
	theme = t
	var title := Label.new()
	title.text = "Realm"
	var ls := LabelSettings.new()
	ls.font_size = 84
	ls.font_color = Color("f4b860")
	ls.outline_size = 10
	ls.outline_color = Color("1b1f3a")
	ls.shadow_size = 6
	ls.shadow_color = Color(0, 0, 0, 0.5)
	title.label_settings = ls
	add_child(title)
```

## Validation
`visual check --style ui` on a menu render: no low-contrast warning; at sheet size the title and buttons
are still legible.
