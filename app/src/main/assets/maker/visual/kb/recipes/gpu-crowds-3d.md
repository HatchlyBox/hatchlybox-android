title: Thousands of animated 3D units in one draw call (vertex-animated MultiMesh)
tags: army armies crowd soldiers units 3d multimesh vertex animation texture vat march battle rts thousands instancing gpu
styles: low-poly toon realistic
cost: medium
engine: godot4
summary: one MultiMeshInstance3D for a whole army; the vertex shader animates each unit (march, bob, sway) from its instance data, so the CPU does nothing per frame

## Method
Nodes per soldier don't scale on phones. Put all units of one look in a `MultiMesh` (one draw call)
and animate them in the vertex shader:
- **Per-instance data** in `INSTANCE_CUSTOM` (`use_custom_data`): team colour in rgb, a phase in alpha,
  so units don't move in lockstep.
- **Procedural animation:** a walk bob, a lean and a leg/arm swing driven by `TIME + phase`, weighted
  by the vertex height (feet move, heads bob).
- **Moving the army:** update transforms only when formations change, or move the whole
  MultiMeshInstance3D; for per-unit movement update `set_instance_transform` for a slice of units per frame.
- **Real animations (VAT):** for skinned walk cycles, bake the animation once into a texture (rows =
  frames, columns = vertices, RGB = position) with a tool script, then read it in the vertex shader with
  `texelFetch` using `VERTEX_ID` and the frame. The shader below is the same idea without a bake.
- **LOD:** a second MultiMesh with billboards or a simpler mesh for far units (`visibility_range_*`).

## Code
```gdshader
// res://crowd.gdshader
shader_type spatial;
// Instance custom data: rgb = team colour, a = phase (0-1). Walk cycle in the vertex shader.
uniform float stride_speed = 7.0;
uniform float bob = 0.06;
uniform float swing = 0.12;
varying vec3 team;

void vertex() {
	float ph = INSTANCE_CUSTOM.a * 6.2831;
	float t = TIME * stride_speed + ph;
	float h = clamp(VERTEX.y + 0.5, 0.0, 1.0);
	VERTEX.y += abs(sin(t)) * bob;
	VERTEX.z += sin(t) * swing * (1.0 - h) * sign(VERTEX.x);
	VERTEX.z += sin(t * 0.5) * 0.04 * h;
	team = INSTANCE_CUSTOM.rgb;
}

void fragment() {
	ALBEDO = team;
	ROUGHNESS = 0.9;
}
```
```gdscript
# res://demo.gd
extends Node3D
# Two armies of 1800 units each, two draw calls.

func army(count: int, origin: Vector3, color: Color, facing: float) -> MultiMeshInstance3D:
	var body := BoxMesh.new()
	body.size = Vector3(0.25, 1.0, 0.18)
	var mm := MultiMesh.new()
	mm.transform_format = MultiMesh.TRANSFORM_3D
	mm.use_custom_data = true
	mm.mesh = body
	mm.instance_count = count
	var cols := 60
	var rng := RandomNumberGenerator.new()
	for i in count:
		var p := origin + Vector3((i % cols) * 0.45 - cols * 0.225, 0.5, (i / cols) * 0.55) + Vector3(rng.randf() * 0.12, 0, rng.randf() * 0.12)
		mm.set_instance_transform(i, Transform3D(Basis(Vector3.UP, facing), p))
		var c := color.darkened(rng.randf() * 0.25)
		mm.set_instance_custom_data(i, Color(c.r, c.g, c.b, rng.randf()))
	var mi := MultiMeshInstance3D.new()
	mi.multimesh = mm
	var mat := ShaderMaterial.new()
	mat.shader = load("res://crowd.gdshader")
	mi.material_override = mat
	return mi

func _ready() -> void:
	var env := WorldEnvironment.new()
	env.environment = Environment.new()
	env.environment.background_mode = Environment.BG_COLOR
	env.environment.background_color = Color("#9fb7c9")
	env.environment.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.environment.ambient_light_color = Color("#6a7080")
	add_child(env)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-55, 30, 0)
	add_child(sun)
	var ground := MeshInstance3D.new()
	ground.mesh = PlaneMesh.new()
	ground.mesh.size = Vector2(80, 80)
	var gm := StandardMaterial3D.new()
	gm.albedo_color = Color("#6d8a4a")
	ground.material_override = gm
	add_child(ground)
	add_child(army(1800, Vector3(0, 0, -22), Color("#c0392b"), 0.0))
	add_child(army(1800, Vector3(0, 0, 4), Color("#2e5fa8"), PI))
	var cam := Camera3D.new()
	cam.position = Vector3(0, 22, 26)
	add_child(cam)
	cam.look_at(Vector3(0, 0, -4))
```

## Validation
Two dense, readable blocks of units in team colours that move individually (not in lockstep).
Stats: draw calls stay ~2 per army regardless of size. Budget on mid phones: ~5000 low-poly units total.

## Adaptation
Toon: use the toon recipe's light() in this shader. Readability: give each unit type a distinct
silhouette (spear = tall thin box, cavalry = wide box) and a banner every 50 units. Cinematic: a low
camera in the dust (fog recipe), a slow dolly along the front line. Pixel: render into a low-res
viewport (pixel-art-3d recipe).
