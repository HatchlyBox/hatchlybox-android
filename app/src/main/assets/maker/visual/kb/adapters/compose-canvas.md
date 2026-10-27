title: Jetpack Compose Canvas adapter (Kotlin apps)
tags: compose canvas kotlin android drawscope brush gradient animation app ui illustration
styles: flat ui illustration
cost: none
engine: compose
summary: use the same principles in a Kotlin app: DrawScope shapes, brushes, animation, Material 3 theme

## Mapping
- Palette → `MaterialTheme.colorScheme` plus a few named Colors for illustrations.
- Procedural art → `Canvas { drawPath(...); drawCircle(brush = Brush.radialGradient(...)) }`.
- Motion → `animateFloatAsState`, `rememberInfiniteTransition`, `Animatable` with `spring()`.
- Particles → a list of data classes updated in `withFrameNanos`, drawn in one Canvas.
- Typography → Material 3 type scale; a display font from `res/font`.
- Rendering here: there's no screenless renderer for Compose; check on the phone with `hatchlybox peek`
  (see-it-run skill), then `visual check` its screenshots (`.peek/*.png`).
