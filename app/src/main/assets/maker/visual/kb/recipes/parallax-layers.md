title: Parallax depth layers
tags: parallax background layers depth scrolling mountains clouds sky cinematic atmosphere side scroller
styles: all
cost: low
engine: godot4
summary: 3-5 layers moving at different speeds with atmospheric colour fade for instant depth

## Method
`Parallax2D` nodes (Godot 4.3+) with `scroll_scale` < 1 for far layers and `repeat_size` for endless
scrolling. Far layers lighter/less saturated (atmospheric perspective), near layers darker and sharper.

## Code
```gdscript
extends Node2D
# Procedural mountain layers: each a Polygon2D ridge, coloured toward the sky with distance.
@export var sky := Color("f4b860")
@export var near := Color("2b2340")
@export var layers := 4
@export var width := 1440.0

func _ready() -> void:
	var rng := RandomNumberGenerator.new()
	rng.seed = 3
	for i in layers:
		var depth := float(i) / float(layers - 1)
		var p := Parallax2D.new()
		p.scroll_scale = Vector2(lerpf(0.1, 0.9, depth), 1.0)
		p.repeat_size = Vector2(width, 0)
		p.repeat_times = 3
		add_child(p)
		var ridge := Polygon2D.new()
		var pts := PackedVector2Array()
		var base := lerpf(520.0, 760.0, depth)
		var steps := 24
		for s in steps + 1:
			var x := width * s / steps
			var y := base - absf(sin(s * 0.7 + i)) * lerpf(160.0, 90.0, depth) - rng.randf() * 30.0
			pts.append(Vector2(x, y))
		pts.append(Vector2(width, 1400))
		pts.append(Vector2(0, 1400))
		ridge.polygon = pts
		ridge.color = sky.lerp(near, lerpf(0.25, 1.0, depth))
		p.add_child(ridge)
```

## Validation
Render two shots while the camera moves (or `--at 1,3` in a scrolling scene) and `--against`: far
layers change less than near ones. One draw call per layer.

## Adaptation
Pixel: snap points to integers, nearest filter. Painted: Sprite2D layers with soft textures and a fog
band (`recipes/fog-2d`) between them. Night: darker ramp + stars on the farthest layer.
