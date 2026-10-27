title: 3D terrain with triplanar texturing and slope/height splatting
tags: terrain 3d landscape hills mountains triplanar splat splatmap slope height grass rock snow dirt heightmap texture blending procedural
styles: realistic painted low-poly
cost: medium
engine: godot4
summary: a heightmap mesh from noise; one shader picks grass, rock, snow and dirt by slope and height and samples textures triplanarly so cliffs never stretch

## Method
- **Mesh:** a grid from `FastNoiseLite` heights built with `SurfaceTool` (`generate_normals()`); 64–128
  cells per side on phones. Bigger worlds: chunks with lower resolution far away.
- **Splatting without a splat map:** the shader decides the material per pixel from the world normal
  (slope: steep = rock) and height (low = sand or dirt, high = snow), with noise on the borders so
  transitions look natural. A painted splat map (RGBA = 4 materials) can override where design matters.
- **Triplanar sampling:** sample each texture from the three world axes and blend by the normal, so
  steep faces aren't stretched. Use it for rock; flat materials can use plain top-down UVs (cheaper).
- Textures can be generated (`NoiseTexture2D`) or CC0 ones from Poly Haven (downscale to 512 px).

## Code
```gdshader
// res://terrain.gdshader
shader_type spatial;
// Material by slope and height, triplanar detail; colours in the palette.
uniform sampler2D detail : filter_linear_mipmap, repeat_enable;
uniform vec4 grass : source_color = vec4(0.36, 0.55, 0.25, 1.0);
uniform vec4 rock : source_color = vec4(0.45, 0.42, 0.4, 1.0);
uniform vec4 snow : source_color = vec4(0.93, 0.95, 1.0, 1.0);
uniform vec4 dirt : source_color = vec4(0.55, 0.45, 0.3, 1.0);
uniform float snow_line = 7.0;
uniform float scale = 0.15;
varying vec3 wpos;
varying vec3 wnrm;

void vertex() {
	wpos = (MODEL_MATRIX * vec4(VERTEX, 1.0)).xyz;
	wnrm = normalize((MODEL_MATRIX * vec4(NORMAL, 0.0)).xyz);
}

float tri(vec3 p, vec3 n) {
	vec3 w = pow(abs(n), vec3(4.0));
	w /= (w.x + w.y + w.z);
	return texture(detail, p.zy * scale).r * w.x + texture(detail, p.xz * scale).r * w.y + texture(detail, p.xy * scale).r * w.z;
}

void fragment() {
	float d = tri(wpos, wnrm);
	float slope = 1.0 - wnrm.y;
	vec3 c = mix(dirt.rgb, grass.rgb, smoothstep(0.6, 1.6, wpos.y + d));
	c = mix(c, rock.rgb, smoothstep(0.25, 0.4, slope + (d - 0.5) * 0.2));
	c = mix(c, snow.rgb, smoothstep(snow_line - 0.5, snow_line + 0.5, wpos.y + d * 2.0) * (1.0 - smoothstep(0.45, 0.6, slope)));
	ALBEDO = c * (0.75 + d * 0.5);
	ROUGHNESS = 0.95;
}
```
```gdscript
# res://demo.gd
extends Node3D

func _ready() -> void:
	var noise := FastNoiseLite.new()
	noise.frequency = 0.035
	noise.fractal_octaves = 5
	var n := 96
	var cell := 0.7
	var st := SurfaceTool.new()
	st.begin(Mesh.PRIMITIVE_TRIANGLES)
	var h := func(x: int, z: int) -> float:
		var v := noise.get_noise_2d(x, z) * 0.5 + 0.5
		return pow(v, 2.2) * 16.0
	for z in n:
		for x in n:
			var a := Vector3(x * cell, h.call(x, z), z * cell)
			var b := Vector3((x + 1) * cell, h.call(x + 1, z), z * cell)
			var c := Vector3(x * cell, h.call(x, z + 1), (z + 1) * cell)
			var d := Vector3((x + 1) * cell, h.call(x + 1, z + 1), (z + 1) * cell)
			for v in [a, b, c, b, d, c]:
				st.add_vertex(v)
	st.generate_normals()
	var mi := MeshInstance3D.new()
	mi.mesh = st.commit()
	var tex := NoiseTexture2D.new()
	tex.seamless = true
	tex.noise = FastNoiseLite.new()
	tex.noise.frequency = 0.05
	var m := ShaderMaterial.new()
	m.shader = load("res://terrain.gdshader")
	m.set_shader_parameter("detail", tex)
	mi.material_override = m
	mi.position = Vector3(-n * cell / 2, -2, -n * cell)
	add_child(mi)
	var env := WorldEnvironment.new()
	env.environment = Environment.new()
	env.environment.background_mode = Environment.BG_COLOR
	env.environment.background_color = Color("#a8c8e8")
	env.environment.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.environment.ambient_light_color = Color("#68707a")
	add_child(env)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-35, -50, 0)
	add_child(sun)
	var cam := Camera3D.new()
	cam.position = Vector3(0, 18, 10)
	add_child(cam)
	cam.look_at(Vector3(0, 2, -30))
```

## Validation
Grass in the valleys, rock on steep faces without stretched streaks, snow on high flat-ish ground,
soft noisy borders. Stats: one draw call per chunk; ~55k vertices here (fine on mid phones).

## Adaptation
Painterly: posterise the detail and use flat palette colours. Low-poly: no detail texture, faceted
normals (no `generate_normals` smoothing: duplicate vertices per face), colour by face. Strategy maps:
add a coarse hex or square overlay as a second pass. Add the height-fog-3d recipe for depth.
