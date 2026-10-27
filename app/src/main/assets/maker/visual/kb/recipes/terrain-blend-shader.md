title: Terrain material blending shader
tags: terrain blend splat mask shader textures grass dirt rock transitions painted realistic ground
styles: painted realistic flat
cost: medium
engine: godot4
summary: blend several ground materials with a mask and noise so transitions look painted, not tiled

## Purpose
Soft, organic transitions (grass into dirt into rock) on large 2D or top-down terrain.

## Method
A mask texture (R = dirt, G = rock, B = snow; grass where all are 0) drawn from the map data, plus noise
to break up edges. Materials are procedural here (colour + noise) so no image files are needed; swap in
textures for a painted look.

## Code
```gdshader
// res://terrain_blend.gdshader
shader_type canvas_item;
// Splat blending: the mask's channels choose dirt, rock and snow over grass, with noisy edges.
uniform sampler2D mask : filter_linear;
uniform sampler2D noise : repeat_enable, filter_linear;
uniform vec4 grass : source_color = vec4(0.42, 0.62, 0.36, 1.0);
uniform vec4 dirt : source_color = vec4(0.55, 0.42, 0.30, 1.0);
uniform vec4 rock : source_color = vec4(0.50, 0.50, 0.54, 1.0);
uniform vec4 snow : source_color = vec4(0.93, 0.95, 0.98, 1.0);
uniform float edge_noise = 0.25;
uniform float detail_scale = 6.0;

void fragment() {
	float n = texture(noise, UV * detail_scale).r;
	vec3 m = texture(mask, UV).rgb;
	m = clamp((m - 0.5 + (n - 0.5) * edge_noise) * 4.0 + 0.5, 0.0, 1.0);
	vec3 c = grass.rgb * (0.85 + 0.3 * n);
	c = mix(c, dirt.rgb * (0.8 + 0.4 * n), m.r);
	c = mix(c, rock.rgb * (0.75 + 0.5 * n), m.g);
	c = mix(c, snow.rgb, m.b);
	COLOR = vec4(c, 1.0);
}
```
```gdscript
extends Sprite2D
# Builds the mask from heights and applies the blend shader to this sprite.
func _ready() -> void:
	var size := Vector2i(256, 256)
	var hn := FastNoiseLite.new()
	hn.frequency = 0.01
	var img := Image.create(size.x, size.y, false, Image.FORMAT_RGB8)
	for y in size.y:
		for x in size.x:
			var h := hn.get_noise_2d(x, y)
			img.set_pixel(x, y, Color(clampf(1.0 - absf(h) * 6.0, 0, 1), clampf((h - 0.2) * 5.0, 0, 1), clampf((h - 0.4) * 5.0, 0, 1)))
	var mat := ShaderMaterial.new()
	mat.shader = load("res://terrain_blend.gdshader")
	mat.set_shader_parameter("mask", ImageTexture.create_from_image(img))
	var nt := NoiseTexture2D.new()
	nt.seamless = true
	nt.noise = FastNoiseLite.new()
	mat.set_shader_parameter("noise", nt)
	material = mat
	texture = PlaceholderTexture2D.new()
	(texture as PlaceholderTexture2D).size = Vector2(size * 4)
```

## Validation
Render: edges should wobble naturally, no hard straight seams. `visual check`: colourfulness 25–50 for
natural ground; one draw call for the whole terrain.

## Adaptation
Pixel: set the sprite's texture_filter to nearest and posterize (`c = floor(c * 6.0) / 6.0`).
Low-poly: use vertex colours on a mesh instead.
