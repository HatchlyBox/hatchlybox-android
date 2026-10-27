title: Fog, mist and volumetric-looking light shafts in 2D
tags: fog mist haze atmosphere volumetric god rays light shafts clouds smoke layer depth mood cinematic forest
styles: painted realistic flat pixel
cost: medium
engine: godot4
summary: scrolling noise fog layers between parallax planes and additive light shafts fake volumetrics cheaply

## Code
```gdshader
// res://fog.gdshader
shader_type canvas_item;
// Drifting fog: two scrolling noise octaves, faded at the top and bottom of the layer.
uniform sampler2D noise_tex : repeat_enable, filter_linear;
uniform vec4 fog_color : source_color = vec4(0.85, 0.82, 0.9, 1.0);
uniform float density = 0.6;
uniform vec2 speed = vec2(0.015, 0.0);

void fragment() {
	float n = texture(noise_tex, UV * vec2(1.5, 1.0) + TIME * speed).r;
	n = n * 0.6 + texture(noise_tex, UV * vec2(3.0, 2.0) - TIME * speed * 1.7).r * 0.4;
	float band = smoothstep(0.0, 0.35, UV.y) * smoothstep(1.0, 0.6, UV.y);
	COLOR = vec4(fog_color.rgb, clamp((n - 0.35) * 2.0, 0.0, 1.0) * band * density);
}
```
```gdshader
// res://light_shafts.gdshader
shader_type canvas_item;
render_mode blend_add;
// God rays: soft diagonal stripes that fade with distance from the source at the top.
uniform vec4 ray_color : source_color = vec4(1.0, 0.9, 0.7, 1.0);
uniform float strength = 0.35;

float hash(float x) { return fract(sin(x * 91.7) * 4375.85); }

void fragment() {
	float x = UV.x + UV.y * 0.35;
	float stripes = 0.0;
	for (int i = 0; i < 5; i++) {
		float c = hash(float(i)) * 1.2 - 0.1;
		float w = 0.02 + hash(float(i) + 7.0) * 0.06;
		stripes += smoothstep(w, 0.0, abs(x - c)) * (0.6 + 0.4 * sin(TIME * 0.7 + float(i)));
	}
	COLOR = vec4(ray_color.rgb * stripes * strength * (1.0 - UV.y), 1.0);
}
```
```gdscript
extends ColorRect
# A fog layer: noise texture made in code, full width, placed between parallax layers.
func _ready() -> void:
	var nt := NoiseTexture2D.new()
	nt.width = 256
	nt.height = 256
	nt.seamless = true
	var fn := FastNoiseLite.new()
	fn.frequency = 0.02
	nt.noise = fn
	var m := ShaderMaterial.new()
	m.shader = load("res://fog.gdshader")
	m.set_shader_parameter("noise_tex", nt)
	material = m
	size = Vector2(720, 420)
	position = Vector2(0, 600)
```

## Validation
`visual check`: fog lowers contrast in its band but the playfield keeps its "detail" share; if a
"low contrast" warning appears across the whole image, reduce density or keep fog behind gameplay.
Each full-width layer is a full-screen-ish fill: ≤ 2 layers on mid phones.

## Adaptation
Pixel: posterize alpha to 3 steps and use a dithered threshold. Horror: low, green-grey, slow.
Cinematic forest: 2 fog layers between 3 tree layers + light shafts from the top-left light direction.
