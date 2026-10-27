title: Isometric 2.5D worlds
tags: isometric 2.5d strategy city builder tiles y-sort depth sorting diamond grid elevation heroes map
styles: pixel flat painted
cost: low
engine: godot4
summary: diamond-grid maps with y-sorted objects and elevation for a 3D feel at 2D cost

## Method
- Grid ↔ screen: `screen = Vector2((x - y) * tw / 2, (x + y) * th / 2)` with tile width tw = 2 × th.
- Ground drawn first (TileMapLayer with an isometric TileSet, or `_draw` of diamonds).
- Objects (trees, buildings, units) in one parent with `y_sort_enabled = true`, origins at their feet.
- Elevation: shift up by height × th/2 and draw the visible side faces darker.
- Light from one side: left faces lighter, right faces darker.

## Code
```gdscript
extends Node2D
# An isometric island with elevation: diamond tops and shaded side faces, drawn back to front.
@export var size := 20
@export var tw := 48.0
var noise := FastNoiseLite.new()

func iso(x: float, y: float, h: float = 0.0) -> Vector2:
	return Vector2((x - y) * tw / 2.0, (x + y) * tw / 4.0 - h * tw / 4.0)

func _ready() -> void:
	noise.frequency = 0.12
	position = Vector2(360, 300)

func _draw() -> void:
	for s in range(0, size * 2 - 1):
		for x in range(size):
			var y := s - x
			if y < 0 or y >= size:
				continue
			var hv := noise.get_noise_2d(x, y)
			if hv < -0.2:
				draw_colored_polygon(PackedVector2Array([iso(x, y), iso(x + 1, y), iso(x + 1, y + 1), iso(x, y + 1)]), Color("3f6e8c"))
				continue
			var h := floorf((hv + 0.2) * 6.0)
			var top := Color("6fa36b") if h < 3 else Color("9a8f7a")
			draw_colored_polygon(PackedVector2Array([iso(x, y + 1, h), iso(x + 1, y + 1, h), iso(x + 1, y + 1), iso(x, y + 1)]), top.darkened(0.25))
			draw_colored_polygon(PackedVector2Array([iso(x + 1, y, h), iso(x + 1, y + 1, h), iso(x + 1, y + 1), iso(x + 1, y)]), top.darkened(0.45))
			draw_colored_polygon(PackedVector2Array([iso(x, y, h), iso(x + 1, y, h), iso(x + 1, y + 1, h), iso(x, y + 1, h)]), top)
```

## Validation
Render: no face drawn over one nearer to the camera (draw order back to front), consistent light side.
Units must stand on tiles: their y-sort origin at the feet.

## Adaptation
Pixel: tw 32, th 16, 2:1 pixel lines, nearest filter. Painted: tile textures with baked light.
Heroes/strategy maps: add props by biome with `recipes/vegetation-instancing` and castles from
`recipes/procedural-buildings` (or baked via `recipes/prerender-sprites`).
