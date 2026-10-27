title: Enormous armies and crowds
tags: army armies crowd units soldiers battle swarm boids flocking thousands strategy rts multimesh simulation
styles: all
cost: medium
engine: godot4
summary: simulate thousands of units in packed arrays and draw them with one MultiMesh, with formation and sway

## Method
No node per unit. Positions/velocities/teams live in `PackedVector2Array`s; one `_physics_process`
loop steers them (seek target + separation from a spatial hash); a `MultiMeshInstance2D` draws all
(colour per team, small bob animation). 2000–5000 units at 30–60 fps on mid phones in GDScript if the
loop is simple; beyond that, lower the update rate (half the units per frame).

## Code
```gdscript
extends MultiMeshInstance2D
# Two armies marching to battle: packed arrays + a spatial grid for separation, drawn as one MultiMesh.
@export var per_team := 800
var pos := PackedVector2Array()
var vel := PackedVector2Array()
var team := PackedByteArray()
const CELL := 24.0
const COLORS := [Color("3f6e8c"), Color("b5523b")]

func _ready() -> void:
	var rng := RandomNumberGenerator.new()
	for t in 2:
		for i in per_team:
			pos.append(Vector2(rng.randf_range(80, 640), (200.0 if t == 0 else 1080.0) + rng.randf_range(-120, 120)))
			vel.append(Vector2.ZERO)
			team.append(t)
	var quad := QuadMesh.new()
	quad.size = Vector2(6, 10)
	var mm := MultiMesh.new()
	mm.transform_format = MultiMesh.TRANSFORM_2D
	mm.use_colors = true
	mm.mesh = quad
	mm.instance_count = pos.size()
	multimesh = mm

func _physics_process(delta: float) -> void:
	var grid := {}
	for i in pos.size():
		var k := Vector2i(pos[i] / CELL)
		if not grid.has(k):
			grid[k] = PackedInt32Array()
		grid[k].append(i)
	var t := Time.get_ticks_msec() * 0.001
	for i in pos.size():
		var goal := Vector2(pos[i].x, 640.0)
		var steer := (goal - pos[i]).limit_length(1.0) * 40.0
		var k := Vector2i(pos[i] / CELL)
		for dy in range(-1, 2):
			for dx in range(-1, 2):
				for j in grid.get(k + Vector2i(dx, dy), PackedInt32Array()):
					var d := pos[i] - pos[j]
					var l := d.length_squared()
					if j != i and l < 100.0 and l > 0.01:
						steer += d / l * 400.0
		vel[i] = vel[i].lerp(steer, 0.1)
		pos[i] += vel[i] * delta
		var bob := sin(t * 10.0 + i) * 1.5
		multimesh.set_instance_transform_2d(i, Transform2D(0.0, pos[i] + Vector2(0, bob)))
		multimesh.set_instance_color(i, COLORS[team[i]])
```

## Validation
`visual render --at 2,5`: stats nodes stay low (one node for the army), draw_calls ~1; look for clumping
(separation too weak) or jitter (too strong). fps here is software: judge cost by the loop, and keep
per_team × 2 ≤ the tier's sprite budget from `visual device`.

## Adaptation
Readable units: a 2–3 frame walk cycle from an atlas via `INSTANCE_CUSTOM` and a shader selecting the
frame; banners/leaders as real nodes on top. 3D: MultiMeshInstance3D with billboarded quads.
