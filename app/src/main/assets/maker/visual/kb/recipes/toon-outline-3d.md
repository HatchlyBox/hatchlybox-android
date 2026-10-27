title: Toon shading with outlines and rim light (anime, comic, cel-shaded 3D)
tags: toon cel shading cel-shaded anime comic outline inverted hull rim light stylised 3d ramp bands ink
styles: low-poly toon comic painted
cost: low
engine: godot4
summary: a light() function that quantises lighting into bands with a coloured shade and rim, plus an inverted-hull outline pass

## Method
Two parts, as in Guilty Gear Xrd-style or Zelda-style 3D:
- **Bands:** a custom `light()` turns N·L into 2–3 flat bands, with a coloured shadow (never grey) and
  a rim light that outlines the silhouette against the light.
- **Outline:** an inverted hull: the mesh again as a `next_pass`, pushed out along its normals, with
  front faces culled so only the back shows as a rim. Thickness in world units; scale it with distance
  for constant-width lines. Smooth normals give clean outlines; hard-edged meshes need their normals
  averaged, or the hull splits at corners.
Works in the compatibility renderer; one extra draw per outlined mesh.

## Code
```gdshader
// res://toon.gdshader
shader_type spatial;
// Cel shading: lighting in flat bands, a coloured shade and a rim light.
uniform vec4 albedo : source_color = vec4(0.9, 0.5, 0.4, 1.0);
uniform vec4 shade : source_color = vec4(0.35, 0.25, 0.5, 1.0);
uniform float bands = 2.0;
uniform float rim_amount = 0.35;
uniform vec4 rim_color : source_color = vec4(1.0, 0.95, 0.85, 1.0);

void fragment() {
	ALBEDO = albedo.rgb;
}

void light() {
	float ndl = clamp(dot(NORMAL, LIGHT), 0.0, 1.0) * ATTENUATION;
	float stepped = floor(ndl * bands + 0.5) / bands;
	vec3 lit = mix(shade.rgb, vec3(1.0), stepped);
	float rim = smoothstep(1.0 - rim_amount - 0.02, 1.0 - rim_amount, 1.0 - dot(NORMAL, VIEW)) * step(0.2, ndl);
	DIFFUSE_LIGHT += lit * LIGHT_COLOR / PI + rim * rim_color.rgb;
}
```
```gdshader
// res://outline.gdshader
shader_type spatial;
render_mode unshaded, cull_front;
// Inverted hull: the mesh pushed out along its normals, back faces only, one flat colour.
uniform vec4 line_color : source_color = vec4(0.08, 0.06, 0.1, 1.0);
uniform float thickness = 0.03;

void vertex() {
	VERTEX += NORMAL * thickness;
}

void fragment() {
	ALBEDO = line_color.rgb;
}
```
```gdscript
# res://demo.gd
extends Node3D

func toon(color: Color, shade: Color) -> ShaderMaterial:
	var m := ShaderMaterial.new()
	m.shader = load("res://toon.gdshader")
	m.set_shader_parameter("albedo", color)
	m.set_shader_parameter("shade", shade)
	var o := ShaderMaterial.new()
	o.shader = load("res://outline.gdshader")
	m.next_pass = o
	return m

func _ready() -> void:
	var env := WorldEnvironment.new()
	env.environment = Environment.new()
	env.environment.background_mode = Environment.BG_COLOR
	env.environment.background_color = Color("#bfe3f0")
	env.environment.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.environment.ambient_light_color = Color("#404060")
	add_child(env)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-50, -35, 0)
	sun.light_energy = 1.2
	add_child(sun)
	var cam := Camera3D.new()
	cam.position = Vector3(0, 2.2, 7)
	add_child(cam)
	cam.look_at(Vector3(0, 0.4, 0))
	var ground := MeshInstance3D.new()
	ground.mesh = PlaneMesh.new()
	ground.mesh.size = Vector2(20, 20)
	var gm := ShaderMaterial.new()
	gm.shader = load("res://toon.gdshader")
	gm.set_shader_parameter("albedo", Color("#8fd16a"))
	gm.set_shader_parameter("shade", Color("#3e7a5a"))
	gm.set_shader_parameter("rim_amount", 0.0)
	ground.material_override = gm
	ground.position.y = -1.0
	add_child(ground)
	var items := [[SphereMesh.new(), Color("#ff7a59"), Color("#7a3a6a")], [CapsuleMesh.new(), Color("#ffd166"), Color("#a0522d")],
		[TorusMesh.new(), Color("#5ec8f2"), Color("#2b4a8a")]]
	for i in items.size():
		var mi := MeshInstance3D.new()
		mi.mesh = items[i][0]
		mi.material_override = toon(items[i][1], items[i][2])
		mi.position = Vector3(i * 2.3 - 2.3, 0.1, 0)
		mi.rotation = Vector3(0.5, 0.4 * i, 0.2)
		add_child(mi)
```

## Validation
Flat bands with a crisp edge, coloured (not grey) shade, a thin dark outline all round each object, a
light rim on the lit side. If outlines break at corners, the mesh has split normals.

## Adaptation
Anime: 2 bands, warm skin shade, thin outline. Comic: 3 bands, thick black outline, add halftone dots
in the shade band (screen-space dot pattern). Children's book: no outline, soft band edge (widen the
smoothstep). Mobile budget: outline only characters and key props, not the terrain.
