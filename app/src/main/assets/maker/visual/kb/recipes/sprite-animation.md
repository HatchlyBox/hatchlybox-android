title: Character and sprite animation without an animation editor
tags: animation sprite character walk idle run jump attack frames spritesheet procedural limbs bob tween skeleton
styles: all
cost: low
engine: godot4
summary: frame animation from code-built SpriteFrames, or procedural motion (bob, lean, limb swing) for any style

## Methods
- **Frames**: build `SpriteFrames` in code from images you generate (pixel maps, SVGs, baked 3D) and
  play them with `AnimatedSprite2D` (walk 6–8 frames at 10–12 fps, idle 2–4 at 4–6 fps).
- **Procedural**: one drawing, animated by transforms: bob (sin), lean into movement, squash on land,
  limb swing with `Polygon2D` children rotated by sine. Looks good for flat/vector styles and costs nothing.
- **Cut-out rigs**: Sprite2D/Polygon2D parts parented in a hierarchy (body → arm → hand), rotated by code or
  `AnimationPlayer` tracks created in code.

## Code
```gdscript
extends Node2D
# A procedural walker: body, head and two legs that swing with speed; lean and bob for life.
@export var speed := 120.0
@export var body_color := Color("3f6e8c")
var t := 0.0
var legs: Array[Polygon2D] = []
var body := Polygon2D.new()

func part(points: PackedVector2Array, color: Color) -> Polygon2D:
	var p := Polygon2D.new()
	p.polygon = points
	p.color = color
	return p

func _ready() -> void:
	for side in [-1, 1]:
		var leg := part(PackedVector2Array([Vector2(-4, 0), Vector2(4, 0), Vector2(4, 26), Vector2(-4, 26)]), body_color.darkened(0.3))
		leg.position = Vector2(side * 7, 0)
		add_child(leg)
		legs.append(leg)
	body = part(PackedVector2Array([Vector2(-14, -40), Vector2(14, -40), Vector2(12, 2), Vector2(-12, 2)]), body_color)
	add_child(body)
	var head := part(PackedVector2Array([Vector2(-10, -62), Vector2(10, -62), Vector2(10, -42), Vector2(-10, -42)]), Color("f2c9a0"))
	body.add_child(head)
	position = Vector2(200, 700)

func _process(delta: float) -> void:
	t += delta * speed / 30.0
	var swing := sin(t) * 0.6
	legs[0].rotation = swing
	legs[1].rotation = -swing
	body.position.y = -absf(cos(t)) * 4.0
	body.rotation = 0.08
	position.x = fmod(position.x + speed * delta, 720.0)
```

## Validation
Render 4 frames a quarter-cycle apart (`--at 1,1.1,1.2,1.3`) and look at the sheet: the pose should
clearly change; feet shouldn't slide visibly (match swing rate to speed).

## Adaptation
Pixel: frames instead of rotation (rotation breaks the pixel grid). Painted: cut-out parts with soft
textures and secondary motion (cloth sway via `recipes/sprite-shaders` sway).
