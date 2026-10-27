---
name: games
description: Make games and drawn graphics with Compose Canvas - a game loop that runs every frame, taps and drags, collisions, a score and a saved best score. Use for arcade games, puzzles, drawing apps, animated charts or anything drawn by hand rather than built from standard widgets.
---

# Games and drawing

Compose's `Canvas` draws shapes every frame; `withFrameNanos` gives the game loop its time step;
`pointerInput` reads taps and drags. No game engine is needed. Working code: `example/Game.kt`
(a ball to tap, with a score, a timer, game over and restart).

## Gradle

Nothing to add: Canvas and gestures are in the template's Compose.

## How it fits together

- **State** (positions, speed, score) in `remember { mutableStateOf(...) }`, or a ViewModel for games
  that must survive rotation.
- **Loop**: `LaunchedEffect(running) { while (running) withFrameNanos { now -> ... } }`. Move things by
  `speed * dt` with `dt` in seconds, so the game runs the same at 60 and 120 Hz.
- **Size**: read the canvas size with `onSizeChanged`; keep everything inside it, in any orientation.
- **Input**: `pointerInput(Unit) { detectTapGestures { pos -> ... } }` or `detectDragGestures`.
- **Draw** in `Canvas { drawCircle(...); drawRect(...); drawLine(...) }`, colours from
  `MaterialTheme.colorScheme`.
- **Best score**: keep it with DataStore (see the data skill) so it survives restarts.

## Rules

- A start screen, a visible score, a pause or game-over state and a restart button.
- Keep work per frame small: no allocation-heavy code and no logging inside `withFrameNanos`.
- Respect the system bars: put the HUD inside the Scaffold's padding.
- Sound: `android.media.ToneGenerator` for beeps, or the media skill for real audio.
