title: Game readability on a phone
tags: readability gameplay clarity contrast hud enemies pickups feedback telegraph small screen
styles: all
cost: none
engine: any
summary: make players, threats, pickups and interactive things instantly recognisable at phone size

## Rules
- **Gameplay over decoration**: background detail lower contrast and saturation than anything interactive.
- **Colour coding** consistent everywhere: e.g. danger red/orange, rewards gold/yellow, friendly blue/green,
  interactive things outlined or glowing. Don't reuse the danger colour in the scenery.
- **Size**: on a 6" phone, a tappable or important object ≥ 7 mm (about 44 px at 160 dpi scale; in a
  720-wide portrait viewport ≥ 48 px). Text ≥ 24 px in a 720×1280 viewport for body, 36+ for headings.
- **Shape coding** as well as colour (colour-blind players): enemies spiky, pickups round, hazards striped.
- **Telegraph** threats: wind-up animation, flash, or a ground marker before damage.
- **Feedback** for every action within 100 ms: sound + flash/particles/shake (`principles/motion-juice`).
- **Contrast against every background**: give sprites a 1–2 px darker outline or a soft drop shadow.
- **HUD**: at most 3–4 numbers on screen; icons with numbers; inside the safe area.

## Validation
`visual render` mid-game (`--at` after the action starts, `--tap` to play), then `visual check`:
no "low contrast" warning; in the sheet you can point at the player, a threat and a pickup in a second.
