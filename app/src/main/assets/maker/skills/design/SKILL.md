---
name: design
description: Make the app look finished and polished with Material 3 - its own palette (or the wallpaper's colours), dark mode, clear typography and spacing, empty states, adaptive layouts for landscape and tablets, and accessibility. Use for every app before calling it done, and whenever the user asks for nicer looks, colours, a theme or a cleaner layout.
---

# Looking finished

Working code: `example/Theme.kt` (`AppTheme` with the app's palette, optional dynamic colour and dark mode, `EmptyState`,
`SectionHeader`, and a screen that adapts to wide windows).

Beyond the basics: tested Compose recipes for what makes an app feel premium. `visual list recipes`,
then `visual show <name>`: compose-motion (springs, staggered entrances, rolling numbers),
compose-shared-elements (list to detail), compose-predictive-back, compose-typography (font pairing),
compose-agsl-shaders (living backgrounds), compose-glass-depth, compose-charts, compose-loading-states,
compose-haptics, compose-adaptive-layouts. Before writing screens, `visual show` compose-motion and
compose-typography (every app needs both) and at least two more that fit the brief, and use them.

## Theme

- Wrap the app in `AppTheme { ... }` (example) instead of a bare `MaterialTheme`. It uses the app's own
  palette, light and dark: put the colours from `design/brief.md` (the `direction` skill) in it, so the app
  has an identity of its own. `dynamicColor = true` follows the wallpaper instead; use it only when the
  brief or the request asks for that (every app would otherwise wear the same colours).
- Carry the brief's palette into the launcher icon background and the app's key moments.
- Colours only from `MaterialTheme.colorScheme` (`primary`, `surfaceContainer`…), never hard-coded hex
  in screens: then dark mode (and dynamic colour, if chosen) just work.

## Layout

- Spacing on an 8 dp grid: 16 dp screen padding, 8 or 12 dp between items, 24 dp between sections.
- Typography from `MaterialTheme.typography`: `headlineSmall` for a screen's main number or title,
  `titleMedium` for card titles, `bodyMedium` for text, `labelMedium` for small labels.
- Lists: `LazyColumn` with `key`s, `ListItem` rows; cards (`ElevatedCard`, `OutlinedCard`) for grouped
  content.
- Every list or screen that can be empty shows an `EmptyState`: an icon, one line on what goes here, and
  the button that adds the first item.
- Wide windows (landscape, tablets, foldables): `BoxWithConstraints`, and above 600 dp show two panes
  or a centred column at most 640 dp wide (example).

## Polish checklist

- A real app name in `strings.xml`, a launcher icon that matches the app (see AGENTS.md).
- Touch targets at least 48 dp; every icon button has a `contentDescription`.
- Text contrast: on-colours on their colours (`onPrimary` on `primary`), never grey on grey.
- Feedback for every action: a Snackbar or a visible change.
- Numbers formatted for people: `NumberFormat.getCurrencyInstance()`, dates with `DateTimeFormatter`.
