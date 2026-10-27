title: Living grass and trees: wind in the vertex shader (3D)
tags: wind grass meadow foliage trees sway gust vertex shader multimesh 3d lush nature field vegetation animation
styles: realistic painted low-poly toon
cost: low
engine: godot4
summary: thousands of grass blades in one MultiMesh, bent by a travelling gust wave and per-blade noise in the vertex shader, coloured root to tip

## Method
- One blade mesh (a thin triangle strip built in code), thousands of instances in a
  `MultiMeshInstance3D`: one draw call. Scatter with random rotation and scale; denser near the camera.
- The vertex shader bends each blade by **height²** (the root stays planted): a slow global sway, a
  **gust wave** travelling across the field (a sine of `world position · wind direction − time`), and
  a little per-blade flutter from the instance position. All on the GPU.
- Colour from root (dark, fake ambient occlusion) to tip (light, sun-catching) by height: depth and
  lushness without lights or textures.
- Trees: the same bend on branch/leaf meshes, weighted by vertex colour or height, slower and smaller.
- Budget: 5–20k blades on mid phones; fade or thin with distance (visibility_range, a second sparser
  MultiMesh).

## Code
```gdshader
// res://grass.gdshader
shader_type spatial;
render_mode cull_disabled;
// Wind: sway + travelling gusts + flutter, weighted by blade height; root-to-tip colour.
uniform vec4 root_color : source_color = vec4(0.1, 0.25, 0.1, 1.0);
uniform vec4 tip_color : source_color = vec4(0.6, 0.85, 0.35, 1.0);
uniform vec2 wind_dir = vec2(1.0, 0.3);
uniform float strength = 0.35;
varying float h;

void vertex() {
	h = clamp(VERTEX.y, 0.0, 1.0);
	// MODEL_MATRIX includes the instance's transform: its origin is the blade's world position.
	vec3 ip = MODEL_MATRIX[3].xyz;
	vec2 d = normalize(wind_dir);
	float gust = sin(dot(ip.xz, d) * 0.25 - TIME * 2.2) * 0.5 + 0.5;
	float flutter = sin(TIME * 7.0 + ip.x * 3.1 + ip.z * 2.3) * 0.15;
	float bend = (0.3 + gust * 0.9 + flutter) * strength * h * h;
	// The wind blows in world space; turn it into the blade's own (rotated, scaled) space.
	vec3 local_wind = inverse(mat3(MODEL_MATRIX)) * vec3(d.x, 0.0, d.y);
	VERTEX.xz += local_wind.xz * bend;
	VERTEX.y -= bend * bend * 0.3;
}

void fragment() {
	ALBEDO = mix(root_color.rgb, tip_color.rgb, h);
	ROUGHNESS = 0.8;
}
```
```gdscript
# res://demo.gd
extends Node3D
# 12000 blades, one draw call; each blade's wind phase comes from its own position.

func blade() -> ArrayMesh:
	var st := SurfaceTool.new()
	st.begin(Mesh.PRIMITIVE_TRIANGLES)
	var w := 0.06
	var pts := [Vector3(-w, 0, 0), Vector3(w, 0, 0), Vector3(-w * 0.6, 0.5, 0), Vector3(w * 0.6, 0.5, 0), Vector3(0, 1.0, 0)]
	for t in [[0, 1, 2], [1, 3, 2], [2, 3, 4]]:
		for i in t:
			st.set_normal(Vector3(0, 0, 1))
			st.add_vertex(pts[i])
	return st.commit()

func _ready() -> void:
	var mm := MultiMesh.new()
	mm.transform_format = MultiMesh.TRANSFORM_3D
	mm.mesh = blade()
	mm.instance_count = 12000
	var rng := RandomNumberGenerator.new()
	for i in mm.instance_count:
		var p := Vector3(rng.randf_range(-12, 12), 0, -pow(rng.randf(), 1.6) * 30.0)
		var s := rng.randf_range(0.5, 1.0)
		mm.set_instance_transform(i, Transform3D(Basis(Vector3.UP, rng.randf() * TAU).scaled(Vector3(s, s, s)), p))
	var mi := MultiMeshInstance3D.new()
	mi.multimesh = mm
	var mat := ShaderMaterial.new()
	mat.shader = load("res://grass.gdshader")
	mi.material_override = mat
	add_child(mi)
	var ground := MeshInstance3D.new()
	ground.mesh = PlaneMesh.new()
	ground.mesh.size = Vector2(60, 80)
	var gm := StandardMaterial3D.new()
	gm.albedo_color = Color("#1f3a1c")
	ground.material_override = gm
	add_child(ground)
	var env := WorldEnvironment.new()
	env.environment = Environment.new()
	env.environment.background_mode = Environment.BG_COLOR
	env.environment.background_color = Color("#bcd8e8")
	env.environment.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.environment.ambient_light_color = Color("#5a6a5a")
	add_child(env)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-35, -40, 0)
	add_child(sun)
	var cam := Camera3D.new()
	cam.position = Vector3(0, 1.6, 3)
	add_child(cam)
	cam.look_at(Vector3(0, 0.4, -8))
```

## Validation
A dense meadow, dark at the roots and bright at the tips, blades leaning with the wind; two shots
1 s apart differ along a travelling band (the gust). Stats: one draw call for all blades.

## Adaptation
Painterly: fewer, wider blades with a painted tip colour; bigger gusts. Toon: 2-band colour, no
flutter. Pixel-art 3D: fewer, chunkier blades inside the low-res viewport. Trees and banners: the same
bend with height weighting, slower (TIME * 0.8). Battles: flatten grass near units (pass unit
positions as uniforms) for a satisfying trail.
