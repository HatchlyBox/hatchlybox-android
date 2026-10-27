# Hatchlybox launcher themes

A theme is a folder in `/root/themes/<id>/` with a `theme.json` (and, if it wants one, a background picture).
Hatchlybox lists it under Themes in Settings → Look and on the home screen's "Theme and background" panel.

**Saved changes show on the phone within two seconds**, while you work: the theme in use is redrawn at every
save, and a theme being made is chosen as soon as it's first saved. So save early and improve it in steps.

## How to work

1. Start from a file, never a blank one. The folder you're given already holds a `theme.json` (a copy of the
   current look). Otherwise `theme new <id> [style|palette|current]` makes one.
2. Change it into one coherent idea (below).
3. `theme check` (in the folder, or `theme check <id>`) prints what the launcher would ignore, and why, and
   contrast warnings. Fix everything until it prints `OK`.
4. `/root/themes/errors.txt` is what Hatchlybox itself ignored when it last loaded the themes.

## The `theme` command

```
theme list                  the themes here (* = in use)
theme new <id> [from]       start a theme from current (default), a built-in style or a palette
theme check [id|file]       what the launcher would ignore and why (default ./theme.json)
theme defaults              every key, its default and range
theme styles                the built-in styles' values (STYLES.json)
theme palettes              the built-in palettes; theme palette <id> for one's colours (PALETTES.json)
theme current               the look in use, as a theme.json (CURRENT.json)
theme use <id>              switch the launcher to a theme or a built-in style
theme errors                errors.txt
```

## theme.json

```json
{
  "name": "Night Arcade",
  "description": "Neon edges on black, like a 90s arcade",
  "style": "neon",
  "dark": "always",
  "colors": {
    "light": { "surface": "#F5F0FF", "text": "#1A1030", "accent": "#D1009B", "glow": "#0091C2",
               "red": "#C4123A", "orange": "#C2550A", "yellow": "#9A6B00", "green": "#1E7B44",
               "cyan": "#00748A", "blue": "#2F4FD1", "magenta": "#B0089A" },
    "dark":  { "surface": "#07040F", "text": "#F2E9FF", "accent": "#FF2BD6", "glow": "#21E6FF",
               "red": "#FF4D6D", "orange": "#FF8A3D", "yellow": "#FFE14D", "green": "#3DFF9A",
               "cyan": "#21E6FF", "blue": "#5B7CFF", "magenta": "#FF2BD6" }
  },
  "corner": 10,
  "border": 1.5,
  "borderColor": "accent",
  "glow": true,
  "font": "mono",
  "uppercase": false,
  "motion": "pulse",
  "gap": 6,
  "cardOpacity": 0.7,
  "frameAll": true,
  "clock": "glow",
  "background": { "kind": "theme", "art": "aurora" }
}
```

Every key but `name` is optional; what's missing comes from the `style` it's based on.

| key | values | what it does |
| --- | --- | --- |
| `style` | `soft`, `oldschool`, `neon`, `glass`, `tiled` | the base; every key below defaults to the style's |
| `dark` | `auto`, `always`, `never` | `auto` follows the phone or the user's choice |
| `colors` | `light` and `dark` objects | the palette (below); leave it out to let the user pick one |
| `corner` | 0 to 40 (dp) | corner radius of cards, sheets, fields and buttons (0 = square) |
| `border` | 0 to 6 (dp) | card border width |
| `borderColor` | `outline` (faint), `text`, `accent`, `glow`, `light` (frosted white), `#hex` | |
| `glow` | true, false | a soft halo around cards in the border colour |
| `font` | `sans`, `mono`, `serif` | the whole app's type |
| `uppercase` | true, false | small labels in capitals |
| `motion` | `smooth` (springs), `snappy` (instant, widgets appear one by one), `pulse` (breathing glow, flicker on), `float` (slow drift up) | also picks the style backdrop |
| `gap` | 0 to 16 (dp) | space between widgets |
| `cardOpacity` | 0 to 1 | how solid cards are; low values over a picture look like glass |
| `frameAll` | true, false | the clock gets a card too (the apps carousel stays clear) |
| `clock` | `thin` (big thin numerals), `mono` (terminal digits, blinking colon), `glow` (lit numerals), `light` | |
| `background` | `"theme"`, `"plain"`, `"wallpaper"`, `{ "kind": … }` or `{ "image": "bg.jpg" }` | below |

The styles: `soft` rounded, calm and airy; `oldschool` square edges, solid borders, mono type, no easing,
dot-grid backdrop; `neon` dark, glowing breathing edges, horizon glow and perspective grid; `glass` frosted
see-through cards, fine light edges, big soft colour fields; `tiled` tight tiles with sharp accent edges and
mono type, like a tiling desktop.

### Colours

Each mode has four main colours and seven tones:

- `surface` (the background), `text`, `accent` (buttons, highlights, the selected thing) and `glow` (the
  second colour: halos, the backdrop's second light). The whole app's Material palette is made from these.
- `red`, `orange`, `yellow`, `green`, `cyan`, `blue`, `magenta`: the tones. Widgets use them by name
  (charts, status, tags), the backdrops use them for extra light, the palette picker shows them as a ring.
  Tune them to the theme's mood (a muted forest, a hot synthwave) rather than pure primaries. Tones you
  leave out are made from the accent.
- Contrast: text on surface at least 7:1, accent on surface at least 3:1, each tone at least 3:1.
  `theme check` measures them.
- Borrow from a built-in palette with `theme palette <id>` (`theme palettes` lists them, many after the
  Omarchy themes: Neon Rain, Mainframe, Sunset Arcade, Fjord, Moss Hollow, Campfire, Great Wave, Jade Alley…).

### Background

- `{ "kind": "theme", "art": "waves" }` (or just `"waves"`): a background drawn in the theme's colours, light
  and dark. The arts: `glow`, `mesh`, `aurora`, `waves`, `dunes`, `peaks`, `stars`, `rain`, `sunset`, `horizon`, `embers`, `fog`, `topo`, `bauhaus`, `frost`, `grid`, `dots`, `city`. Pick the one that fits the theme's mood
  (rain for a rainy city night, embers for a fire, stars for a night sky…). Without `art`, the style's own:
  glow (soft), dots (oldschool), horizon (neon), frost (glass), grid (tiled). `"plain"`: the surface colour.
  `"wallpaper"`: the phone's wallpaper.
- `"p:<name>"` as the art: a detailed painting that ships with Hatchlybox, one per built-in theme in that theme's
  colours: `p:tokyonight` (rooftop city at night), `p:neonrain` (rainy neon alley), `p:mainframe` (data hall),
  `p:arcade` (synthwave sunset), `p:fjord`, `p:moss` (mossy glen), `p:campfire`, `p:greatwave`, `p:jadealley`
  (lantern alley), `p:espresso` (night café), `p:starlit` (Milky Way), `p:icedesk` (ice cave), `p:ember` (lava),
  `p:bog` (bog lantern), `p:lastlight` (desert mesa at dusk), `p:mission` (Earth from orbit), `p:fogbank`
  (lighthouse in fog), `p:void` (black hole), `p:marshmallow` (pastel cloud hills); light ones: `p:inkpaper`
  (ink wash), `p:petal` (blossom pond), `p:wildflower` (meadow), `p:gallery` (white gallery). Pick one whose
  colours match the theme's.
- `{ "image": "bg.jpg" }`: a picture saved in this folder (PNG or JPEG, about 1080×2400), made or fetched by
  you with a licence you checked; python3 can draw one if Pillow is installed (`pip install --user --break-system-packages pillow`).
  A light shade at the top and bottom keeps the clock and the create box readable.

Keep themes coherent: one idea, a palette that works in both light and dark, and motion that fits the mood.
