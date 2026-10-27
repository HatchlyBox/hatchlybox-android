title: Cinematic colour grading with a LUT
tags: color grading colour grade lut lookup table cinematic film look teal orange mood tone post processing screen shader filmic atmosphere
styles: all
cost: low
engine: godot4
summary: one full-screen pass maps every colour through a lookup table: film looks, split toning, day/night moods, all made in code or painted

## Method
Grading is what makes a game look "shot" rather than rendered. A LUT (lookup table) stores, for every
input colour, the graded output colour, so any grade (contrast curves, split toning, saturation per hue,
a film emulation) costs one texture lookup per pixel:
- The LUT is a strip of N slices (N×N each, blue picks the slice, red/green the pixel): 16 slices of
  16×16 = a 256×16 image. Build it in code from a grading function (below) or grade a neutral LUT PNG
  in any image editor along with a screenshot and save it.
- Apply it on a `CanvasLayer` above the game with a full-screen `ColorRect` using
  `hint_screen_texture` (works over 2D and 3D, in the compatibility renderer).
- Blend between two LUTs for time-of-day or story moments (`mix` two lookups by a uniform).
- Keep the UI on a higher CanvasLayer so it isn't graded.

## Code
```gdshader
// res://grade.gdshader
shader_type canvas_item;
// Looks up each screen colour in a 16-slice strip LUT, blending neighbouring blue slices.
uniform sampler2D screen_tex : hint_screen_texture, filter_linear;
uniform sampler2D lut : filter_linear, repeat_disable;
uniform float amount = 1.0;
uniform float split = 0.0; // 0 = whole screen; 0.5 = graded right half only (to compare)

vec3 lookup(vec3 c) {
	float n = 16.0;
	float b = c.b * (n - 1.0);
	float s0 = floor(b);
	float s1 = min(s0 + 1.0, n - 1.0);
	vec2 px = vec2(c.r * (n - 1.0) + 0.5, c.g * (n - 1.0) + 0.5) / vec2(n * n, n);
	vec3 a = texture(lut, px + vec2(s0 / n, 0.0)).rgb;
	vec3 d = texture(lut, px + vec2(s1 / n, 0.0)).rgb;
	return mix(a, d, b - s0);
}

void fragment() {
	vec3 c = texture(screen_tex, SCREEN_UV).rgb;
	vec3 g = mix(c, lookup(clamp(c, 0.0, 1.0)), amount);
	COLOR = vec4(SCREEN_UV.x >= split ? g : c, 1.0);
}
```
```gdscript
# res://demo.gd
extends Node2D
# A colourful test scene, graded (right half) with a teal-and-orange film look built in code.

func grade_fn(c: Color) -> Color:
	var l := c.r * 0.3 + c.g * 0.59 + c.b * 0.11
	var s := Color(0.05, 0.25, 0.35).lerp(Color(1.0, 0.75, 0.5), smoothstep(0.1, 0.9, l))
	var g := c.lerp(Color(l, l, l), -0.15)
	g = Color(g.r * 0.45 + s.r * 0.55, g.g * 0.45 + s.g * 0.55, g.b * 0.45 + s.b * 0.55)
	for k in 3:
		var v: float = g[k]
		g[k] = clampf(v * v * (3.0 - 2.0 * v) * 1.05, 0.0, 1.0)
	return g

func make_lut() -> ImageTexture:
	var n := 16
	var img := Image.create(n * n, n, false, Image.FORMAT_RGB8)
	for b in n:
		for g in n:
			for r in n:
				img.set_pixel(b * n + r, g, grade_fn(Color(r / 15.0, g / 15.0, b / 15.0)))
	return ImageTexture.create_from_image(img)

func _draw() -> void:
	var w := 720.0
	for i in 32:
		draw_rect(Rect2(0, i * 40, w, 40), Color.from_hsv(0.6 - i * 0.012, 0.5, 0.25 + i * 0.022))
	for i in 7:
		draw_circle(Vector2(100 + i * 90, 640 + sin(i) * 120), 60, Color.from_hsv(i / 7.0, 0.7, 0.9))
	draw_rect(Rect2(0, 1000, w, 280), Color("#3d6b35"))

func _ready() -> void:
	var layer := CanvasLayer.new()
	add_child(layer)
	var r := ColorRect.new()
	r.set_anchors_preset(Control.PRESET_FULL_RECT)
	r.size = Vector2(720, 1280)
	var m := ShaderMaterial.new()
	m.shader = load("res://grade.gdshader")
	m.set_shader_parameter("lut", make_lut())
	m.set_shader_parameter("split", 0.5)
	r.material = m
	layer.add_child(r)
```

## Validation
Right half: shadows lean teal, highlights warm, more contrast; left half untouched. `visual check`
before/after with `--against`: colourfulness and range change, no clipping to pure black or white.

## Adaptation
Painterly: lower contrast, warm highlights, desaturated greens. Horror: crush blues, green-grey
midtones. Night: blue shift + low saturation; blend to it with `amount`. Pixel: grade to the palette
(a LUT that snaps to the nearest palette colour = palette enforcement).
