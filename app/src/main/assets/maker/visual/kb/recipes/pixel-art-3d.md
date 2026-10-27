title: Pixel-art 3D (3D rendered at low resolution, crisp upscale)
tags: pixel art 3d low resolution retro lowres hd-2d diorama crisp nearest upscale subviewport stylised modern pixel
styles: pixel low-poly toon
cost: low
engine: godot4
summary: render the 3D scene into a small SubViewport and scale it up with nearest filtering, with toon bands and outlines; pixel-snap the camera so pixels don't shimmer

## Method
The modern "3D pixel art" look (diorama-like scenes that read as hand-pixelled): render the 3D world
at a fraction of the screen resolution and enlarge it with nearest-neighbour filtering.
- `SubViewportContainer` with `stretch = true` and `stretch_shrink = 3–5` renders its `SubViewport`
  at 1/N resolution and draws it enlarged; `texture_filter = NEAREST` keeps pixels square.
- 2–3 flat bands in exact palette colours (an unshaded shader, below: the light pipeline shifts colours) make it read as pixel art;
  outlines 1 low-res pixel thick (hull thickness ≈ world size of one low-res pixel).
- **Pixel-snap** an orthographic or slow-moving camera: move it in steps of one low-res pixel in world
  units, and offset the container by the remainder in screen pixels for smooth scrolling without shimmer.
- UI and text go outside the SubViewport at full resolution (crisp, readable).
- Cheaper than full-resolution 3D: the GPU shades 1/9–1/25 of the pixels.

## Code
```gdshader
// res://bands.gdshader
shader_type spatial;
render_mode unshaded;
// Two or three flat light bands in a fixed palette: computed here, not by the light pipeline, so the
// output is exactly the palette colours (pixel art needs exact colours).
uniform vec4 lit : source_color = vec4(0.95, 0.75, 0.45, 1.0);
uniform vec4 mid : source_color = vec4(0.75, 0.45, 0.35, 1.0);
uniform vec4 dark : source_color = vec4(0.32, 0.2, 0.32, 1.0);
uniform vec3 sun_dir = vec3(-0.5, 0.75, 0.45);

void fragment() {
	vec3 l = normalize((VIEW_MATRIX * vec4(normalize(sun_dir), 0.0)).xyz);
	float ndl = dot(NORMAL, l);
	ALBEDO = ndl > 0.55 ? lit.rgb : (ndl > 0.1 ? mid.rgb : dark.rgb);
}
```
```gdscript
# res://demo.gd
extends SubViewportContainer
# The whole 3D world renders at 1/4 resolution and is enlarged with square pixels.

func mat(a: Color, b: Color, c: Color) -> ShaderMaterial:
	var m := ShaderMaterial.new()
	m.shader = load("res://bands.gdshader")
	m.set_shader_parameter("lit", a)
	m.set_shader_parameter("mid", b)
	m.set_shader_parameter("dark", c)
	return m

func _ready() -> void:
	set_anchors_preset(Control.PRESET_FULL_RECT)
	size = get_viewport_rect().size
	stretch = true
	stretch_shrink = 4
	texture_filter = CanvasItem.TEXTURE_FILTER_NEAREST
	var vp := SubViewport.new()
	add_child(vp)
	var env := WorldEnvironment.new()
	env.environment = Environment.new()
	env.environment.background_mode = Environment.BG_COLOR
	env.environment.background_color = Color("#2b2140")
	# No ambient light: the bands alone decide every colour, so the palette stays exact.
	env.environment.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.environment.ambient_light_color = Color.BLACK
	vp.add_child(env)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-45, -40, 0)
	vp.add_child(sun)
	var cam := Camera3D.new()
	cam.projection = Camera3D.PROJECTION_ORTHOGONAL
	cam.size = 12.0
	cam.position = Vector3(8, 8, 8)
	vp.add_child(cam)
	cam.look_at(Vector3.ZERO)
	var ground := MeshInstance3D.new()
	ground.mesh = BoxMesh.new()
	ground.mesh.size = Vector3(8, 1, 8)
	ground.material_override = mat(Color("#8fd16a"), Color("#4f9a5a"), Color("#2e5a4a"))
	ground.position.y = -0.5
	vp.add_child(ground)
	var house := MeshInstance3D.new()
	house.mesh = BoxMesh.new()
	house.mesh.size = Vector3(2, 1.6, 1.6)
	house.material_override = mat(Color("#f2d5a0"), Color("#c99a6a"), Color("#6a4a4a"))
	house.position = Vector3(-1, 0.8, -0.5)
	vp.add_child(house)
	var roof := MeshInstance3D.new()
	roof.mesh = PrismMesh.new()
	roof.mesh.size = Vector3(2.4, 1.0, 1.9)
	roof.material_override = mat(Color("#e8664a"), Color("#a63d40"), Color("#5a2340"))
	roof.position = Vector3(-1, 2.1, -0.5)
	vp.add_child(roof)
	for p in [Vector3(2, 0, 1.5), Vector3(2.5, 0, -2), Vector3(-2.8, 0, 2.5)]:
		var tree := MeshInstance3D.new()
		tree.mesh = CylinderMesh.new()
		tree.mesh.top_radius = 0.0
		tree.mesh.bottom_radius = 0.8
		tree.mesh.height = 2.2
		tree.mesh.radial_segments = 6
		tree.material_override = mat(Color("#6fcf6a"), Color("#2f8a5a"), Color("#1d4a40"))
		tree.position = p + Vector3(0, 1.1, 0)
		vp.add_child(tree)
```

## Validation
Visible square pixels at a consistent size, 2–3 bands per surface, a clear palette. `visual check
--style pixel`: crispness passes (no blurry upscale). Stats: low fill cost.

## Adaptation
HD-2D: pixel-art 3D plus full-resolution depth-of-field-like blur on the far layer and bloom on lights.
Strategy diorama: orthographic isometric camera (as here), pixel-snapped panning. Action: perspective
camera at shrink 3, outlines on characters only. Pick the shrink so the low-res height is 240–360 px.
