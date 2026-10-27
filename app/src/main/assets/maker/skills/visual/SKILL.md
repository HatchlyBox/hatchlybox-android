---
name: visual
description: Visual Intelligence - plan, research, create, render, evaluate and improve the look of a game or app in any art style (pixel, flat, painted, low-poly, realistic-lite, cinematic) with a searchable technique library, a real screenless renderer for Godot and image measurements. Use for every Godot game and whenever the user asks for beautiful, detailed, atmospheric or stylised graphics.
---

# Visual Intelligence

You don't just write graphics code: you **decide** the approach, **render** the result, **look** at it,
**measure** it, and **improve** it until it meets the plan. The `visual` command gives you the tools;
the knowledge lives outside your context until you search for it.

## The loop

1. **Interpret** the prompt: subject, mood, references, style. `visual show principles/art-direction`.
2. **Budget**: `visual device` (the phone, its tier, draw-call/sprite/light/memory budgets).
3. **Plan**: `visual plan` writes `design/visual-plan.md`. Fill it: style, dimension
   (`visual show principles/dimension-choice`), palette with roles, shape language, light direction,
   techniques, quality targets. Decide; don't ask the user.
4. **Research**: `visual find <need>` for each thing to make ("forest", "castle", "fog", "army", "water
   reflection", "pixel ui"), then `visual show <id>`. Recipes have tested Godot 4 code: adapt the
   parameters to your style (each has an Adaptation section). `visual find` also returns what earlier
   apps learned (`learned/...`).
5. **Create** in small visible steps: the background and palette first, then the playfield, characters,
   effects, UI.
6. **Render**: `visual render --at 1,4 [--tap X,Y@T]` runs the project with a real (software) OpenGL
   renderer, no screen needed, and saves screenshots, a small `sheet.png` and stats (draw calls, objects,
   nodes, video memory: the same as on the phone). Look at `sheet.png` (not the full-size shots).
   Use `--tap` to get past menus into gameplay; render the busiest moment too.
7. **Evaluate**: `visual check <shots> --style <style>` measures contrast, colourfulness, palette, detail
   and its placement, safe areas, letterboxing and pixel crispness, and warns. Then judge the sheet
   against the plan: mood words, focal point, readability (`principles/readability`), consistency.
   Compare iterations with `--against <previous shot>`.
8. **Improve**: write what was wrong and the change in the plan's Iterations table; fix; render again.
   Stop when the quality targets and budgets are met (2–4 rounds is normal).
9. **Learn**: for a technique that worked well and is reusable, `visual learn <name>` and fill it in
   (what, when, how, numbers). Hatchlybox adds it to the shared library for future apps.

If `visual render` says the renderer isn't installed, run `visual setup` (about 50 MB) once. On a computer
it may answer with a `sudo` command instead: don't run it; say in `acceptance.json` that the user should, and
judge from the code and `visual check` meanwhile. Renders are slow-ish (software): a few seconds each; fps in
stats is software fps, ignore it.

Each version is judged with the renderer it ships with: `visual render` is the Android version (Compatibility,
phone budgets); `visual render --for linux|macos|windows` is a computer's version (Forward+, the computer
budgets in `visual device`); `--for web` is the browser's (Compatibility). A game that looks good on the
phone should also use the computers' headroom (better lights, shadows, effects behind a quality setting).

## Library (visual list for everything)

- principles: art-direction, color, composition, readability, typography-ui, motion-juice, pixel-art,
  dimension-choice, mobile-performance
- recipes: terrain-autotile, terrain-blend-shader, parallax-layers, sky-gradient, lighting-2d,
  particles-2d, water-2d, fog-2d, sprite-shaders, procedural-buildings, vegetation-instancing,
  crowds-armies, isometric-2-5d, prerender-sprites, light-3d, procedural-mesh-3d, sprite-animation,
  post-processing-2d, camera-2d, screen-transitions, svg-assets, dithering, color-grading-lut,
  flow-map-water, wfc-maps; 3D: matcap-3d, toon-outline-3d, triplanar-terrain-3d, height-fog-3d,
  wind-sway-3d, gpu-crowds-3d, impostors-3d, pixel-art-3d
- Compose recipes (Kotlin apps): compose-motion, compose-shared-elements, compose-predictive-back,
  compose-agsl-shaders, compose-glass-depth, compose-typography, compose-charts,
  compose-loading-states, compose-haptics, compose-adaptive-layouts
- adapters: godot4 (primary), compose-canvas (Kotlin apps)

## Rules

- One style, one palette, one light direction for the whole game. Mixed styles look amateur.
- Gameplay readability beats decoration; the busiest scene must stay within `visual device` budgets.
- Never leave a render with "almost blank" or "low contrast" warnings unexplained.
- Kotlin (Compose) apps: same principles and `visual check` on `hatchlybox peek` screenshots; there's no
  screenless renderer for Compose.
- Renders go to `design/renders/` (not committed); the plan and learned recipes are kept with the app.
