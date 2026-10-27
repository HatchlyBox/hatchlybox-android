title: Procedural buildings and castles in 2D
tags: buildings castle towers houses village city architecture medieval fantasy procedural polygon roofs windows
styles: flat painted pixel
cost: low
engine: godot4
summary: compose castles and houses from parametric parts (walls, towers, roofs, windows) with consistent light

## Method
Parts with parameters (width, height, roof type, palette, light side) drawn with `_draw()` or Polygon2D,
so a whole village varies by seed but stays in one style. Shade every part the same way: lit side
lighter, shadow side darker (light from the top-left), a darker base for grounding, windows lit at night.

## Code
```gdscript
extends Node2D
# A castle from parts: keep, towers with cone roofs, crenellations, windows, gate. Seeded variety.
@export var seed_value := 4
@export var wall := Color("c9bfa5")
@export var roof := Color("b5523b")
@export var night := false
var rng := RandomNumberGenerator.new()

func _ready() -> void:
	rng.seed = seed_value

func shade(c: Color, k: float) -> Color:
	return c.darkened(k) if k > 0.0 else c.lightened(-k)

func block(r: Rect2) -> void:
	draw_rect(r, wall)
	draw_rect(Rect2(r.position + Vector2(r.size.x * 0.65, 0), Vector2(r.size.x * 0.35, r.size.y)), shade(wall, 0.18))
	draw_rect(Rect2(r.position + Vector2(0, r.size.y - 6), Vector2(r.size.x, 6)), shade(wall, 0.35))

func crenels(x: float, y: float, w: float) -> void:
	var n := int(w / 14.0)
	for i in n:
		if i % 2 == 0:
			draw_rect(Rect2(x + i * w / n, y - 10, w / n, 10), wall)

func tower(x: float, base: float, w: float, h: float) -> void:
	block(Rect2(x, base - h, w, h))
	var top := base - h
	draw_colored_polygon(PackedVector2Array([Vector2(x - 6, top), Vector2(x + w / 2, top - w * 1.2), Vector2(x + w / 2, top)]), shade(roof, -0.1))
	draw_colored_polygon(PackedVector2Array([Vector2(x + w / 2, top), Vector2(x + w / 2, top - w * 1.2), Vector2(x + w + 6, top)]), shade(roof, 0.2))
	for i in int(h / 60.0):
		window(Vector2(x + w / 2 - 5, top + 30 + i * 60))

func window(p: Vector2) -> void:
	var lit := night and rng.randf() < 0.7
	draw_rect(Rect2(p, Vector2(10, 16)), Color("ffd27a") if lit else shade(wall, 0.6))

func _draw() -> void:
	var base := 600.0
	var keep_w := 220.0
	block(Rect2(-keep_w / 2, base - 180, keep_w, 180))
	crenels(-keep_w / 2, base - 180, keep_w)
	draw_rect(Rect2(-22, base - 70, 44, 70), shade(wall, 0.7))
	for side in [-1, 1]:
		var w := rng.randf_range(50, 70)
		var h := rng.randf_range(240, 320)
		tower(side * (keep_w / 2 + 10) - (w if side < 0 else 0.0), base, w, h)
	var cw := rng.randf_range(60, 80)
	tower(-cw / 2, base - 180, cw, rng.randf_range(120, 180))
```

## Validation
Render day and night (`night = true`): silhouettes readable against the sky (`visual check` contrast),
light consistent on every part, windows on at night.

## Adaptation
Pixel: integer sizes, nearest filter, 1 px outlines (`draw_rect(r, color, false, 1.0)`).
Painted/realistic: bake the castle once into a texture (`recipes/prerender-sprites`) and add
texture noise and a soft ambient-occlusion gradient at the base. City: same parts, other roofs.
