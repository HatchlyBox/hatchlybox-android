title: Sprite shaders: hit flash, outline, dissolve, palette swap, wind sway
tags: shader sprite flash hit damage outline glow dissolve burn palette swap recolor wind sway foliage
styles: all
cost: low
engine: godot4
summary: small canvas_item shaders for feedback and variety without new art

## Code
```gdshader
// res://sprite_fx.gdshader
shader_type canvas_item;
// One shader for common sprite effects; set the uniforms from code (tweens work on them).
uniform float flash : hint_range(0.0, 1.0) = 0.0;
uniform vec4 flash_color : source_color = vec4(1.0);
uniform float outline_width = 0.0; // in texels
uniform vec4 outline_color : source_color = vec4(0.1, 0.08, 0.15, 1.0);
uniform float dissolve : hint_range(0.0, 1.0) = 0.0;
uniform vec4 burn_color : source_color = vec4(1.0, 0.5, 0.1, 1.0);
uniform float sway = 0.0; // wind: pixels at the top of the sprite

float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }

void vertex() {
	VERTEX.x += sway * sin(TIME * 1.7 + VERTEX.y * 0.05) * (1.0 - UV.y);
}

void fragment() {
	vec4 c = texture(TEXTURE, UV);
	if (outline_width > 0.0 && c.a < 0.5) {
		vec2 px = TEXTURE_PIXEL_SIZE * outline_width;
		float a = texture(TEXTURE, UV + vec2(px.x, 0.0)).a + texture(TEXTURE, UV - vec2(px.x, 0.0)).a
			+ texture(TEXTURE, UV + vec2(0.0, px.y)).a + texture(TEXTURE, UV - vec2(0.0, px.y)).a;
		if (a > 0.0) c = outline_color;
	}
	c.rgb = mix(c.rgb, flash_color.rgb, flash);
	if (dissolve > 0.0) {
		float n = hash(floor(UV / TEXTURE_PIXEL_SIZE));
		if (n < dissolve) c.a = 0.0;
		else if (n < dissolve + 0.08) c.rgb = burn_color.rgb;
	}
	COLOR = c * COLOR;
}
```
```gdscript
extends Sprite2D
# Uses sprite_fx.gdshader: hit() flashes, die() dissolves, outline on selection.
func _ready() -> void:
	var m := ShaderMaterial.new()
	m.shader = load("res://sprite_fx.gdshader")
	material = m
	if texture == null:
		var g := GradientTexture2D.new()
		g.width = 64
		g.height = 64
		texture = g

func hit() -> void:
	var m := material as ShaderMaterial
	m.set_shader_parameter("flash", 1.0)
	create_tween().tween_method(func(v: float) -> void: m.set_shader_parameter("flash", v), 1.0, 0.0, 0.15)

func select(on: bool) -> void:
	(material as ShaderMaterial).set_shader_parameter("outline_width", 1.5 if on else 0.0)

func die() -> void:
	var m := material as ShaderMaterial
	var tw := create_tween()
	tw.tween_method(func(v: float) -> void: m.set_shader_parameter("dissolve", v), 0.0, 1.0, 0.6)
	tw.tween_callback(queue_free)
```
Outlines need transparent padding around the sprite in its texture (1–2 px).
Sprites sharing one material batch together; giving each its own material (to set uniforms per sprite)
breaks batching. For per-sprite tints use `modulate` (it arrives as COLOR), and give a material of
its own only to the few sprites that flash or dissolve at the moment.

## Palette swap
Draw sprites in a few grey levels; a shader maps grey → a palette row in a small texture
(`texture(palette, vec2(c.r, row))`): enemy variants, team colours, damage states without new art.

## Validation
Render with an effect active (call it in `_ready` for a test scene) and compare `--against` the plain shot.
