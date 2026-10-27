title: Particle effects: fire, smoke, dust, sparks, rain, snow, magic
tags: particles fire smoke dust sparks explosion rain snow weather magic sparkle trail effects vfx
styles: all
cost: low
engine: godot4
summary: CPUParticles2D presets built in code for the common effects, with phone-friendly counts

## Rules
- `CPUParticles2D` for small effects (< 200 particles): works everywhere, no shader compile hitch.
  `GPUParticles2D` + `ParticleProcessMaterial` for large weather (thousands) in gl_compatibility.
- Colour ramps (`color_ramp`) fade to transparent; size curves shrink; additive blending for fire/magic.
- One-shot bursts (`one_shot = true`, `explosiveness = 1`) for hits/explosions; restart with `restart()`.
- Budget: ≤ 500 particles on screen on mid phones; big soft particles cost fill rate: fewer, smaller.

## Code
```gdscript
extends Node2D
# Factory for common effects. Each returns a ready CPUParticles2D you add where needed.
static func ramp(colors: Array) -> Gradient:
	var g := Gradient.new()
	var offsets := PackedFloat32Array()
	for i in colors.size():
		offsets.append(float(i) / maxf(1.0, colors.size() - 1))
	g.offsets = offsets
	g.colors = PackedColorArray(colors)
	return g

static func additive() -> CanvasItemMaterial:
	var m := CanvasItemMaterial.new()
	m.blend_mode = CanvasItemMaterial.BLEND_MODE_ADD
	return m

static func fire() -> CPUParticles2D:
	var p := CPUParticles2D.new()
	p.amount = 60
	p.lifetime = 0.9
	p.direction = Vector2.UP
	p.spread = 15.0
	p.gravity = Vector2(0, -60)
	p.initial_velocity_min = 40.0
	p.initial_velocity_max = 90.0
	p.scale_amount_min = 6.0
	p.scale_amount_max = 12.0
	p.color_ramp = ramp([Color("fff3b0"), Color("ffb35c"), Color("e2553a"), Color(0.2, 0.1, 0.1, 0.0)])
	p.material = additive()
	return p

static func dust_burst() -> CPUParticles2D:
	var p := CPUParticles2D.new()
	p.amount = 24
	p.one_shot = true
	p.explosiveness = 1.0
	p.lifetime = 0.5
	p.spread = 180.0
	p.initial_velocity_min = 60.0
	p.initial_velocity_max = 160.0
	p.damping_min = 200.0
	p.damping_max = 300.0
	p.scale_amount_min = 3.0
	p.scale_amount_max = 6.0
	p.color_ramp = ramp([Color(0.9, 0.85, 0.75, 0.8), Color(0.9, 0.85, 0.75, 0.0)])
	return p

static func snow(width: float) -> CPUParticles2D:
	var p := CPUParticles2D.new()
	p.amount = 150
	p.lifetime = 6.0
	p.preprocess = 6.0
	p.emission_shape = CPUParticles2D.EMISSION_SHAPE_RECTANGLE
	p.emission_rect_extents = Vector2(width / 2.0, 4)
	p.direction = Vector2.DOWN
	p.gravity = Vector2(10, 30)
	p.initial_velocity_min = 20.0
	p.initial_velocity_max = 50.0
	p.scale_amount_min = 1.5
	p.scale_amount_max = 3.5
	p.color = Color(1, 1, 1, 0.85)
	return p

func _ready() -> void:
	var f := fire()
	f.position = Vector2(360, 900)
	add_child(f)
	var s := snow(720)
	s.position = Vector2(360, -10)
	add_child(s)
	var d := dust_burst()
	d.position = Vector2(360, 1000)
	add_child(d)
```

## Validation
Render with `--at` during the effect; `--against` a frame before it shows the effect area. Check
objects/primitives in stats against the budget.

## Adaptation
Pixel: particles as 1–2 px squares (`scale_amount` 1–2, nearest filter), no additive blur, fewer colours.
Magic: additive, cyan/violet ramp, `orbit_velocity`. Rain: thin stretched particles via a 1×6 texture,
fast downward velocity, slight angle.
