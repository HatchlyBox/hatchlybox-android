title: Lightweight 3D scenes that look good on phones
tags: 3d low-poly lighting environment fog sky tonemap glow materials camera scene racing exploration
styles: low-poly realistic
cost: high
engine: godot4
summary: gl_compatibility 3D with one sun, sky, depth fog, tonemap and vertex-coloured low-poly materials

## Rules
- Renderer `gl_compatibility`; 1 `DirectionalLight3D` (shadows only if the budget allows: one cascade,
  `directional_shadow_max_distance` small), a few `OmniLight3D` without shadows.
- `WorldEnvironment`: `ProceduralSkyMaterial` sky, ambient from sky, tonemap Filmic/ACES, glow on with a
  low intensity, depth fog for distance (hides the far plane, adds atmosphere).
- Materials: few StandardMaterial3D shared by many meshes; vertex colours instead of textures for low-poly;
  `shading_mode = UNSHADED` for far/background things.
- Geometry budget from `visual device`; LOD by swapping meshes, or fewer segments on primitives.

## Code
```gdscript
extends Node3D
# A complete atmospheric 3D scene: sky, sun, fog, glow, ground and low-poly trees.
func _ready() -> void:
	var env := Environment.new()
	var sky := Sky.new()
	var psky := ProceduralSkyMaterial.new()
	psky.sky_top_color = Color("1f3b54")
	psky.sky_horizon_color = Color("f3b37a")
	psky.ground_horizon_color = Color("f3b37a")
	sky.sky_material = psky
	env.background_mode = Environment.BG_SKY
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
	env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	env.glow_enabled = true
	env.glow_intensity = 0.4
	env.fog_enabled = true
	env.fog_light_color = Color("f3b37a")
	env.fog_density = 0.006
	env.fog_sky_affect = 0.0
	var we := WorldEnvironment.new()
	we.environment = env
	add_child(we)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-35, 40, 0)
	sun.light_color = Color("ffe2b8")
	add_child(sun)
	var ground := MeshInstance3D.new()
	var plane := PlaneMesh.new()
	plane.size = Vector2(200, 200)
	ground.mesh = plane
	var gm := StandardMaterial3D.new()
	gm.albedo_color = Color("4f7a4a")
	ground.material_override = gm
	add_child(ground)
	var leaf := StandardMaterial3D.new()
	leaf.albedo_color = Color("2e5b3f")
	var cone := CylinderMesh.new()
	cone.top_radius = 0.0
	cone.bottom_radius = 1.2
	cone.height = 3.0
	cone.radial_segments = 6
	var mm := MultiMesh.new()
	mm.transform_format = MultiMesh.TRANSFORM_3D
	mm.mesh = cone
	mm.instance_count = 400
	var rng := RandomNumberGenerator.new()
	for i in mm.instance_count:
		var p := Vector3(rng.randf_range(-60, 60), 1.5, rng.randf_range(-80, 10))
		mm.set_instance_transform(i, Transform3D(Basis.from_scale(Vector3.ONE * rng.randf_range(0.7, 1.6)), p))
	var forest := MultiMeshInstance3D.new()
	forest.multimesh = mm
	forest.material_override = leaf
	add_child(forest)
	var cam := Camera3D.new()
	cam.position = Vector3(0, 6, 18)
	add_child(cam)
	cam.look_at(Vector3(0, 2, -20))
```

## Validation
`visual render`: stats draw_calls/primitives against the 3D budget; `visual check` should show depth
(fog gradient) and no "mostly black" (ambient light missing).

## Adaptation
Toon: `StandardMaterial3D.diffuse_mode = DIFFUSE_TOON`, outlines via an inverted-hull second material
(`cull_mode = CULL_FRONT`, grow). Realistic-lite: textures + roughness, baked lightmaps are not available
headless: keep to sky ambient + sun.
