title: Pixel art done right in Godot
tags: pixel art 8-bit 16-bit retro sprites nearest filter integer scaling palette dithering crisp
styles: pixel
cost: low
engine: godot4
summary: crisp pixels: nearest filtering, integer scale, small fixed palette, consistent pixel size

## Setup (project.godot)
```
[display]
window/size/viewport_width=320
window/size/viewport_height=180
window/stretch/mode="viewport"
window/stretch/scale_mode="integer"
window/stretch/aspect="expand"
[rendering]
textures/canvas_textures/default_texture_filter=0
2d/snap/snap_2d_transforms_to_pixel=true
2d/snap/snap_2d_vertices_to_pixel=true
```
Portrait: 180×320 (or 360×640 for more room). `stretch/mode="viewport"` renders at the low
resolution and scales up: every sprite shares one pixel grid.

## Rules
- One palette of 16–32 colours (`visual check --style pixel` warns past 256 exact colours, which means blur).
- Same pixel size everywhere; never rotate or scale sprites by non-integers (rotate by swapping frames instead).
- Clean lines: no doubles or jaggies; 1 px dark outline in a darker hue of the fill, or selective outlines.
- Shading: 2–4 tones per material, hue-shifted; dithering only for large gradients (sky).
- Animation: 4–8 frames per action at 8–12 fps; smear frames for fast moves.
- Text: a pixel font at its native size × integer; or draw text via `Label` with a bitmap font.
- Drawing sprites without an editor: write PNGs from Python (`zlib` + `struct`, no Pillow needed) from
  ASCII art maps, or draw with `Image.set_pixel` in GDScript at startup and make an `ImageTexture`.

## Code
```gdscript
extends Node2D
# A sprite from an ASCII map and a palette, built at startup (no image files needed).
const PAL := {".": Color(0, 0, 0, 0), "k": Color("1b1f3a"), "y": Color("f4b860"), "w": Color("f6e7c1"), "r": Color("d17b6f")}
const HERO := [
	"..kkkk..",
	".kyyyyk.",
	"kywkkwyk",
	"kyyyyyyk",
	".krrrrk.",
	".kr..rk.",
	"..k..k..",
]

static func texture_from(rows: Array, pal: Dictionary) -> ImageTexture:
	var img := Image.create(rows[0].length(), rows.size(), false, Image.FORMAT_RGBA8)
	for y in rows.size():
		for x in rows[y].length():
			img.set_pixel(x, y, pal[rows[y][x]])
	return ImageTexture.create_from_image(img)

func _ready() -> void:
	var s := Sprite2D.new()
	s.texture = texture_from(HERO, PAL)
	s.texture_filter = CanvasItem.TEXTURE_FILTER_NEAREST
	s.position = Vector2(40, 40)
	add_child(s)
```

## Validation
`visual check --style pixel`: no "exact colours" or "soft edges" warnings.
