title: 2D water with waves and reflection
tags: water lake river sea ocean waves reflection ripple shader refraction shore foam caustics
styles: painted flat realistic pixel
cost: medium
engine: godot4
summary: a canvas shader for water: animated distortion, a reflection of what's above, shore foam and depth tint

## Code
```gdshader
// res://water.gdshader
shader_type canvas_item;
// Side-view water: reflects the screen above the surface, wobbles it, tints with depth, adds highlights.
uniform sampler2D screen_texture : hint_screen_texture, filter_linear;
uniform vec4 shallow : source_color = vec4(0.30, 0.62, 0.72, 1.0);
uniform vec4 deep : source_color = vec4(0.07, 0.20, 0.35, 1.0);
uniform float surface_y = 0.0; // the water's top edge in screen UV (set from code)
uniform float reflection = 0.45;
uniform float wave = 0.006;

void fragment() {
	vec2 suv = SCREEN_UV;
	float wob = sin(UV.x * 40.0 + TIME * 2.0) * wave + sin(UV.x * 13.0 - TIME * 1.3) * wave * 0.6;
	vec2 refl_uv = vec2(suv.x + wob, 2.0 * surface_y - suv.y + wob * 3.0);
	vec3 refl = texture(screen_texture, clamp(refl_uv, vec2(0.0), vec2(1.0))).rgb;
	vec3 water = mix(shallow.rgb, deep.rgb, smoothstep(0.0, 1.0, UV.y));
	vec3 c = mix(water, refl, reflection * (1.0 - UV.y));
	float glint = pow(max(0.0, sin(UV.x * 90.0 + TIME * 3.0 + UV.y * 30.0)), 40.0) * (1.0 - UV.y);
	c += glint * 0.5;
	float foam = smoothstep(0.03, 0.0, UV.y + wob * 2.0);
	c = mix(c, vec3(0.95), foam);
	COLOR = vec4(c, 0.95);
}
```
```gdscript
extends ColorRect
# The water surface: keeps surface_y in screen UV so the reflection lines up.
func _ready() -> void:
	var m := ShaderMaterial.new()
	m.shader = load("res://water.gdshader")
	material = m
	color = Color.WHITE

func _process(_delta: float) -> void:
	var vp := get_viewport_rect().size
	var top := get_global_transform_with_canvas().origin.y
	(material as ShaderMaterial).set_shader_parameter("surface_y", top / vp.y)
```
Draw the water after (below in the tree) what it reflects. Screen-reading shaders cost one copy of the
screen: use one water node, not many.

## Top-down variant
Skip the reflection; use two scrolling noise textures for ripples, `deep`/`shallow` by distance from
shore (a mask), and white foam where the mask is near the shore edge.

## Validation
Render twice (`--at 1,1.5`) and `--against`: the surface should change subtly (waves), not flicker.

## Adaptation
Pixel: quantize UV to the pixel grid (`floor(UV * size) / size`) and colours to 4 tones, glints as
single pixels. Flat: no reflection, two tones and moving white lines.
