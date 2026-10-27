title: Art direction from a prompt
tags: style intent mood reference concept art direction decide look theme genre
styles: all
cost: none
engine: any
summary: turn any creative prompt into a concrete, consistent visual direction before making anything

## Purpose
A prompt names a feeling ("cinematic", "cozy", "epic", "creepy") and a subject. Art direction turns that
into decisions every asset obeys, so the result looks designed rather than assembled.

## Method
1. **Extract**: subject, genre, mood words, named references, audience, platform (a phone held in hand).
2. **Pick 3 mood words** and translate each into visual levers:
   | Mood | Value (light/dark) | Saturation | Shapes | Motion |
   |---|---|---|---|---|
   | cozy | mid-light, warm | medium | round, soft | slow, bouncy |
   | epic / cinematic | high contrast, deep shadows | low-medium, one strong accent | tall, vertical, big scale gaps | slow camera, parallax |
   | creepy | dark, cold | low, sickly accent | thin, angular, asymmetric | sudden |
   | playful | bright | high | round, chunky | squash & stretch |
   | serene | light, cool | low | horizontal, long | drifting |
   | tense / action | high contrast | high accent on danger | sharp diagonals | fast, shake |
3. **Choose one style** (pixel, flat vector, hand-painted look, low-poly, realistic-lite) for everything:
   mixing styles is the most common reason things look amateur. If you must mix (3D-baked sprites in a
   2D world), unify with one palette, one light direction, one outline rule.
4. **Choose the dimension** with `principles/dimension-choice` and `visual device`.
5. **Write it down** in design/visual-plan.md (`visual plan`): palette with roles, shape language,
   light direction, typography, camera. Every later choice is checked against it.

## Consistency rules
- One light direction for the whole game (usually top-left): shading on sprites, shadows, highlights.
- One outline rule: none, or the same width and colour treatment everywhere (dark hue of the fill, not black).
- One level of detail: the hero isn't 10× more detailed than the ground.
- Pixel art: one pixel size everywhere (never scale sprites by different factors).
- UI belongs to the world: its colours come from the palette, its corners/borders echo the shape language.

## Validation
Render (`visual render`) and look at the sheet next to the plan: can you name the 3 mood words from the
screenshot alone? Does anything break the style? `visual check` colourfulness and contrast should match
the mood table (cozy: colourfulness 30–60; cinematic: contrast sd > 55; serene: < 40).
