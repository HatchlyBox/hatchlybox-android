extends Node2D
## Tap the circles before they shrink away: 30 seconds, best score saved on the phone.

const SAVE := "user://save.cfg"
const ROUND := 30.0

var targets := []  # each {"pos": Vector2, "r": float}
var score := 0
var best := 0
var time_left := ROUND
var state := "start"  # "start", "play" or "over"
var spawn := 0.0

@onready var hud: Label = $HUD/Score
@onready var message: Label = $HUD/Message


func _ready() -> void:
	var cfg := ConfigFile.new()
	if cfg.load(SAVE) == OK:
		best = cfg.get_value("game", "best", 0)
	_place_hud()
	get_viewport().size_changed.connect(_place_hud)
	_show()


## Keep the score clear of the status bar and notch: the safe area is in screen pixels.
func _place_hud() -> void:
	var safe := DisplayServer.get_display_safe_area()
	var ratio := get_viewport_rect().size.y / float(max(1, DisplayServer.window_get_size().y))
	hud.position.y = 24.0 + safe.position.y * ratio


func _process(delta: float) -> void:
	if state == "play":
		time_left -= delta
		spawn -= delta
		if spawn <= 0.0:
			spawn = 0.7
			var size := get_viewport_rect().size
			targets.append({"pos": Vector2(randf_range(70.0, size.x - 70.0), randf_range(180.0, size.y - 70.0)), "r": 70.0})
		for t in targets:
			t.r -= 40.0 * delta
		targets = targets.filter(func(t): return t.r > 8.0)
		if time_left <= 0.0:
			_end()
		_show()
	queue_redraw()


func _unhandled_input(event: InputEvent) -> void:
	if not (event is InputEventScreenTouch and event.pressed):
		return
	if state != "play":
		_start()
		return
	for i in range(targets.size() - 1, -1, -1):
		if event.position.distance_to(targets[i].pos) <= targets[i].r:
			targets.remove_at(i)
			score += 1
			return


func _draw() -> void:
	draw_rect(get_viewport_rect(), Color("#1b1f3b"))
	for t in targets:
		draw_circle(t.pos, t.r, Color("#ffb300"))
		draw_circle(t.pos, t.r * 0.55, Color("#ff6f00"))


func _start() -> void:
	state = "play"
	score = 0
	time_left = ROUND
	targets.clear()


func _end() -> void:
	state = "over"
	targets.clear()
	if score > best:
		best = score
		var cfg := ConfigFile.new()
		cfg.set_value("game", "best", best)
		cfg.save(SAVE)


func _show() -> void:
	hud.text = "Score %d    Best %d    %ds" % [score, best, ceili(max(time_left, 0.0))]
	message.visible = state != "play"
	if state == "start":
		message.text = "Tap Targets\n\nTap to start"
	else:
		message.text = "Time's up!\n%d points\n\nTap to play again" % score
