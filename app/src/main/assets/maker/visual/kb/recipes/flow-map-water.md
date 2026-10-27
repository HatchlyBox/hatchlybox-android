title: Flowing rivers and moats with flow maps
tags: water river stream moat flow map flowmap current waterfall lava flowing animated shader 2d top-down foam
styles: painted flat realistic pixel
cost: low
engine: godot4
summary: a texture of directions tells the water shader where each pixel flows; two time-offset phases blend so the motion never stretches or resets visibly

## Method
Scrolling a texture in one direction can't follow a bending river. A **flow map** stores a direction
per pixel (red = x, green = y, 0.5 = still). The shader moves the water pattern along it:
- Two phases, half a cycle apart, each distorting UVs by `flow * fract(time)`; blend them with a
  triangle wave so each resets while invisible. No stretching, no visible jump.
- Speed (flow length) also drives **foam** and brightness: fast water is whiter.
- Make the flow map in code from the river's path (below: the tangent of a curve), or paint it.
  Obstacles: flow goes around them (bend the vectors away), with foam at the front.
- Same technique for lava, conveyor belts, magic streams, clouds over a map.

## Code
```gdshader
// res://flow_water.gdshader
shader_type canvas_item;
// Flow-mapped water: two phases of a ripple texture moved along the flow, blended by a triangle wave.
uniform sampler2D flow_map : filter_linear;
uniform sampler2D ripples : filter_linear_mipmap, repeat_enable;
uniform vec4 deep : source_color = vec4(0.08, 0.25, 0.4, 1.0);
uniform vec4 shallow : source_color = vec4(0.2, 0.55, 0.65, 1.0);
uniform vec4 foam : source_color = vec4(0.9, 0.97, 1.0, 1.0);
uniform float strength = 0.25;
uniform float tiling = 3.0;

void fragment() {
	vec4 f = texture(flow_map, UV);
	vec2 flow = (f.rg - 0.5) * 2.0;
	float speed = length(flow);
	float t = TIME * 0.4;
	float p0 = fract(t);
	float p1 = fract(t + 0.5);
	float w = abs(p0 - 0.5) * 2.0;
	vec2 uv = UV * tiling;
	float a = texture(ripples, uv - flow * p0 * strength).r;
	float b = texture(ripples, uv - flow * p1 * strength + 0.37).r;
	float r = mix(a, b, w);
	vec3 c = mix(deep.rgb, shallow.rgb, r * 0.8 + speed * 0.2);
	c = mix(c, foam.rgb, smoothstep(0.78, 0.92, r + speed * 0.15) * f.b);
	COLOR = vec4(c, f.a);
}
```
```gdscript
# res://demo.gd
extends Node2D
# A meandering river: the flow map follows the river's centre line; alpha = the river's banks.

func _draw() -> void:
	draw_rect(Rect2(0, 0, 720, 1280), Color("#5d8a3f"))

func _ready() -> void:
	var w := 128
	var h := 224
	var img := Image.create(w, h, false, Image.FORMAT_RGBA8)
	for y in h:
		var cx := w * 0.5 + sin(y * 0.045) * w * 0.22
		var dx := cos(y * 0.045) * w * 0.22 * 0.045
		var dir := Vector2(dx, 1.0).normalized()
		for x in w:
			var d := absf(x - cx) / (w * 0.18)
			var inside := clampf((1.0 - d) * 4.0, 0.0, 1.0)
			var spd := clampf(1.0 - d * d, 0.0, 1.0)
			var v := dir * spd
			img.set_pixel(x, y, Color(v.x * 0.5 + 0.5, v.y * 0.5 + 0.5, spd, inside))
	var flow := ImageTexture.create_from_image(img)
	var rip := NoiseTexture2D.new()
	rip.seamless = true
	rip.generate_mipmaps = true
	rip.noise = FastNoiseLite.new()
	rip.noise.noise_type = FastNoiseLite.TYPE_CELLULAR
	rip.noise.frequency = 0.04
	var r := ColorRect.new()
	r.size = Vector2(720, 1280)
	var m := ShaderMaterial.new()
	m.shader = load("res://flow_water.gdshader")
	m.set_shader_parameter("flow_map", flow)
	m.set_shader_parameter("ripples", rip)
	r.material = m
	add_child(r)
```

## Validation
A river that bends with the banks, ripples moving along it, faster and whiter in the middle, no
visible reset. Compare two shots 1 s apart with `visual check --against`: the water area changes.

## Adaptation
Pixel: low-res flow map with nearest filtering, posterise r to 3 levels. Painterly: soft ripple
noise, foam as painted strokes texture. Top-down strategy maps: one flow map for all rivers of the
map. Lava: hot colours, slow speed, glow on foam. 3D: the same shader as spatial on a plane, UV = xz.
