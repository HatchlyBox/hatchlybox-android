title: Dithering: stylised shading, palette gradients and fades without transparency
tags: dither dithering bayer ordered blue noise screen-door transparency fade posterize palette gradient retro 1-bit stylised obra dinn
styles: pixel flat retro toon
cost: low
engine: godot4
summary: a 4x4 Bayer threshold turns smooth gradients into a few palette colours with a pattern, and fades 3D objects by discarding pixels instead of blending

## Method
- **Palette gradients (2D):** quantise a smooth value (light, height, distance) to N palette colours;
  before rounding, add the Bayer threshold for that screen pixel. Smooth sky and light gradients become
  a deliberate, retro pattern with an exact palette (no banding).
- **Screen-door fades (3D):** instead of alpha blending (sorting problems, overdraw, slow on phones),
  `discard` pixels whose Bayer value is above the opacity. Fade objects near the camera, LOD
  cross-fades, ghosts, dissolves; stays in the opaque pass.
- **1-bit or 2-colour looks** (Return of the Obra Dinn style): dither the lighting into two colours.
- Use the pixel position at the art's pixel size (`floor(FRAGCOORD.xy / px)`), so the pattern matches
  pixel art when upscaled.
- Blue-noise textures give a less regular pattern than Bayer (a 64×64 blue-noise PNG, CC0).

## Code
```gdshader
// res://dither_sky.gdshader
shader_type canvas_item;
// A vertical gradient quantised to 5 palette colours with ordered (Bayer 4x4) dithering.
uniform vec4 c0 : source_color = vec4(0.06, 0.05, 0.16, 1.0);
uniform vec4 c1 : source_color = vec4(0.2, 0.12, 0.35, 1.0);
uniform vec4 c2 : source_color = vec4(0.55, 0.22, 0.45, 1.0);
uniform vec4 c3 : source_color = vec4(0.95, 0.45, 0.4, 1.0);
uniform vec4 c4 : source_color = vec4(1.0, 0.8, 0.55, 1.0);
uniform float pixel = 4.0;

float bayer(vec2 p) {
	int x = int(mod(p.x, 4.0));
	int y = int(mod(p.y, 4.0));
	int m[16] = int[](0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5);
	return (float(m[y * 4 + x]) + 0.5) / 16.0;
}

void fragment() {
	vec2 p = floor(FRAGCOORD.xy / pixel);
	float v = clamp(UV.y * 4.0 + bayer(p) - 0.5, 0.0, 4.0);
	int i = int(floor(v + 0.5));
	vec4 c = i == 0 ? c0 : i == 1 ? c1 : i == 2 ? c2 : i == 3 ? c3 : c4;
	COLOR = c;
}
```
```gdshader
// res://dither_fade.gdshader
shader_type spatial;
// Screen-door transparency: opaque pass, pixels discarded by a Bayer threshold below "opacity".
uniform vec4 albedo : source_color = vec4(0.9, 0.6, 0.3, 1.0);
uniform float opacity = 0.5;

float bayer(vec2 p) {
	int x = int(mod(p.x, 4.0));
	int y = int(mod(p.y, 4.0));
	int m[16] = int[](0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5);
	return (float(m[y * 4 + x]) + 0.5) / 16.0;
}

void fragment() {
	if (bayer(floor(FRAGCOORD.xy / 2.0)) > opacity) {
		discard;
	}
	ALBEDO = albedo.rgb;
}
```
```gdscript
# res://demo.gd
extends Node3D
# Top: a dithered palette sky (2D layer behind). Front: five spheres fading 100% → 20% without blending.

func _ready() -> void:
	var back := CanvasLayer.new()
	back.layer = -1
	add_child(back)
	var sky := ColorRect.new()
	sky.size = Vector2(720, 1280)
	var sm := ShaderMaterial.new()
	sm.shader = load("res://dither_sky.gdshader")
	sky.material = sm
	back.add_child(sky)
	var env := WorldEnvironment.new()
	env.environment = Environment.new()
	env.environment.background_mode = Environment.BG_CANVAS
	add_child(env)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-40, -30, 0)
	add_child(sun)
	var cam := Camera3D.new()
	cam.position = Vector3(0, 0, 9)
	add_child(cam)
	for i in 5:
		var m := MeshInstance3D.new()
		m.mesh = SphereMesh.new()
		var mat := ShaderMaterial.new()
		mat.shader = load("res://dither_fade.gdshader")
		mat.set_shader_parameter("opacity", 1.0 - i * 0.2)
		m.material_override = mat
		m.position = Vector3(0, 4.2 - i * 2.1, 0)
		add_child(m)
```

## Validation
The sky shows 5 colour bands meeting in regular cross-hatch patterns, no smooth gradient. Spheres go
from solid to sparse dot patterns; no sorting artefacts. `visual check --style pixel` passes crispness.

## Adaptation
1-bit / 2-colour games: c0..c4 = 2 colours, dither the lighting. Pixel-art 3D: dither inside the
low-res viewport (pixel = 1). Fog and light shafts in pixel games: dither the alpha. Painterly or
realistic styles: use blue noise at pixel = 1 for invisible, smooth fades only.
