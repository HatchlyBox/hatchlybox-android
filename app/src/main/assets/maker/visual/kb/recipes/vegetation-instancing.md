title: Forests and grass with MultiMesh instancing
tags: vegetation trees forest grass bushes foliage instancing multimesh wind sway lush nature thousands
styles: flat painted pixel low-poly
cost: low
engine: godot4
summary: thousands of trees or grass blades in one draw call with MultiMeshInstance2D and a wind shader

## Method
One mesh (a tree quad or a small polygon), a `MultiMesh` with a transform and colour per instance,
placed by a noise/Poisson rule, sorted by y so lower trees overlap higher ones. Wind via a vertex shader
using the instance position for phase. One draw call for the whole forest.

## Code
```gdscript
extends MultiMeshInstance2D
# A forest: N stylised trees (triangle canopy + trunk) as one MultiMesh, varied by scale and colour.
@export var count := 1500
@export var area := Rect2(0, 0, 720, 1280)
@export var dark := Color("2e4a3f")
@export var light := Color("6fa36b")

func tree_mesh() -> ArrayMesh:
	var st := SurfaceTool.new()
	st.begin(Mesh.PRIMITIVE_TRIANGLES)
	var pts := [Vector2(-3, 0), Vector2(3, 0), Vector2(3, -8), Vector2(-3, 0), Vector2(3, -8), Vector2(-3, -8),
		Vector2(-14, -6), Vector2(14, -6), Vector2(0, -40)]
	for i in pts.size():
		st.set_color(Color(0.35, 0.25, 0.2) if i < 6 else Color.WHITE)
		st.add_vertex(Vector3(pts[i].x, pts[i].y, 0))
	return st.commit()

func _ready() -> void:
	var noise := FastNoiseLite.new()
	noise.frequency = 0.004
	var rng := RandomNumberGenerator.new()
	rng.seed = 11
	var spots: Array[Vector2] = []
	while spots.size() < count:
		var p := Vector2(rng.randf_range(area.position.x, area.end.x), rng.randf_range(area.position.y, area.end.y))
		if noise.get_noise_2dv(p) > -0.1:
			spots.append(p)
	spots.sort_custom(func(a: Vector2, b: Vector2) -> bool: return a.y < b.y)
	var mm := MultiMesh.new()
	mm.transform_format = MultiMesh.TRANSFORM_2D
	mm.use_colors = true
	mm.mesh = tree_mesh()
	mm.instance_count = spots.size()
	for i in spots.size():
		var s := rng.randf_range(0.7, 1.4) * lerpf(0.6, 1.2, spots[i].y / area.end.y)
		mm.set_instance_transform_2d(i, Transform2D(0.0, Vector2(s, s), 0.0, spots[i]))
		mm.set_instance_color(i, dark.lerp(light, rng.randf()))
	multimesh = mm
	var m := ShaderMaterial.new()
	m.shader = load("res://sway.gdshader")
	material = m
```
```gdshader
// res://sway.gdshader
shader_type canvas_item;
// Wind for MultiMesh vegetation: the top bends more; phase from the instance position (MODEL_MATRIX).
uniform float strength = 3.0;
void vertex() {
	float phase = MODEL_MATRIX[3].x * 0.02 + MODEL_MATRIX[3].y * 0.013;
	VERTEX.x += sin(TIME * 1.6 + phase) * strength * clamp(-VERTEX.y / 40.0, 0.0, 1.0);
}
```

## Validation
stats.json: draw_calls stays ~1 for the forest regardless of count; render and look for even, not
clumped or gridded, placement (the sheet).

## Adaptation
Painted: a tree texture on a QuadMesh instead of polygons (`mm.mesh = QuadMesh` + texture on the node).
Pixel: integer positions, a 16×24 tree sprite, no rotation. Low-poly 3D: MultiMeshInstance3D with a cone+cylinder.
