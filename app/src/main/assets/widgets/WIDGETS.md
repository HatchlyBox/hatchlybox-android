# Hatchlybox home-screen widgets

A widget is a folder in `/root/widgets/<id>/` with a `widget.json`. Hatchlybox draws it on the home screen, where
the user places it on any page and can move and resize it freely, from a 2×2 square to the whole page, so it
must look right at every size (see "Every size"), and picks up every saved change within two seconds. Nothing
else is drawn: no HTML, no Kotlin. Keep widgets minimal, calm and readable at a glance.

There are two kinds:
- **A drawn widget** (`ui`): text, numbers, icons, charts, lists and buttons from a tree of nodes, with live data
  from a shell command, and **pictures that move**: a `canvas` of shapes, and `anim` on any part (see "Pictures
  and motion"). Pets, characters, scenes, animations and little games are drawn widgets. Most widgets are this.
- **A terminal widget** (`terminal`): a real terminal on the home screen running one interactive program full
  screen: OpenCode, Claude, Codex, htop, a shell, an editor. Every tap on it opens the keyboard with a key bar,
  and it looks like a terminal. Use it only when the user wants to type into a program, or names one to run.
  See "Terminal widgets".

**Picking the kind:**

| The request | Make |
| --- | --- |
| a number, a list, the weather, a status, a button that runs a script | drawn widget: nodes + `data` |
| a pet cat (any coat) | the `pet` node: a cat that lives in the card (`examples/wifoz`, see "Pets") |
| another animal, a character, a scene, something alive, an animation, a toy, a game | drawn widget with a `canvas` and `anim`, taps on its parts (`examples/detective-cat`) |
| a terminal, a shell, an agent (OpenCode, Claude, Codex), htop, vim, "a program I can type in" | terminal widget |

Never draw a picture or an animation by printing it in a terminal widget (an ASCII cat in a `while` loop, a
Python script that redraws): it opens the keyboard on every tap, shows the terminal's bars, and can't be
themed or resized well. `widget check` refuses a terminal widget that runs a script like that. And never fake
motion with one still image: a pet that doesn't move isn't alive. If a request needs something no node can do,
say so in your summary instead of switching to a terminal widget.

**How to work**: start from the example nearest the request
(`widget examples`, then `widget new <id> <example>` or copy its files), change it, save, and run
`widget check <id>` after every change. It checks the file, runs the data command, and **draws the widget
into a picture** (`.check.png` in its folder) with the live data at its real size: look at the picture and fix
what looks wrong. `/root/widgets/errors.txt` says, for every widget, what the home screen ignored and why.

## widget.json

```json
{
  "name": "Battery",
  "size": "small",
  "refresh": 300,
  "data": "./data.sh",
  "state": { "pct": 0, "charging": false },
  "tap": { "open": "app:com.android.settings" },
  "ui": { "type": "column", "gap": 6, "children": [
    { "type": "row", "gap": 6, "align": "center", "children": [
      { "type": "icon", "name": "battery", "color": "primary" },
      { "type": "text", "text": "Battery", "style": "label", "color": "muted" } ] },
    { "type": "text", "text": "{pct}%", "style": "display" },
    { "type": "progress", "value": "{pct}", "max": 100 },
    { "type": "text", "if": "{charging}", "text": "Charging", "style": "caption", "color": "muted" } ] }
}
```

- `name`: shown in menus. `description` (optional): one line about it.
- `builtin`: set by the app on widgets placed from its own list (Wifoz, System…), which come back as they were each
  time they're added from it. When a user asks to change one, the app takes `builtin` off first: the changed one
  is theirs, listed under Your widgets. Leave it out of widgets you make.
- `size`: the size it starts at: `small` (half the width, square), `wide` (full width, short), `large` (full width, tall) or `auto` (full width, three rows). The user can resize it.
- `state`: default values. Text can use them as `{key}` or `{key.sub}`; the latest state wins (see "State").
- `data` (optional): a shell command run in the widget's folder every `refresh` seconds (60 or more, default 900)
  while the home screen is open, with a 30 s limit. It must print one JSON object; its keys are merged into the
  state. Use it for live data: `curl` an API, read a file, compute something. Make it fast and robust: on an
  error print `{"error":"what went wrong"}` rather than nothing. It runs in Alpine Linux (busybox sh, jq, curl, python3 if installed).
- `tap` (optional): an action for a tap anywhere on the widget. Without one, a widget with a `screen` opens it.
- `ui`: the content, a tree of nodes. The card around it (rounded, padded, in the theme's colours) is drawn for you.
- `title` and `icon` (optional): the widget's title at the top, drawn the way the theme writes titles (see "Looking
  like the theme"). Use a `header` node instead when the title needs a value at its end.
- `background` (optional): `none` leaves the card out, so the widget sits straight on the home background (for a
  big clock or a date), or a colour fills the card. Leave it out for the theme's own card.
- `views` (optional): other layouts for other shapes and sizes (see "Every size").
- `screen` (optional): a full-screen page with the details, opened by a tap on the widget (see "Screens").
- `version` (optional, a number): the examples have one; when an example's goes up, widgets placed from Add a
  widget that nobody changed get the new version.

## Looking like the theme

A widget doesn't pick its own look: the launcher's theme gives it one. The theme has
a shape (Soft, Old school, Neon, Glass, Tiles, or one an agent made) and a palette, light and dark. The card's
corners, border, glow and opacity, the font, and the way `header`, `meter`, `stat`, `progress` and `chart` draw
all follow the shape: Old school writes titles in capitals with a bar mark and draws bars and rings in blocks;
Neon makes them glow in the accent; Tiles underlines titles and squares the ends; Soft and Glass round them.
So build widgets from these nodes and the named colours, and they suit every theme the user picks.
`/root/widgets/THEME.json` says what the look is now.

## Terminal widgets

```json
{
  "name": "OpenCode",
  "size": "large",
  "terminal": { "command": "opencode", "title": "opencode" }
}
```

Instead of `ui`, `terminal` makes the widget a terminal that runs `command` in `/root` (through `sh`, so
`cd ~/project && opencode` and `claude --continue || claude` work) on a full screen exactly the widget's size,
with colours, the mouse and the cursor keys. Tapping it opens the keyboard with a key bar (esc, tab, ctrl, alt,
arrows); a tap also clicks there when the program uses the mouse; dragging up or down scrolls. Resizing the
widget resizes the program's screen (it isn't restarted). When the program ends, a tap starts it again. Saving
widget.json with a different command restarts it with the new one.

`terminal` keys: `command` (required: an interactive program; `widget check` refuses a script that only prints,
like `python3 cat.py`, unless you add `"script": true` because the user asked for exactly that), `title` (the small label at the top, default the name),
`fontSize` (sp, 7 to 18, default 10), `background`, `text`, `prompt` (the cursor's colour), `corner` (dp), `font`
(`mono` or `serif`). There are no other keys; `state`, `data` and `ui` don't apply to a terminal widget.

Agents' commands: `opencode` (OpenCode), `claude` (Claude Code), `codex` (Codex). `--continue` / `-c` continues
the last conversation in that folder. Check the program exists with `command -v <program>`; install what's
missing with `apk add` (Alpine).

The built-in Terminal widget (the one on the first page) takes the same `command` and `title` in its
`/root/widgets/builtin-<id>/style.json`; without a command it is a bash shell.

## Pictures and motion

A `canvas` node draws a picture from shapes, like an SVG: in its own units (`view`: [width, height], default
[100, 100]), scaled to fit the room the node has (`fit`: `contain`, the default, or `cover` to fill it and crop).
Give it `"weight": 1` in a column so it takes the room left; without `weight` or `height` it keeps the view's
proportions. Shapes are drawn in order, later ones on top. Any shape, group or node can move with `anim`.

```json
{ "type": "canvas", "view": [150, 120], "weight": 1, "shapes": [
  { "shape": "path", "name": "tail", "d": "M108 104 C130 104 136 84 128 70 C125 64 131 60 134 66 C142 84 134 112 108 110 Z",
    "fill": "orange", "anim": { "rotate": 14, "every": 3.2, "pivot": [108, 107] } },
  { "shape": "group", "name": "cat", "tap": { "set": { "happy": true }, "for": 3 },
    "anim": { "scale": [1, 1.035], "every": 3.6, "pivot": [82, 112] }, "shapes": [
    { "shape": "path", "d": "M56 112 C50 92 58 70 82 68 C106 70 114 92 108 112 Z", "fill": "orange" },
    { "shape": "group", "name": "eyes", "if": "!{happy}",
      "anim": { "kind": "pulse", "every": 4.2, "for": 0.22, "scale": [1, 0.1], "pivot": [82, 53] }, "shapes": [
      { "shape": "ellipse", "x": 73, "y": 53, "rx": 3.4, "ry": 4.4, "fill": "#2E211A" },
      { "shape": "ellipse", "x": 91, "y": 53, "rx": 3.4, "ry": 4.4, "fill": "#2E211A" } ] },
    { "shape": "path", "if": "{happy}", "d": "M69 54 Q73 49 77 54 M87 54 Q91 49 95 54", "stroke": "#2E211A", "width": 1.8 } ] } ] }
```

The same parts make up `examples/detective-cat`: a tabby cat that breathes, blinks, sways its
tail and tilts its head, purrs with rising hearts when petted, eats from a bowl you tap, and plays a detective
game (a new mystery each level, harder each time; `game.sh` keeps the game in `game.json` and works it out with
`jq` in `game.jq`). Read it before making any pet, character or game, and borrow from it (copy its files into
your folder; never change the example itself).

**Shapes** (`"shape": …`; numbers are in the view's units):

| shape | keys |
| --- | --- |
| `rect` | `x`, `y`, `w`, `h`, `corner` |
| `circle` | `x`, `y` (the centre), `r` |
| `ellipse` | `x`, `y` (the centre), `rx`, `ry` |
| `line` | `x1`, `y1`, `x2`, `y2` (drawn with `stroke`, default the text colour) |
| `path` | `d`: SVG path data (`M L H V C S Q T A Z`, also lowercase), for any outline: bodies, tails, leaves, waves |
| `text` | `text` (with `{key}`), `x`, `y` (its centre), `size`, `color`, `align` (`start`, `center`, `end`), `bold`, `font` (`mono`, `serif`) |
| `sprite` | pixel art: `rows`, a list of strings, one letter per pixel, `.` for empty; `palette` gives each letter a colour (`{"b": "orange", "e": "#2E211A"}`); `x`, `y`, `pixel` (a pixel's size). `frames` (a list of `rows`) with `fps` (default 4) flips through them: a walk cycle |
| `image` | a PNG or JPEG in the widget's folder: `src`, `x`, `y`, `w`, `h`; `frames` (a list of files) with `fps` (default 6) |
| `group` | `shapes`: parts that move, show and take taps together |

Every shape can also have: `fill` (a colour, `"none"`, or a gradient `{"from": "primary", "to": "tertiary", "dir":
"down"}`, `dir` being `down`, `right` or `out`), `stroke` and `width` (an outline), `cap` (`round`, `butt`,
`square`), `opacity` (0 to 1), `if` (shown only when true, as on nodes: the way to swap faces, moods and states),
`tap` (an action when that part is tapped: pet the cat, feed the fish; a tap elsewhere goes to the widget),
`name` (for you: errors name it), `anim` and `pivot` ([x, y]: where it turns and scales around; default its middle).

**Motion** (`anim`, on a shape, a group or any node): one motion, or a list of them that add up. It goes from rest
towards its values and back, and costs no battery while the screen is off.

| key | |
| --- | --- |
| `move` | [dx, dy]: how far it goes (the view's units in a canvas, dp on a node) |
| `rotate` | degrees, around `pivot` |
| `scale` | a number, or [sx, sy]: breathing is `[1, 1.03]` around the feet |
| `fade` | the opacity it reaches (0 vanishes) |
| `every` | seconds for one round (default 2) |
| `kind` | `wave` (the default: smooth there and back: breathing, swaying, floating, bobbing), `pulse` (rests, then a quick there-and-back taking `for` seconds, default 0.25: blinking, twitching, hopping, a heartbeat) or `loop` (steadily from rest to the end, then from rest again: spinning with `rotate: 360`, rising bubbles and hearts with `move` and `fade: 0`, drifting clouds) |
| `delay` | seconds to shift it by, so parts don't all move at once (three hearts with delays 0, 0.5, 1) |
| `pivot` | [x, y] in a canvas; on a node, fractions of its size ([0.5, 1] is the middle of its bottom) |

On a node, `anim` moves it when drawn (no layout changes): `{"type": "icon", "name": "favorite", "anim": {"kind":
"pulse", "every": 1.2, "scale": 1.25}}` is a beating heart.

**Reactions:** a tap can change the state for a moment: `{"set": {"happy": true}, "for": 3}` sets `happy`, and
three seconds later sets it back. With `if` on the parts (happy eyes and hearts while `happy`, the normal eyes
while `!{happy}`) the picture reacts and settles by itself. A `run` in the same action runs a script too (the detective cat's `./game.sh feed` saves when he ate and gives him energy);
the `data` script works out the rest from the saved files, and the picture and the text show it.

**Pets:** `{"type": "pet", "animal": "cat", "coat": "tabby"}` (coats: tabby, ginger, black, cream) is a whole
cat with a life of its own, drawn by the app at the same small size in any card: it sits, loafs, grooms, yawns,
kneads, naps, stretches, walks, runs, rolls over, pounces, scratches and climbs the card's edges, chases a
butterfly, plays with a ball of yarn and jumps at a toy on a string, choosing what fits the card's size (a tiny
card: sitting, loafing, grooming, watching the butterfly; wide: zoomies, chasing and rolling; tall: climbing and
jumping at the toy). It always stays inside the card. A tap on it pets it, a tap
elsewhere is a laser dot it stalks and pounces on, and holding then dragging leads the dot. As the widget's whole
`ui` it fills the card edge to edge (no title, no margin); inside a column it takes `height` or `weight`. It
needs no `data` or `state`. `examples/wifoz` is the built-in one. For any other animal, draw it with a `canvas`.

**Making something alive** (a pet, a character, a creature, a scene):
- Idle motion, always on, on several parts with different `every` so it never looks mechanical: breathing (`scale`
  on the body group, 3 to 4 s), blinking (`pulse` on the eyes, `scale: [1, 0.1]`, every 3 to 5 s), and one more
  (a tail, ears, a head tilt, swimming, bubbles, leaves).
- It reacts: a tap on it does something you can see (`set` … `for`), and its needs or moods show (`if`).
- It has a state that lasts: `data` and `run` scripts keep it in files (when it was fed, its name, a score).
- Plain buttons (`button` nodes next to the canvas) for the actions, as well as taps on the picture.
- Draw it big and simple: a few clear shapes in the theme's tones (`orange`, `yellow`, `cyan`…), dark eyes in a
  fixed colour (`#2E211A`), and room around it.
- Check it with `widget check <id> motion`: six moments of it side by side. If they all look the same, it doesn't move.

## Screens

A tile is small. For more, a widget can have one full-screen page, opened by a tap (its `screen`): the week's forecast behind a weather tile, every process behind a CPU tile, all the headlines
behind a news tile.

```json
"screen": {
  "title": "This week",
  "ui": { "type": "column", "scroll": true, "gap": 10, "children": [
    { "type": "text", "text": "{temp}°", "style": "display" },
    { "type": "list", "items": "{days}", "gap": 8, "item": { "type": "row", "children": [
      { "type": "text", "text": "{item.day}", "weight": 1 },
      { "type": "text", "text": "{item.max}°", "bold": true } ] } } ] }
}
```

- It opens over the home screen, titled with `title` (default: the widget's title or name), with a ✕. Back
  and the ✕ close it, and so does a `{"close": true}` action.
- A tap on the widget opens it when the widget has no `tap`. With a `tap` of its own (a button, say), open it
  with `{"screen": true}` on a node's `tap` or a button's `action`.
- It's the same widget: the same `state`, `data` and actions, and the data keeps refreshing while it's open.
  A `set` or `toggle` there changes the tile too.
- It holds the same nodes as the tile and is drawn at full size, so it doesn't shrink or drop parts: put the
  content in a `{"type": "column", "scroll": true}` when it can be longer than the screen. `list`'s `max` can
  be higher here (20 to 50).
- Check it with `widget check <id> screen`: a picture of the screen as a tap opens it.

## Every size

The home grid is 8 cells across (a cell is about 50 dp). The user can make a widget any size, so a widget never
gets cut off: Hatchlybox fits it to its card in three steps.
1. **A view for the shape.** The card's shape is one of `tiny` (2×2 cells or less), `small` (about square),
   `wide` (at least 1.8 times as wide as tall), `tall` (at least 1.5 times as tall as wide) or `large` (roomy both
   ways). `views` holds other layouts; the first whose conditions fit is drawn instead of `ui`:
   ```json
   "views": [
     { "when": "tiny", "title": false, "ui": { "type": "column", "align": "center", "arrange": "center", "children": [
       { "type": "icon", "name": "{icon}", "size": 28, "color": "primary" },
       { "type": "text", "text": "{temp}°", "style": "headline" } ] } },
     { "when": "wide", "maxHeight": 130, "ui": { "type": "row", "gap": 12, "align": "center", "children": [ … ] } }
   ]
   ```
   Conditions: `when` (shapes, `"tiny small"` or a list), `minWidth`, `maxWidth`, `minHeight`, `maxHeight` (the
   card's dp). `title: false` leaves the widget's title out of that view. Any node can have the same conditions,
   to show a part only at some sizes, e.g. `"when": "large tall"` on the hourly chart.
2. **Optional parts go first.** When the layout's natural size doesn't fit, it is tried again without the nodes
   with `"priority": 3`, then without `"priority": 2` too, with shorter lists and no title. Without a priority,
   dividers and fixed spacers count as 3, captions and charts as 2, everything else as 1 (always shown).
3. **Smaller as a last resort.** If even that doesn't fit, the whole widget is drawn smaller until it does.

So design for the 2×2 tile and the full page both: a `tiny` view with the one number that matters, priorities on
the extras, and charts with `weight` so they fill whatever room is left. Check with `widget check <id> all`: one
picture of the widget at nine sizes, from 2×2 to 8×6 cells, labelled with their shapes.

## Nodes

Every node has `type`. Any node can also have:
`if` (shown only when the value is truthy: not empty, `false`, `0` or `[]`; `"!{key}"` inverts),
`padding` (dp), `background` (a colour), `corner` (dp, with background), `width` / `height` (dp),
`weight` (share of the free space inside a row or column), `tap` (an action),
`priority` (1 to 3: which parts go first when room runs short), `anim` (motion, see "Pictures and motion") and the size conditions `when`, `minWidth`,
`maxWidth`, `minHeight`, `maxHeight` (see "Every size").

| type | properties |
| --- | --- |
| `column`, `row` | `children`, `gap` (dp), `align` (`start`, `center`, `end`: across), `arrange` (`start`, `center`, `end`, `between`: along); a column's `scroll` (true: it scrolls, for a screen) |
| `box` | `children` drawn on top of each other, `align` (`center`, `top`, `bottom`, `start`, `end`, `topStart`, ...) |
| `text` | `text` (with `{key}`), `style` (`display`, `headline`, `title`, `body`, `label`, `caption`), `color`, `bold`, `align` (`start`, `center`, `end`), `lines` (max lines), `size` (sp), `font` (`mono`, `serif`, `sans`: mono for ASCII art and fetch-style lists), `wrap` (false: long lines are cut, not wrapped) |
| `icon` | `name` (below), `color`, `size` (dp, default 20) |
| `header` | a title row in the theme's style: `text`, `icon`, `color` (the icon's and, in glowing styles, the title's), `trailing` (a small value at the end, like `"up {up}"`) |
| `progress` | `value`, `max` (default 1, or 100 when the value is above 1), `style` (`bar` or `ring`), `color`, `size` (ring, dp) |
| `meter` | a ring with its value in the middle and a label and caption below: `value`, `max` (default 100), `unit` (default `%` when max is 100), `text` (shown in the middle instead), `label`, `caption`, `color`, `size` (dp, default 64) |
| `stat` | a labelled number: `label`, `value`, `caption`, `icon`, `color` |
| `chart` | `values` (`"{history}"`, an array of numbers), `kind` (`line` or `bars`), `area` (true: fill under the line), `color`, `height` (dp, default 48; with `weight` it fills the room left) |
| `list` | `items` (`"{todos}"`, an array), `item` (a node; inside it `{item}`, `{item.key}` and `{index}`), `max`, `gap` |
| `clock` | `format` (Java date pattern, default `HH:mm`), `style`, `color`: a live clock |
| `image` | `src` (a PNG or JPEG in the widget's folder, may be `{key}`), `size` or `width`/`height`, `corner`; `frames` (a list of files) and `fps` (default 6) for a picture that moves |
| `canvas` | a picture of shapes that can move: `view`, `fit`, `shapes` (see "Pictures and motion") |
| `button` | `text`, `action`, `style` (`filled`, `tonal`, `text`) |
| `spacer` | `size` (dp), or `weight` to push things apart |
| `divider` | a thin line |

Text anywhere can also use `{now:EEE d MMM}` (the date and time in a Java date pattern), and the phone's own
numbers without a script: `{phone.battery}` (0-100), `{phone.charging}`, `{phone.batteryStatus}` (Charging, Full,
Low, On battery), `{phone.batteryTone}` (green, yellow or red: use it as a colour), `{phone.batteryTemp}`,
`{phone.storage}` (% of the phone's storage used) and `{phone.storageFree}`.

**Colours:** `primary` (or `accent`), `secondary`, `tertiary` (or `glow`), `error`, `text`, `muted`, `surface`, `container`,
`card`, `outline`, `onPrimary`, the theme's seven tones `red`, `orange`, `yellow`, `green`, `cyan`, `blue`, `magenta`
(for charts, status and tags), or `#RRGGBB` / `#AARRGGBB`. Prefer the named ones: they follow the launcher's theme, light and dark.

**Icons:** `add`, `alarm`, `battery`, `bolt`, `book`, `calendar`, `call`, `camera`, `check`, `circle`, `close`, `cloud`,
`code`, `coffee`, `download`, `edit`, `favorite`, `fitness`, `flag`, `folder`, `home`, `language`, `light`, `location`,
`lock`, `mail`, `map`, `mic`, `moon`, `music`, `notes`, `pause`, `person`, `photo`, `play`, `refresh`, `rain`, `search`,
`settings`, `shopping`, `skip`, `snow`, `star`, `sun`, `terminal`, `timer`, `todo`, `trending`, `wallet`, `water`, `wifi`,
`memory`, `storage`, `speed`, `phone`, `charging`, `today`, `news`, `thermometer`, `chart`, `schedule`.

## Actions

An action is an object with one of:
- `{"open": "https://..."}`, `{"open": "app:<package>"}` (a phone app), `{"open": "phone:calendar"}` (or `phone:clock`,
  `phone:battery`, `phone:settings`: the phone's own screen, whichever app has it) or `{"open": "maker:<slug>"}` (an app's page in Hatchlybox)
- `{"run": "./script.sh arg"}`: a command in the widget's folder; if it prints a JSON object, it's merged into the state. The data script then runs again.
- `{"set": {"key": "value"}}`, `{"toggle": "key"}`: change the state right away. `{"set": {…}, "for": 3}` sets it back after 3 seconds (a reaction)
- `{"refresh": true}`: run the data script now
- `{"screen": true}`, `{"close": true}`: open the widget's screen, or close it (see "Screens")

## State

`state.json` in the widget's folder holds the current state; `widget.json`'s `state` gives the defaults. Scripts,
cron jobs and agents can change a widget from the shell:

```
widget list                       # id and name of every widget
widget examples                   # the working examples in /root/widgets/examples, one line each
widget new <id> [example]         # a new widget folder, empty or a copy of an example
widget check <id> [size]          # checks widget.json, runs the data script once, and draws the widget into
                                  # /root/widgets/<id>/.check.png (at its home-screen size, or small/wide/large/auto;
                                  # all: nine sizes; screen: its screen; motion: six moments of its animation):
                                  # run it after every change, look at the picture, fix every line starting with !
widget done <id> "summary"        # the last step of making or changing a widget: Hatchlybox ends the task
widget errors                     # /root/widgets/errors.txt: what the home screen ignored in each widget, and why
widget set <id> <key> <value>     # value is JSON if it parses (42, true, ["a"]), else a string
widget refresh <id>               # run the data script now
```

## Making a good widget

- One idea per widget, glanceable in a second: one big number or line, a little context, nothing more.
- Use the named colours, `label`/`caption` text in `muted` for context, and at most one accent colour.
- Start with a `header` (or `title`), and use `meter`, `stat`, `progress` and `chart` for numbers: they take the theme's style.
- Pick the smallest size that fits. Lists: `max` 3 to 5 items.
- A pet, a character, a scene or anything alive moves (see "Making something alive") and is a drawn widget with a
  canvas, never a terminal widget. Check it with `widget check <id> motion`.
- Give it a `screen` whenever there's more worth seeing than the tile holds (a forecast, a history, a whole
  list, the details behind a number), so a tap shows it. The tile stays glanceable; the screen has the rest.
- Always run `widget check <id>`, fix what it reports, and look at `.check.png`: nothing cut off, text readable,
  balanced. Check another size too (`widget check <id> small`) since the user can resize it.
  Never leave the widget showing an error.
- Changing a widget: read its widget.json first and keep what the user didn't ask to change.
- The picture needs Hatchlybox on screen; if it says it can't draw, the other checks still count.
