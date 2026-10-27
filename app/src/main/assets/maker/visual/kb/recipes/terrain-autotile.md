title: Procedural terrain with autotiling
tags: terrain tilemap autotile tiles map generation noise biome grass sand water transitions seams procedural world strategy
styles: pixel flat painted
cost: low
engine: godot4
summary: generate a map from noise and paint it with TileMapLayer so transitions between materials are automatic

## Purpose
Large varied maps (strategy, exploration) with clean edges between grass, sand, water, rock.

## Methods
- Height/moisture from `FastNoiseLite` → biome per cell.
- Draw with `TileMapLayer.set_cells_terrain_connect()` (terrain sets do the transitions) when you have a
  tileset with terrain peering bits; otherwise generate the tiles' edges yourself (below: corner-based
  "marching squares" with 16 variants drawn in code).
- One TileMapLayer per material layer (water, ground, decoration) → few draw calls (one atlas each).

## Inputs / Outputs
Map size, seed, biome thresholds → a TileMapLayer (or an Image/texture for very large static maps).

## Code
```gdscript
extends Node2D
# A noise map drawn as coloured tiles with soft corner transitions, no tileset needed:
# each cell's 4 corners decide its shape (marching squares), so coasts are smooth, not blocky.
@export var cells := Vector2i(48, 80)
@export var cell := 16
var noise := FastNoiseLite.new()
const LAND := Color("6fa36b")
const SAND := Color("e8c98a")
const WATER := Color("3f6e8c")

func _ready() -> void:
	noise.seed = 7
	noise.frequency = 0.045
	noise.fractal_octaves = 4

func height(c: Vector2i) -> float:
	return noise.get_noise_2d(c.x, c.y)

func _draw() -> void:
	draw_rect(Rect2(Vector2.ZERO, Vector2(cells * cell)), WATER)
	for level in [[-0.05, SAND], [0.05, LAND]]:
		for y in cells.y:
			for x in cells.x:
				var corners := [Vector2i(x, y), Vector2i(x + 1, y), Vector2i(x + 1, y + 1), Vector2i(x, y + 1)]
				var poly := PackedVector2Array()
				for i in 4:
					var a: Vector2i = corners[i]
					var b: Vector2i = corners[(i + 1) % 4]
					var ha := height(a)
					var hb := height(b)
					if ha >= level[0]:
						poly.append(Vector2(a * cell))
					if (ha >= level[0]) != (hb >= level[0]):
						var t: float = (level[0] - ha) / (hb - ha)
						poly.append(Vector2(a * cell).lerp(Vector2(b * cell), t))
				# Edge points can coincide with corners: skip shapes that don't triangulate.
				if poly.size() >= 3 and not Geometry2D.triangulate_polygon(poly).is_empty():
					draw_colored_polygon(poly, level[1])
```
For pixel/tile art with a real tileset: create a `TileSet` with a terrain set (match corners and sides),
paint the cells of each biome into an Array[Vector2i], then
`layer.set_cells_terrain_connect(cells_of_biome, 0, terrain_index)`.

## Validation
`visual render`; in the sheet coasts must be continuous (no single-cell checkerboard islands: raise the
noise frequency threshold or remove islands smaller than 3 cells). draw_calls stay low (one per layer).
For big maps draw once into a `SubViewport` or cache: `_draw` runs only when `queue_redraw()` is called.

## Adaptation
- Pixel: tile size 8–16, nearest filter, terrain tileset with hand-drawn edges.
- Painted: add a blend shader at the borders (`recipes/terrain-blend-shader`).
- Low-poly 3D: the same heights into a mesh (`recipes/procedural-mesh-3d`).
