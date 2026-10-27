title: Motion, animation principles and game feel
tags: animation motion juice game feel easing tween squash stretch shake hit stop feedback anticipation
styles: all
cost: low
engine: godot4
summary: easing, anticipation, squash & stretch, screen shake, hit stop and particles that make actions feel good

## Principles
- **Easing**: nothing moves linearly; UI in with ease-out (`Tween.EASE_OUT`, `TRANS_BACK` for a pop),
  out with ease-in.
- **Anticipation → action → follow-through**: wind up before a jump/attack, overshoot and settle after.
- **Squash & stretch** on landing/jumping (keep area constant: scale (1.2, 0.8)).
- **Secondary motion**: capes, hair, foliage sway with a sine offset.
- **Timing**: hits 50–100 ms hit stop; UI transitions 150–300 ms; idle loops 1–3 s.
- **Juice on events**: flash white, shake, particles, sound, number pop-ups. Scale effects with importance.

## Code
```gdscript
extends Node2D
# Reusable juice: squash, flash, shake and hit stop.
var shake := 0.0
@onready var cam: Camera2D = get_viewport().get_camera_2d()

func squash(node: Node2D) -> void:
	var tw := create_tween()
	tw.tween_property(node, "scale", Vector2(1.25, 0.8), 0.06)
	tw.tween_property(node, "scale", Vector2.ONE, 0.25).set_trans(Tween.TRANS_ELASTIC).set_ease(Tween.EASE_OUT)

func flash(item: CanvasItem) -> void:
	item.modulate = Color(3, 3, 3)
	create_tween().tween_property(item, "modulate", Color.WHITE, 0.15)

func add_shake(amount: float) -> void:
	shake = minf(shake + amount, 24.0)

func hit_stop(seconds: float = 0.06) -> void:
	Engine.time_scale = 0.05
	await get_tree().create_timer(seconds, true, false, true).timeout
	Engine.time_scale = 1.0

func _process(delta: float) -> void:
	if cam and shake > 0.0:
		cam.offset = Vector2(randf_range(-shake, shake), randf_range(-shake, shake))
		shake = move_toward(shake, 0.0, 60.0 * delta)
		if shake == 0.0:
			cam.offset = Vector2.ZERO
```

## Validation
Render several frames around an event (`--at 2,2.05,2.1,2.2 --tap ...`) and compare with `--against`:
the effect should be visible but short.
