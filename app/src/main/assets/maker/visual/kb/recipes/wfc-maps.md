title: Wave Function Collapse: varied, coherent procedural maps and towns
tags: wave function collapse wfc procedural generation map level town dungeon tiles adjacency rules random world generation strategy roguelike
styles: all
cost: low
engine: godot4
summary: tiles with "may touch" rules; repeatedly fix the most constrained cell and propagate, giving maps that are random yet always look designed

## Method
Pure noise makes blobs; hand-made maps don't scale. WFC (Maxim Gumin; used in Townscaper, Bad North,
Caves of Qud) picks tiles so that every neighbour pair follows adjacency rules:
1. Every cell starts with all tiles possible.
2. Pick the cell with the fewest options left (ties: random); choose one of its options, weighted.
3. Propagate: remove options from neighbours that can no longer touch it, repeatedly (a stack).
4. Repeat until all cells are fixed. On a contradiction (a cell with no options), restart with a new
   seed (simple and fine for small maps) or backtrack.
- Rules here are "simple tiled": which terrain may sit next to which (water–sand–grass–forest–
  mountain). Add weights for the mix you want, and pre-set cells (castle positions, a river mouth)
  before collapsing to guarantee gameplay structure. Weights act at every step, and preset cells
  are solved outward first: preset water edges with a heavy water weight flood the map ring by ring,
  so keep border tiles' weights low and let the interior tiles' weights shape the mix.
- Run it once at map generation (a few ms for 30×50 in GDScript); keep the seed in the save file.
- Overlapping WFC (learning rules from a sample image) gives richer results but is heavier.

## Code
```gdscript
# res://wfc.gd
extends RefCounted
# Simple tiled WFC on a grid. tiles: names; allowed[a] = tiles that may be next to a; weights per tile.

var w: int
var h: int
var tiles: Array
var allowed: Dictionary
var weights: Dictionary
var cells: Array = []
var rng := RandomNumberGenerator.new()

func _init(width: int, height: int, tile_names: Array, adjacency: Dictionary, tile_weights: Dictionary) -> void:
	w = width
	h = height
	tiles = tile_names
	allowed = adjacency
	weights = tile_weights

func solve(seed_value: int, preset: Dictionary = {}) -> Array:
	for attempt in 50:
		rng.seed = seed_value + attempt
		if _run(preset):
			return cells.map(func(c): return c[0])
	return []

func _run(preset: Dictionary) -> bool:
	cells = []
	for i in w * h:
		cells.append(tiles.duplicate())
	for i in preset:
		cells[i] = [preset[i]]
		if not _propagate(i):
			return false
	while true:
		var best := -1
		var fewest := 1 << 30
		for i in cells.size():
			var n: int = cells[i].size()
			if n == 0:
				return false
			if n > 1 and n + rng.randf() < fewest:
				fewest = n
				best = i
		if best == -1:
			return true
		cells[best] = [_pick(cells[best])]
		if not _propagate(best):
			return false
	return true

func _pick(options: Array) -> String:
	var total := 0.0
	for t in options:
		total += weights.get(t, 1.0)
	var r := rng.randf() * total
	for t in options:
		r -= weights.get(t, 1.0)
		if r <= 0.0:
			return t
	return options[-1]

func _propagate(start: int) -> bool:
	var stack := [start]
	while not stack.is_empty():
		var i: int = stack.pop_back()
		var x := i % w
		var y := i / w
		for d: Vector2i in [Vector2i(1, 0), Vector2i(-1, 0), Vector2i(0, 1), Vector2i(0, -1)]:
			var nx := x + d.x
			var ny := y + d.y
			if nx < 0 or ny < 0 or nx >= w or ny >= h:
				continue
			var j := ny * w + nx
			var ok := {}
			for t in cells[i]:
				for a in allowed[t]:
					ok[a] = true
			var kept: Array = cells[j].filter(func(t): return ok.has(t))
			if kept.size() < cells[j].size():
				if kept.is_empty():
					return false
				cells[j] = kept
				stack.append(j)
	return true
```
```gdscript
# res://demo.gd
extends Node2D
# A 30x52 island map: water, sand, grass, forest, hills, mountains; a castle preset in the middle.

# preload, not class_name: a class_name only registers after the editor has imported the project.
const WFC := preload("res://wfc.gd")
const COLORS := {"water": Color("#2f6f9f"), "sand": Color("#e3cf8f"), "grass": Color("#7fb55a"),
	"forest": Color("#2f6b3a"), "hills": Color("#9a9a5a"), "mountain": Color("#8a8580"), "castle": Color("#d94f3d")}
var grid: Array = []
const W := 30
const H := 52

func _ready() -> void:
	var adj := {
		"water": ["water", "sand"],
		"sand": ["water", "sand", "grass"],
		"grass": ["sand", "grass", "forest", "hills", "castle"],
		"forest": ["grass", "forest", "hills"],
		"hills": ["grass", "forest", "hills", "mountain"],
		"mountain": ["hills", "mountain"],
		"castle": ["grass"],
	}
	var weights := {"water": 0.35, "sand": 1.0, "grass": 6.0, "forest": 5.0, "hills": 2.5, "mountain": 2.0, "castle": 0.0}
	var preset := {}
	for x in W:
		preset[x] = "water"
		preset[(H - 1) * W + x] = "water"
	for y in H:
		preset[y * W] = "water"
		preset[y * W + W - 1] = "water"
	preset[(H / 2) * W + W / 2] = "castle"
	var wfc := WFC.new(W, H, adj.keys(), adj, weights)
	grid = wfc.solve(42, preset)
	queue_redraw()

func _draw() -> void:
	draw_rect(Rect2(0, 0, 720, 1280), Color("#1d3c5a"))
	if grid.is_empty():
		return
	var s := 24.0
	for i in grid.size():
		draw_rect(Rect2((i % W) * s, (i / W) * s + 16, s - 1, s - 1), COLORS[grid[i]])
```

## Validation
A coherent island: water at the edges, beaches between water and land, forests and hills grouped,
mountains inside hills, the castle on grass. Never water touching grass directly.

## Adaptation
Strategy maps: preset castles and towns at fair distances first, then collapse. Towns: tiles = road,
house, plaza, wall, with rules for streets. Dungeons: room/corridor tiles with connection rules per
side (use per-direction rules: allowed[t][dir]). Hex maps: 6 neighbours instead of 4. Render the tiles
with the terrain recipes (autotile, blend shader, triplanar terrain in 3D).
