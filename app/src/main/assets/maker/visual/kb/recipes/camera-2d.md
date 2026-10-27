title: Camera work for 2D: follow, framing, cinematic moves
tags: camera camera2d follow smoothing zoom pan cinematic framing look ahead shake limits intro
styles: all
cost: none
engine: godot4
summary: smooth follow with look-ahead, limits, zoom for scale, and tweened cinematic pans for intros

## Code
```gdscript
extends Camera2D
# Follows a target with look-ahead in its moving direction; cinematic() pans and zooms for intros.
@export var target: Node2D
@export var look_ahead := 80.0
var last := Vector2.ZERO

func _ready() -> void:
	position_smoothing_enabled = true
	position_smoothing_speed = 5.0
	make_current()

func _process(delta: float) -> void:
	if target == null:
		return
	var moving := (target.global_position - last) / maxf(delta, 0.0001)
	last = target.global_position
	global_position = target.global_position + moving.normalized() * look_ahead * clampf(moving.length() / 300.0, 0.0, 1.0)

func cinematic(from: Vector2, to: Vector2, from_zoom: float, to_zoom: float, seconds: float) -> void:
	position_smoothing_enabled = false
	global_position = from
	zoom = Vector2.ONE * from_zoom
	var tw := create_tween().set_parallel().set_trans(Tween.TRANS_SINE).set_ease(Tween.EASE_IN_OUT)
	tw.tween_property(self, "global_position", to, seconds)
	tw.tween_property(self, "zoom", Vector2.ONE * to_zoom, seconds)
	await tw.finished
	position_smoothing_enabled = true
```

## Tips
Set `limit_*` to the level bounds. Zoom out to show scale (armies, castles), in for intimacy.
Shake via `offset` (`principles/motion-juice`), never the position.

## Validation
Render an intro with `--at` along the pan; the sheet should read as a storyboard: wide establishing
shot → focus.
