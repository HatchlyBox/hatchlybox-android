title: Procedural 3D terrain and meshes
tags: procedural mesh 3d terrain heightmap surfacetool vertex colors low-poly hills mountains generation
styles: low-poly realistic
cost: medium
engine: godot4
summary: build terrain or props from code with SurfaceTool, vertex colours by height and flat or smooth normals

## Code
```gdscript
extends MeshInstance3D
# A low-poly heightmap terrain with colour by height (water, sand, grass, rock, snow), flat shaded.
@export var cells := 64
@export var scale_xz := 2.0
@export var height := 14.0

func colour_at(h: float) -> Color:
	if h < 0.05: return Color("3f6e8c")
	if h < 0.12: return Color("e8c98a")
	if h < 0.45: return Color("5e8d4f")
	if h < 0.7: return Color("7d746b")
	return Color("eef2f6")

func _ready() -> void:
	var noise := FastNoiseLite.new()
	noise.frequency = 0.035
	noise.fractal_octaves = 5
	var st := SurfaceTool.new()
	st.begin(Mesh.PRIMITIVE_TRIANGLES)
	var h := func(x: int, z: int) -> float: return clampf(noise.get_noise_2d(x, z) * 0.9 + 0.35, 0.0, 1.0)
	for z in cells:
		for x in cells:
			var quad := [Vector2i(x, z), Vector2i(x + 1, z), Vector2i(x + 1, z + 1), Vector2i(x, z + 1)]
			for tri in [[0, 1, 2], [0, 2, 3]]:
				var avg := 0.0
				for k in tri:
					avg += h.call(quad[k].x, quad[k].y) / 3.0
				st.set_color(colour_at(avg))
				for k in tri:
					var c: Vector2i = quad[k]
					var hv: float = maxf(h.call(c.x, c.y), 0.05)
					st.add_vertex(Vector3((c.x - cells / 2.0) * scale_xz, hv * height, (c.y - cells / 2.0) * scale_xz))
	st.generate_normals()
	mesh = st.commit()
	var m := StandardMaterial3D.new()
	m.vertex_color_use_as_albedo = true
	material_override = m
```
Faces are flat-shaded because no vertices are shared; call `st.index()` before `generate_normals()` for
smooth hills.

## Validation
Render with a camera and sun (`recipes/light-3d`); primitives in stats = 2 × cells² triangles: keep
within the device tier.

## Adaptation
Islands: multiply height by a radial falloff. Pixel-3D look: render into a low-res SubViewport and scale up.
