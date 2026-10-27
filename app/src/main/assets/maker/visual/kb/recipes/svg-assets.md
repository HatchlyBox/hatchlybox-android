title: Writing assets as SVG (icons, characters, props, UI)
tags: svg vector assets icons characters props ui art create images without editor scalable crisp
styles: flat vector ui
cost: none
engine: godot4
summary: author crisp scalable art as SVG text files that Godot imports as textures

## Method
Godot imports `.svg` as a texture (rasterised at import; set the import `svg/scale` for size). Good for
icons, flat characters, UI frames, props. Keep each SVG simple: a few paths with the palette's colours,
one light direction (lighter shape on the lit side), a darker outline or shadow shape.

## Example
```
<svg xmlns="http://www.w3.org/2000/svg" width="128" height="128" viewBox="0 0 128 128">
  <ellipse cx="64" cy="116" rx="34" ry="8" fill="#000" opacity=".25"/>
  <path d="M64 10 L102 40 L102 104 L26 104 L26 40 Z" fill="#c9bfa5"/>
  <path d="M64 10 L102 40 L102 104 L64 104 Z" fill="#a89f88"/>
  <path d="M18 44 L64 6 L110 44 Z" fill="#b5523b"/>
  <path d="M64 6 L110 44 L64 44 Z" fill="#8e3f2d"/>
  <rect x="54" y="72" width="20" height="32" rx="10" fill="#3b2f5c"/>
  <rect x="36" y="54" width="12" height="14" rx="2" fill="#ffd27a"/>
</svg>
```
Write with the file tool to `godot/art/house.svg`; use as `preload("res://art/house.svg")` after
`godot --headless --path godot --import`.

## Rules
- Size the viewBox to the use (icons 128, sprites 64–256); scale at import, not at runtime, for crispness.
- Shared palette; no gradients for flat style (or one subtle linear gradient for depth).
- For many variants, generate SVG text from a script (Python string templates) instead of copying files.

## Validation
`visual render` a scene using them; `visual check --style flat`: tight palette (no "main colour bins" warning).
