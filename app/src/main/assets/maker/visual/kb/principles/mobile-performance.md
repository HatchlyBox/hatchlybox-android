title: Phone performance and measuring it
tags: performance fps draw calls batching overdraw memory textures battery optimisation profile budget mobile
styles: all
cost: none
engine: godot4
summary: what costs frame time on phones, how to measure it here, and the fixes that matter

## What costs
1. **Draw calls**: each texture/material change breaks 2D batching. Use atlases (one texture for many
   sprites), the same material, `MultiMeshInstance2D/3D` for repeated things.
2. **Overdraw / fill rate**: full-screen transparent layers, big particles, many stacked shaders.
   Phones are fill-rate bound: keep full-screen passes ≤ 2.
3. **Lights and shadows**: each Light2D re-draws what it touches; shadows cost more. Bake light into
   sprites or use additive glow sprites instead.
4. **Nodes and scripts**: thousands of nodes with `_process` are slow in GDScript. Simulate crowds in
   arrays and draw with MultiMesh; disable processing of off-screen things (`VisibleOnScreenEnabler2D`).
5. **Memory**: textures dominate; ≤ 2048 px, compress (VRAM compression ETC2/ASTC for Android in import),
   mipmaps only for 3D/scaled.

## Measure here
`visual render` writes stats.json per shot: draw_calls, objects, primitives, nodes, video memory.
These match the phone; fps here is software rendering (slow) — ignore it. Compare with `visual device`.
Render the busiest moment (most enemies, effects) with `--at` late and `--tap` to play.

## Fixes by symptom
- draw calls high → atlas/AtlasTexture, merge TileMapLayers, MultiMesh, fewer distinct materials.
- objects/nodes high → pool and reuse, MultiMesh, cull off-screen.
- video memory high → smaller/compressed textures, fewer unique sprites, reuse via modulate/palette swap.

## Battery
Turn-based or menu screens: `OS.low_processor_usage_mode = true` (redraws only on change).
