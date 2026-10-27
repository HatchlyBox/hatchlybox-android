title: Choosing 2D, 2.5D, baked 3D or light 3D
tags: 2d 2.5d 3d isometric dimension decide approach hybrid prerendered performance strategy armies castles
styles: all
cost: none
engine: godot4
summary: decide the rendering approach from the visual goal and the phone's budget, not by habit

## Decision
| Goal in the prompt | Best fit on a phone | Why |
|---|---|---|
| many units, big maps, strategy, city builders | **2.5D isometric** sprites (y-sorted), MultiMesh crowds | thousands of units cheaply; looks rich; readable |
| detailed castles/buildings that must look 3D | **3D-baked sprites**: model or draw once, render to texture at startup (`recipes/prerender-sprites`) | 3D look at 2D cost |
| platformer, shooter, puzzle, arcade | **2D** with parallax, lights, particles | clearest, cheapest |
| racing, flying, exploration "into the screen" | **light 3D** (gl_compatibility, low-poly, baked/vertex lighting) | depth is gameplay |
| cinematic atmosphere | 2D layered parallax + light + fog shaders, or 3D with fog and few lights | mood is lighting, not polygons |
| retro | pixel 2D (`principles/pixel-art`) | style and cheap |
| board/card/UI-heavy | 2D Controls with Theme, tweened | text and layout matter most |

## Rules of thumb (check `visual device` for the tier)
- Everything must run at ≥ 30 fps in the busiest scene on a **mid** phone; aim for 60.
- 2D costs scale with draw calls and overdraw (big transparent layers stacked); 3D with draw calls,
  lights and shadows. Software renders here give exact draw calls/objects: compare them to the budget.
- Prefer **hybrid**: a 2D world with a few 3D-looking hero assets (baked), or a 3D world with 2D sprites
  (billboards) for crowds and vegetation.
- Godot on Android: use the **gl_compatibility** renderer (every phone); `forward_plus`/`mobile` need Vulkan
  and fail on some phones. No SDFGI, no volumetric fog, no SSR in compatibility: fake them
  (`recipes/fog-2d`, `recipes/water-2d`, `recipes/light-3d`).

## Record
Write the choice and the reason in design/visual-plan.md ("Dimension") with the budget numbers.
