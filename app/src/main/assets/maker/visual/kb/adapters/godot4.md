title: Godot 4 adapter (primary engine)
tags: godot godot4 engine adapter gdscript scenes nodes renderer compatibility android export setup
styles: all
cost: none
engine: godot4
summary: how Visual Intelligence techniques map onto Godot 4.7 (gl_compatibility) for Android

## Constraints
- Renderer: `gl_compatibility` (OpenGL ES 3). Available: CanvasItem/Spatial shaders, Light2D/
  PointLight2D/DirectionalLight2D with normal maps, GPUParticles2D/3D (compat supports them; prefer
  CPUParticles for < 200 particles), WorldEnvironment glow + tonemap + adjustments + fog (depth fog),
  MultiMesh, SubViewport render-to-texture. Not available: SDFGI, SSR, SSAO, volumetric fog.
- Shaders: Godot shading language (`shader_type canvas_item;` / `spatial;`), `hint_screen_texture` for
  screen-reading effects (one BackBufferCopy per layer).
- Node choices: Sprite2D/AnimatedSprite2D, TileMapLayer (terrains, y-sort), Polygon2D/Line2D for
  vector art, `_draw()` for procedural shapes, CanvasLayer for HUD and parallax (`Parallax2D`),
  Camera2D with limits/smoothing, MeshInstance3D + StandardMaterial3D for 3D.
- Assets without an editor: SVG files (imported as textures; scale via import `svg/scale`), PNG from
  Python or `Image` at runtime, meshes from `SurfaceTool`/`ArrayMesh`, primitive meshes, CSG for blockouts.

## Workflow with the tools
1. `visual plan`, `visual device`, `visual find <need>` per technique.
2. Build in small steps; after each visible milestone `visual render --at ...` and `visual check`.
3. Look at sheet.png; note what's wrong in the plan's Iterations table; fix; render again
   (`--against` the previous shot to confirm the change).
4. Stop when the plan's quality targets and budgets are met. `visual learn <name>` for what's reusable.

## Gotchas
- `--check-only` validates scripts only; shaders compile at render time: errors appear in
  `visual render` output (`SHADER ERROR`).
- `TileMap` is deprecated: use `TileMapLayer`. `ParallaxBackground` is superseded by `Parallax2D`.
- Particles: `GPUParticles2D` needs a `ParticleProcessMaterial`; on compat it works but very old GPUs
  are slower: `CPUParticles2D` is the safe choice for small effects.
