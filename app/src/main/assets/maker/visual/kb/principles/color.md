title: Colour and palettes
tags: color colour palette harmony hue saturation value contrast accent gradient lighting mood
styles: all
cost: none
engine: any
summary: build a 5-8 colour palette with roles, keep values readable, use saturation and hue for focus

## Purpose
Colour sets mood and steers attention. Most "ugly" games fail on value (light/dark) structure, not hue.

## Method
- **Value first**: background darkest or lightest, playfield mid, characters and pickups at the
  extremes. Squint test: convert a render to grey (`visual check` brightness/contrast); the player and
  dangers must still pop.
- **Palette with roles** (5–8 colours + their light/dark ramps):
  background, ground/terrain, structures, hero, danger, reward/pickup, UI text, UI accent.
- **Ramps**: for each base colour make 3–5 shades. Shift hue as you go: shadows toward blue/purple,
  lights toward yellow/orange (hue shifting); don't just darken with black.
- **Saturation budget**: most of the screen low-to-mid saturation; the highest saturation only on what
  matters (player, danger, rewards, buttons).
- **Harmonies**: analogous (calm), complementary accent (focus: blue world, orange hero),
  triadic (playful). Pick one.
- **Atmospheric perspective**: far layers lighter (or darker at night), less saturated, toward the sky colour.

## Godot
- Keep the palette in one script (`const PAL := {"bg": Color("1b1f3a"), ...}`) or a Theme; never scatter hex.
- `CanvasModulate` tints the whole 2D world (night, dusk); `WorldEnvironment` + `Environment.adjustment_*`
  for global brightness/contrast/saturation; a gradient `GradientTexture1D` as a colour ramp for
  procedural art.

## Proven palettes (starting points; adapt)
- Dusk fantasy: 1b1f3a 3b2f5c 7a4e7e d17b6f f4b860 f6e7c1 2e4a3f 6fa36b
- Cozy village: 2b2d42 6c584c a98467 adc178 dde5b6 f0ead2 e07a5f 81b29a
- Neon night: 0d0221 261447 2de2e6 ff3864 f6019d ffd319 541388 ffffff
- Desert: 3d2c2e 7b4b3a c46d3b e8a75a f3d18b f9f1dc 4f6d7a 92b6b1
- Frost: 0b1d2c 1f3b54 3f6e8c 7fb2c9 c9e7f2 ffffff e86a5c f3c969

## Validation
`visual check`: colourfulness in the mood's range; "low contrast" warning means value structure failed.
In the sheet the eye should land on the hero/goal first.
