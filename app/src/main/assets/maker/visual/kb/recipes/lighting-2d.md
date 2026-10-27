title: 2D lighting, day-night and glow
tags: light lighting day night cycle torch lantern glow shadows canvasmodulate pointlight2d normal map atmosphere mood
styles: all
cost: medium
engine: godot4
summary: CanvasModulate for time of day, PointLight2D for torches, additive glow sprites when lights are too costly

## Methods
- **Time of day**: `CanvasModulate.color` from a `Gradient` sampled by time (dawn pink, noon white,
  dusk orange, night blue ~ #3b4a7a).
- **Lights**: `PointLight2D` with a radial `GradientTexture2D` (no image file needed). Each light re-draws
  what it touches: ≤ 4 on mid phones. `energy` 0.8–1.5, warm `color` for fire, `blend_mode` add.
- **Cheap glow**: a Sprite2D with the same radial gradient and `CanvasItemMaterial.blend_mode = BLEND_MODE_ADD`
  looks like light without lighting cost — use it for many small lights (windows, fireflies, lava).
- **Normal maps** make flat sprites react to light: a `CanvasTexture` with `normal_texture`; generate
  a normal map from a height (grayscale) image with the code in `recipes/sprite-shaders` or skip it.
- **Shadows**: `LightOccluder2D` + `shadow_enabled` only for hero lights (expensive).
- Lit things ignore CanvasModulate if `light_mask`/`CanvasLayer` separate the UI: keep the HUD on a CanvasLayer.

## Code
```gdscript
extends Node2D
# Day-night tint plus a flickering torch and cheap additive glows.
@export var day_length := 60.0
var clock := 0.3
var tint := Gradient.new()
var modulate_node := CanvasModulate.new()
var torch := PointLight2D.new()

static func radial(size: int, color: Color) -> GradientTexture2D:
	var g := Gradient.new()
	g.set_color(0, color)
	g.set_color(1, Color(color, 0.0))
	var t := GradientTexture2D.new()
	t.gradient = g
	t.fill = GradientTexture2D.FILL_RADIAL
	t.fill_from = Vector2(0.5, 0.5)
	t.fill_to = Vector2(1.0, 0.5)
	t.width = size
	t.height = size
	return t

func _ready() -> void:
	tint.offsets = PackedFloat32Array([0.0, 0.25, 0.5, 0.75, 1.0])
	tint.colors = PackedColorArray([Color("3b4a7a"), Color("f2b8a0"), Color.WHITE, Color("f4a261"), Color("3b4a7a")])
	add_child(modulate_node)
	torch.texture = radial(256, Color.WHITE)
	torch.color = Color("ffb35c")
	torch.energy = 1.2
	torch.position = Vector2(360, 700)
	add_child(torch)
	for i in 6:
		var glow := Sprite2D.new()
		glow.texture = radial(96, Color("ffd98a"))
		var m := CanvasItemMaterial.new()
		m.blend_mode = CanvasItemMaterial.BLEND_MODE_ADD
		glow.material = m
		glow.position = Vector2(100 + i * 100, 500)
		add_child(glow)

func _process(delta: float) -> void:
	clock = fmod(clock + delta / day_length, 1.0)
	modulate_node.color = tint.sample(clock)
	torch.energy = 1.1 + 0.15 * sin(Time.get_ticks_msec() * 0.02) + randf() * 0.05
```

## Validation
Render at several clock values (export a start time) and `visual check`: night should still have
contrast (lights carry the scene); no "mostly black" warning. stats: each PointLight2D adds draw calls.

## Adaptation
Pixel: light textures with hard steps (Gradient interpolation CONSTANT, 3–4 bands). Painted: soft
large radii, warm/cool split. Horror: one small light, everything else near black but not 0.
