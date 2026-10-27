title: 3D look at 2D cost: baking sprites from 3D at startup
tags: prerender bake 3d to 2d sprite render to texture subviewport impostor detailed castle building isometric hybrid
styles: low-poly realistic painted
cost: low
engine: godot4
summary: build a model from primitives, render it once in a SubViewport, and use the image as a sprite

## Method
A `SubViewport` (transparent background) with a camera, a light and the model (CSG or primitive
meshes); render one frame (`render_target_update_mode = UPDATE_ONCE`), wait for `RenderingServer.frame_post_draw`,
take `get_texture().get_image()` into an `ImageTexture`, free the viewport. Bake several angles for
rotation or a few frames for animation. Then the world is 2D: cheap, y-sortable, MultiMesh-able.

## Code
```gdscript
extends Node2D
# Bakes a low-poly tower (cylinder + cone) lit from the top-left into a texture, then shows it as sprites.
signal baked(texture: Texture2D)

func bake(size: Vector2i = Vector2i(256, 256)) -> ImageTexture:
	var vp := SubViewport.new()
	vp.size = size
	vp.transparent_bg = true
	vp.render_target_update_mode = SubViewport.UPDATE_ONCE
	vp.own_world_3d = true
	add_child(vp)
	var cam := Camera3D.new()
	cam.projection = Camera3D.PROJECTION_ORTHOGONAL
	cam.size = 4.0
	cam.position = Vector3(4, 4, 4)
	vp.add_child(cam)
	cam.look_at(Vector3(0, 1, 0))
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-50, -30, 0)
	vp.add_child(sun)
	var stone := StandardMaterial3D.new()
	stone.albedo_color = Color("c9bfa5")
	var body := MeshInstance3D.new()
	var cyl := CylinderMesh.new()
	cyl.height = 2.0
	cyl.top_radius = 0.6
	cyl.bottom_radius = 0.7
	cyl.radial_segments = 12
	body.mesh = cyl
	body.material_override = stone
	body.position.y = 1.0
	vp.add_child(body)
	var roof := MeshInstance3D.new()
	var cone := CylinderMesh.new()
	cone.top_radius = 0.0
	cone.bottom_radius = 0.85
	cone.height = 1.2
	cone.radial_segments = 12
	roof.mesh = cone
	var red := StandardMaterial3D.new()
	red.albedo_color = Color("b5523b")
	roof.material_override = red
	roof.position.y = 2.6
	vp.add_child(roof)
	await RenderingServer.frame_post_draw
	var tex := ImageTexture.create_from_image(vp.get_texture().get_image())
	vp.queue_free()
	return tex

func _ready() -> void:
	var tex := await bake()
	for i in 5:
		var s := Sprite2D.new()
		s.texture = tex
		s.position = Vector2(120 + i * 120, 400 + (i % 2) * 60)
		s.scale = Vector2.ONE * 0.5
		add_child(s)
	baked.emit(tex)
```

## Validation
Render after the bake (`--at 1.5`): sprites show the shaded model with a transparent background.
Video memory grows by the baked textures only (size² × 4 bytes each).

## Adaptation
Painted: bake larger, then add a canvas shader with posterize + edge darkening. Pixel: bake at low
resolution (64²) and draw with nearest filtering; bake 8 directions for units.
