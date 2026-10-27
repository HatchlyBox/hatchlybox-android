title: Impostors and visibility ranges: huge detailed worlds on a phone
tags: impostor billboard lod level of detail distant castles forest far objects visibility range hlod open world 3d big scene draw distance
styles: all
cost: low
engine: godot4
summary: render a detailed model once into a texture and show it as a camera-facing billboard far away; GeometryInstance3D visibility ranges swap model and impostor by distance

## Method
Far objects cover a few pixels but cost full draw calls and vertices. An impostor is a picture of the
model on a camera-facing quad:
- Render the detailed model once in a `SubViewport` (`own_world_3d`, transparent background,
  `UPDATE_ONCE`) and use its `ViewportTexture` on a `Sprite3D` with `BILLBOARD_FIXED_Y` (stays upright).
  Or pre-render to PNG with a tool script (prerender-sprites recipe) for zero runtime cost.
- **Swap by distance:** set `visibility_range_end` on the detailed mesh and `visibility_range_begin` on
  the impostor to the same distance, with `visibility_range_end_margin`/`begin_margin` and
  `visibility_range_fade_mode` for a soft cross-fade. Godot does the switching; no script.
- Many impostors of one kind → a `MultiMeshInstance3D` with a quad and a billboard shader, one draw call.
- Light direction is baked into the picture: render it with the scene's sun direction.

## Code
```gdscript
# res://demo.gd
extends Node3D
# A detailed keep near the camera; 60 more far away as impostors of the same model.

func keep() -> Node3D:
	var k := Node3D.new()
	var stone := StandardMaterial3D.new()
	stone.albedo_color = Color("#b8b0a0")
	var roof := StandardMaterial3D.new()
	roof.albedo_color = Color("#8a3b2e")
	for p in [Vector3(-1, 0, -1), Vector3(1, 0, -1), Vector3(-1, 0, 1), Vector3(1, 0, 1)]:
		var t := MeshInstance3D.new()
		t.mesh = CylinderMesh.new()
		t.mesh.top_radius = 0.45
		t.mesh.bottom_radius = 0.5
		t.mesh.height = 2.6
		t.material_override = stone
		t.position = p + Vector3(0, 1.3, 0)
		k.add_child(t)
		var r := MeshInstance3D.new()
		r.mesh = CylinderMesh.new()
		r.mesh.top_radius = 0.0
		r.mesh.bottom_radius = 0.6
		r.mesh.height = 0.9
		r.material_override = roof
		r.position = p + Vector3(0, 3.05, 0)
		k.add_child(r)
	var hall := MeshInstance3D.new()
	hall.mesh = BoxMesh.new()
	hall.mesh.size = Vector3(2, 1.8, 2)
	hall.material_override = stone
	hall.position.y = 0.9
	k.add_child(hall)
	return k

func lights(world: Node) -> void:
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-40, -30, 0)
	world.add_child(sun)

func _ready() -> void:
	var env := WorldEnvironment.new()
	env.environment = Environment.new()
	env.environment.background_mode = Environment.BG_COLOR
	env.environment.background_color = Color("#a9c4d8")
	env.environment.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.environment.ambient_light_color = Color("#707a88")
	add_child(env)
	lights(self)
	# The impostor picture: the same model in its own world, rendered once.
	var vp := SubViewport.new()
	vp.size = Vector2i(256, 256)
	vp.own_world_3d = true
	vp.transparent_bg = true
	vp.render_target_update_mode = SubViewport.UPDATE_ONCE
	add_child(vp)
	vp.add_child(keep())
	lights(vp)
	var vcam := Camera3D.new()
	vcam.projection = Camera3D.PROJECTION_ORTHOGONAL
	vcam.size = 4.4
	vcam.position = Vector3(0, 1.8, 8)
	vp.add_child(vcam)
	var near := keep()
	near.position = Vector3(0, 0, 2)
	for c in near.get_children():
		c.visibility_range_end = 40.0
	add_child(near)
	var rng := RandomNumberGenerator.new()
	for i in 60:
		var s := Sprite3D.new()
		s.texture = vp.get_texture()
		s.billboard = BaseMaterial3D.BILLBOARD_FIXED_Y
		s.alpha_cut = SpriteBase3D.ALPHA_CUT_DISCARD
		s.pixel_size = 4.4 / 256.0
		s.position = Vector3(rng.randf_range(-40, 40), 1.8, rng.randf_range(-90, -20))
		s.visibility_range_begin = 0.0
		add_child(s)
	var ground := MeshInstance3D.new()
	ground.mesh = PlaneMesh.new()
	ground.mesh.size = Vector2(200, 200)
	var gm := StandardMaterial3D.new()
	gm.albedo_color = Color("#7c9a55")
	ground.material_override = gm
	add_child(ground)
	var cam := Camera3D.new()
	cam.position = Vector3(0, 4, 9)
	add_child(cam)
	cam.look_at(Vector3(0, 2, -20))
```

## Validation
A detailed keep in front, a field of distant keeps behind that look the same but cost one quad each.
Stats: draw calls grow by ~1 per impostor (or 1 total with a MultiMesh), vertices barely at all.

## Adaptation
Forests: impostor tree clusters beyond 30 m, real trees near. Strategy maps: impostor buildings when
zoomed out, models when zoomed in. Pixel or painted styles: pre-render and paint over the impostor
PNGs. Keep the impostor camera's angle close to the game camera's pitch.
