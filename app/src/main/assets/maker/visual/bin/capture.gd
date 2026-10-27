extends SceneTree
# visual render: loads the main scene (or the one given), sends the touches, saves the screen and
# Godot's performance monitors at the given seconds, then quits.
# Arguments after --: <out dir> <seconds,comma-separated> <taps "x,y@t;..."> [scene]
var out := ""
var times: Array = []
var taps: Array = []
var t := 0.0
var frames := 0
var stats: Array = []

func _initialize() -> void:
	var args := OS.get_cmdline_user_args()
	out = args[0]
	for s in args[1].split(",", false):
		times.append(float(s))
	times.sort()
	if args.size() > 2:
		for s in args[2].split(";", false):
			var p := s.split("@")
			var xy := p[0].split(",")
			taps.append({"t": float(p[1]), "at": Vector2(float(xy[0]), float(xy[1])), "done": false})
	var scene: String = args[3] if args.size() > 3 and args[3] != "" else ProjectSettings.get_setting("application/run/main_scene", "")
	if scene == "":
		push_error("visual render: the project has no main scene; pass --scene res://...")
		quit(2)
		return
	change_scene_to_file(scene)

func _touch(at: Vector2, pressed: bool) -> void:
	var e := InputEventScreenTouch.new()
	e.position = at
	e.pressed = pressed
	Input.parse_input_event(e)
	var m := InputEventMouseButton.new()
	m.position = at
	m.global_position = at
	m.button_index = MOUSE_BUTTON_LEFT
	m.pressed = pressed
	Input.parse_input_event(m)

func _process(delta: float) -> bool:
	t += delta
	frames += 1
	for tap in taps:
		if not tap.done and t >= tap.t:
			tap.done = true
			# Taps are in viewport pixels; input arrives in window pixels (the stretch scales between them).
			var at: Vector2 = root.get_final_transform() * tap.at
			_touch(at, true)
			_touch(at, false)
	if not times.is_empty() and t >= times[0]:
		var f := "%s/shot-%d.png" % [out, stats.size() + 1]
		root.get_texture().get_image().save_png(f)
		stats.append({"t": snappedf(t, 0.01), "file": f.get_file(),
			"fps_software": Performance.get_monitor(Performance.TIME_FPS),
			"draw_calls": int(Performance.get_monitor(Performance.RENDER_TOTAL_DRAW_CALLS_IN_FRAME)),
			"objects": int(Performance.get_monitor(Performance.RENDER_TOTAL_OBJECTS_IN_FRAME)),
			"primitives": int(Performance.get_monitor(Performance.RENDER_TOTAL_PRIMITIVES_IN_FRAME)),
			"nodes": int(Performance.get_monitor(Performance.OBJECT_NODE_COUNT)),
			"video_mem_mb": snappedf(Performance.get_monitor(Performance.RENDER_VIDEO_MEM_USED) / 1048576.0, 0.1),
			"static_mem_mb": snappedf(Performance.get_monitor(Performance.MEMORY_STATIC) / 1048576.0, 0.1)})
		times.pop_front()
	if times.is_empty():
		var fa := FileAccess.open(out + "/stats.json", FileAccess.WRITE)
		fa.store_string(JSON.stringify({"frames": frames, "seconds": snappedf(t, 0.01), "shots": stats}, "  "))
		fa.close()
		quit()
		return true
	return false
