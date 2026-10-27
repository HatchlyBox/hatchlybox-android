title: Matcap shading: rich, sculpted 3D lighting for almost nothing
tags: matcap material capture lit sphere 3d shading stylised sculpted clay ceramic metal toy cheap mobile unshaded
styles: low-poly painted realistic toy
cost: low
engine: godot4
summary: one small "lit sphere" texture looked up by the view-space normal replaces real lights: sculpted, consistent shading for any mesh at unshaded cost

## Method
A matcap (material capture) is a picture of a lit sphere. Each pixel of a mesh looks up the matcap
where its view-space normal points (`NORMAL.xy * 0.5 + 0.5`). The result looks lit, glossy or painted
however the sphere was drawn, with no lights, no shadows and no per-light cost: ideal for phones and
for a strong art style (clay, ceramic, chrome, hand-painted). The matcap can be painted (SVG/PNG) or
generated in code (below). Limits: the lighting turns with the camera (it's view-space), and there are
no cast shadows: add a blob shadow (a dark transparent disc under each object) for grounding.

## Code
```gdshader
// res://matcap.gdshader
shader_type spatial;
render_mode unshaded;
// The matcap lit-sphere lookup; tint multiplies it so one matcap serves a whole palette.
uniform sampler2D matcap : source_color, filter_linear, repeat_disable;
uniform vec4 tint : source_color = vec4(1.0);

void fragment() {
	vec2 uv = NORMAL.xy * vec2(0.5, -0.5) + 0.5;
	ALBEDO = texture(matcap, uv).rgb * tint.rgb;
}
```
```gdscript
# res://demo.gd
extends Node3D
# A generated matcap (warm key light from the upper left, cool fill, bright rim) on a few shapes.

func make_matcap(key: Color, fill: Color, rim: Color, gloss: float) -> ImageTexture:
	var s := 128
	var img := Image.create(s, s, false, Image.FORMAT_RGB8)
	var light := Vector3(-0.5, 0.6, 0.65).normalized()
	for y in s:
		for x in s:
			var nx := (x + 0.5) / s * 2.0 - 1.0
			var ny := 1.0 - (y + 0.5) / s * 2.0
			var r2 := nx * nx + ny * ny
			if r2 > 1.0:
				img.set_pixel(x, y, fill)
				continue
			var n := Vector3(nx, ny, sqrt(1.0 - r2))
			var d := clampf(n.dot(light), 0.0, 1.0)
			var c := fill.lerp(key, smoothstep(0.0, 1.0, d))
			c = c.lerp(rim, pow(1.0 - n.z, 3.0) * 0.8)
			var h := light + Vector3(0, 0, 1)
			c = c.lerp(Color.WHITE, pow(clampf(n.dot(h.normalized()), 0.0, 1.0), 40.0) * gloss)
			img.set_pixel(x, y, c)
	return ImageTexture.create_from_image(img)

func _ready() -> void:
	var env := WorldEnvironment.new()
	env.environment = Environment.new()
	env.environment.background_mode = Environment.BG_COLOR
	env.environment.background_color = Color("#20262e")
	add_child(env)
	var cam := Camera3D.new()
	cam.position = Vector3(0, 1.2, 6.5)
	add_child(cam)
	cam.look_at(Vector3(0, 0.3, 0))
	var mc := make_matcap(Color("#ffd9a8"), Color("#3a3f6b"), Color("#9fe3ff"), 0.9)
	var shapes := [SphereMesh.new(), TorusMesh.new(), CapsuleMesh.new(), PrismMesh.new()]
	var tints := [Color("#ff8a65"), Color("#a5d6a7"), Color("#90caf9"), Color("#fff59d")]
	for i in shapes.size():
		var m := MeshInstance3D.new()
		m.mesh = shapes[i]
		var mat := ShaderMaterial.new()
		mat.shader = load("res://matcap.gdshader")
		mat.set_shader_parameter("matcap", mc)
		mat.set_shader_parameter("tint", tints[i])
		m.material_override = mat
		m.position = Vector3((i % 2) * 2.4 - 1.2, 1.4 - (i / 2) * 2.2, 0)
		m.rotation = Vector3(0.4, 0.6 * i, 0)
		add_child(m)
```

## Validation
Shapes read as solid and lit from one direction with a rim; there are no lights in the scene.
`visual check`: good value range; no "low contrast". Draw calls = one per mesh (no light passes).

## Adaptation
Clay or toy: low gloss, warm key, saturated tint. Metal or chrome: paint a horizon (sky above, ground
below) into the matcap with a sharp highlight. Hand-painted: generate, then add brush noise, or paint
the matcap as an SVG in the palette. Use 2–4 matcaps for the whole game (characters, props, terrain
accents) and tint them; mix with real lighting only for the ground and big shadows.
