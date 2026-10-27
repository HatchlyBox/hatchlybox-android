title: Screen transitions and menus that feel designed
tags: transition fade wipe circle iris dissolve scene change menu ui animation polish
styles: all
cost: low
engine: godot4
summary: a transition layer (fade, iris, diagonal wipe) and tweened menu entrances

## Code
```gdshader
// res://transition.gdshader
shader_type canvas_item;
// progress 0 = clear, 1 = covered. mode 0 iris (circle), 1 diagonal wipe, 2 pixel dissolve.
uniform float progress : hint_range(0.0, 1.0) = 0.0;
uniform int mode = 0;
uniform vec4 fill : source_color = vec4(0.06, 0.05, 0.1, 1.0);
uniform float aspect = 0.5625;

float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }

void fragment() {
	float a;
	if (mode == 0) {
		vec2 d = (UV - 0.5) * vec2(1.0, 1.0 / aspect);
		a = step(1.0 - progress, length(d) / 1.2);
	} else if (mode == 1) {
		a = step(UV.x + UV.y * 0.5, progress * 1.5);
	} else {
		a = step(hash(floor(UV * vec2(36.0, 64.0))), progress);
	}
	COLOR = vec4(fill.rgb, a);
}
```
```gdscript
extends CanvasLayer
# Autoload-style transition: cover, change scene, uncover.
var rect := ColorRect.new()

func _ready() -> void:
	layer = 200
	rect.set_anchors_preset(Control.PRESET_FULL_RECT)
	rect.mouse_filter = Control.MOUSE_FILTER_IGNORE
	var m := ShaderMaterial.new()
	m.shader = load("res://transition.gdshader")
	rect.material = m
	add_child(rect)

func go(scene: String, mode: int = 0) -> void:
	var m := rect.material as ShaderMaterial
	m.set_shader_parameter("mode", mode)
	await create_tween().tween_method(func(v: float) -> void: m.set_shader_parameter("progress", v), 0.0, 1.0, 0.35).finished
	if scene != "":
		get_tree().change_scene_to_file(scene)
	await create_tween().tween_method(func(v: float) -> void: m.set_shader_parameter("progress", v), 1.0, 0.0, 0.35).finished
```
Menus: buttons slide/fade in one after another (`tween_property(b, "modulate:a", 1.0, 0.2)` with
`set_delay(i * 0.05)`), scale to 0.95 on press.

## Validation
Render mid-transition (`--at` while progress ≈ 0.5): the shape should be clean (no gaps or banding).
