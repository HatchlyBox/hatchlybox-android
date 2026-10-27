title: Depth and height fog, aerial perspective (cinematic 3D depth)
tags: fog height fog depth aerial perspective atmosphere mist valley haze distance cinematic 3d environment sun scatter layers mountains
styles: realistic painted low-poly toon
cost: low
engine: godot4
summary: Environment fog with height falloff and sun scattering plus a sky-coloured fade makes distance read as layers, the cheapest way to make 3D look cinematic

## Method
Distant things lose contrast and take the sky's colour (aerial perspective). It's how painters and
film give depth, and it hides the far clipping plane.
- `Environment.fog_enabled` with `fog_density` for distance, `fog_height` + `fog_height_density` so
  valleys fill with mist while peaks rise above it, `fog_sun_scatter` for a glow toward the sun,
  `fog_aerial_perspective` to blend toward the sky colour. All supported by the compatibility renderer.
- Fog colour = the sky's horizon colour, lighter than the scene: layers then separate by value
  (foreground dark and contrasted, background pale), like Caspar David Friedrich's valleys.
- Stage depth: place objects in 3–4 distance bands (foreground framing, the action, mid ridges, far
  peaks), so the fog has something to separate.
- Volumetric fog is not available in the compatibility renderer: fake beams with the fog-2d recipe's
  light shafts on a CanvasLayer, or additive quads.

## Code
```gdscript
# res://demo.gd
extends Node3D
# Ridges of trees in 4 depth bands; height fog fills the valley, the far ridges fade into the sky.

func ridge(z: float, count: int, rng: RandomNumberGenerator, color: Color) -> void:
	var mat := StandardMaterial3D.new()
	mat.albedo_color = color
	for i in count:
		var t := MeshInstance3D.new()
		t.mesh = CylinderMesh.new()
		t.mesh.top_radius = 0.0
		t.mesh.bottom_radius = rng.randf_range(0.8, 1.4)
		t.mesh.height = rng.randf_range(3.0, 6.0)
		t.mesh.radial_segments = 7
		t.material_override = mat
		var x := rng.randf_range(-60, 60)
		t.position = Vector3(x, t.mesh.height * 0.5 + sin(x * 0.05 + z) * 3.0, z + rng.randf_range(-4, 4))
		add_child(t)

func _ready() -> void:
	var env := WorldEnvironment.new()
	var e := Environment.new()
	e.background_mode = Environment.BG_COLOR
	e.background_color = Color("#c9d6e0")
	e.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	e.ambient_light_color = Color("#6a7684")
	e.fog_enabled = true
	e.fog_light_color = Color("#c9d6e0")
	e.fog_density = 0.006
	e.fog_sun_scatter = 0.15
	e.fog_aerial_perspective = 0.25
	e.fog_height = 1.5
	e.fog_height_density = 0.08
	e.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	env.environment = e
	add_child(env)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-25, -60, 0)
	sun.light_color = Color("#ffe2b8")
	add_child(sun)
	var ground := MeshInstance3D.new()
	ground.mesh = PlaneMesh.new()
	ground.mesh.size = Vector2(400, 400)
	var gm := StandardMaterial3D.new()
	gm.albedo_color = Color("#3c5a3a")
	ground.material_override = gm
	add_child(ground)
	var rng := RandomNumberGenerator.new()
	rng.seed = 7
	for band in [[-6.0, 25], [-30.0, 40], [-65.0, 50], [-120.0, 60]]:
		ridge(band[0], band[1], rng, Color("#24402c"))
	var cam := Camera3D.new()
	cam.position = Vector3(0, 6, 8)
	cam.far = 300
	add_child(cam)
	cam.look_at(Vector3(0, 3, -60))
```

## Validation
Four clearly separated layers of trees, each paler than the one in front; the valley floor misty, tree
tops above it clearer. `visual check`: value range stays wide (dark foreground), no "low contrast"
warning over the whole image; if there is one, lower density or darken the foreground.

## Adaptation
Morning valley: low, dense height fog, warm sun scatter. Horror: dark green-grey fog, high density,
short range. Desert: thin warm haze, high aerial perspective. Toon: fog colour from the palette, fewer
layers. Strategy maps: light fog only toward the horizon (fog_density low), keep the battlefield crisp.
