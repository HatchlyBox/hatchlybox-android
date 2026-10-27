# Assets: searching, licensing and fitting them into one look

The art direction comes from this request and `design/brief.md`, never from a house style or an earlier
game. For every game, search the libraries below yourself before deciding what to make with code: good
free assets often beat code-made ones for 3D models, characters, animation, textures, sound and music.
Search per asset type the game needs: 3D models, characters, animations, vehicles, environments,
textures and materials, sprites and tilesets, UI kits, fonts, sound effects, music, VFX and shaders.
Where nothing suitable and licensable exists, make original assets (SKILL.md "Assets", the `visual`
recipes). Either way, the result must be finished art: a placeholder (grey boxes, prototype kits left
untouched, programmer art) is not done.

Free to download doesn't mean free to use. Read each asset's licence on its own page or in its
License.txt. Never assume CC0 because of the site.

## Where to look

| Source | Good for | Licence (check each asset anyway) |
|---|---|---|
| Poly Haven (polyhaven.com) | PBR textures, HDRI skies, realistic props and nature | CC0. The API asks for a User-Agent and a visible credit (below) |
| ambientCG (ambientcg.com) | PBR materials: grass, dirt, cobblestone, brick, plaster, timber | CC0 |
| Kenney (kenney.nl/assets) | stylised 3D kits, sprites, tilesets, UI, fonts, sounds | CC0, each zip has a License.txt |
| Quaternius (quaternius.com) | low-poly characters with animations, buildings, vehicles, nature | Quaternius Asset License v1.0: commercial use, no attribution, no redistributing the assets on their own (see "public repos") |
| itch.io (itch.io/game-assets/free/tag-cc0) | everything, many styles; KayKit, PSX, pixel-art packs | per pack, on its page: CC0, CC-BY, or the author's own terms (read them) |
| OpenGameArt (opengameart.org) | models, sprites, tilesets, sounds, music | per asset; filter by CC0 |
| Freesound (freesound.org) | sound effects, ambiences | per sound: CC0, CC-BY or NC |
| Google Fonts (github.com/google/fonts) | fonts | mostly SIL OFL 1.1: keep OFL.txt with the font, don't sell the font alone |
| Godot Shaders (godotshaders.com) | shaders, VFX | per shader: CC0 or MIT (keep the MIT notice). GPL v3 would make the whole game GPL: skip |

Downloading: use direct file links (APIs, zips, GitHub raw). If a page only offers a download through
a browser flow you can't follow, pick another asset; never work around a paywall or a login.

## Licences: accept, satisfy or reject

- **Accept:** CC0 / public domain; CC-BY 3.0/4.0 and MIT with credits; OFL for fonts; the Quaternius
  licence (not in public repos); an author's own terms that clearly allow commercial use in a game.
- **Reject:** NC (non-commercial), ND (no derivatives), GPL or CC-BY-SA for art that you'd mix with
  everything else, "personal use only", a licence you can't find or that contradicts itself,
  re-uploads of paid packs, assets ripped or extracted from commercial games, fan rips, AI packs
  with no stated source. When in doubt, reject.
- **Public repos:** when the app's source goes to a public GitHub repo, the asset files go with it. CC0,
  CC-BY and OFL are fine there. Assets that may not be redistributed on their own (Quaternius, many
  itch.io terms) don't belong in a public repo: prefer CC0, or keep them out of git (`.gitignore`, with
  a download script that the build runs) and say so in the manifest.

## Poly Haven API (no key; always send a User-Agent)

```bash
A="hatchlybox-agent"
curl -s -A $A "https://api.polyhaven.com/categories/models"                       # categories and counts
curl -s -A $A "https://api.polyhaven.com/assets?t=textures&c=brick" | jq 'keys'  # t=textures|models|hdris
curl -s -A $A "https://api.polyhaven.com/info/<id>" | jq '{name,authors,polycount,tags}'
curl -s -A $A "https://api.polyhaven.com/files/<id>" | jq '.gltf["1k"].gltf'      # url + include (bin, textures)
curl -s -A $A "https://api.polyhaven.com/files/<id>" | jq -r '.Diffuse["1k"].jpg.url'   # one texture map
```

A glTF download is the `.gltf` URL plus every file in `include`, saved at those relative paths. Textures:
`Diffuse`, `nor_gl` (Godot uses OpenGL normals), `Rough`, `AO` or the packed `arm`. Credit Poly Haven in the
app's credits ("Textures and models from Poly Haven").

## Choosing: quality and fit before quantity

Judge each candidate against the brief: visual quality and detail, consistency with the rest of the
set, scale and proportions, animation quality (the clips you need: idle, walk, run, attack, death),
whether Godot 4 imports it cleanly, and its cost on the phone (`visual device`). Take a set from as
few sources as possible. A big pile of unrelated assets is worse than a few that belong together.

## Godot 4 integration

- **Formats:** glTF/GLB for 3D (with animations), PNG for sprites, tilesets and UI, JPG or PNG for
  textures, OGG for music and long sounds, WAV for short effects, TTF/OTF fonts.
- **Budget:** check the polycount before downloading. Realistic props are often 10–50k triangles: use
  a few as hero pieces, not hundreds. Fill crowds and forests with low-poly kits and MultiMesh (recipes
  gpu-crowds-3d, impostors-3d). Textures at 1k (2k at most for the ground), ETC2/ASTC on import; tile
  ground materials (recipe triplanar-terrain-3d) instead of large unique textures. Pixel art: Nearest
  filter, no mipmaps.
- **Reusable scenes and resources:** import each model once and wrap it in its own `.tscn` with simple
  collision shapes (not trimesh), materials as shared `.tres`, an `AnimationPlayer` or `AnimationTree`
  with the clips renamed and looping set, visibility ranges or LOD, then instance that scene. Tilesets
  become a `TileSet` resource, fonts a `FontFile` in the UI theme, sounds go on audio buses.
- **Files:** `godot/assets/<source>/<asset>/`, so the source shows in the tree.
- **Verify each one:** after `godot --headless --path godot --import`, load it in the game, run
  `visual render`, and look: it's there, at the right scale, lit, animated, with no import errors in
  the log.

## Unifying the look

Mixed sources look like a collage until you unify them. Use one scale (a door is about 2 m), one
light rig, one colour grade (recipe color-grading-lut) and shared fog. Tune materials to the brief's
palette (albedo tint, roughness, saturation) and keep proportions consistent. A realistic rock next
to a stylised house reads as two games: restyle it (recipes toon-outline-3d, matcap-3d) or replace
it. After each render, check for mismatched assets (style, scale, texel density, brightness), fix them,
then check the frame budget again.

## The manifest: design/assets.md (required whenever you use a downloaded file)

```
| Asset | Files | Source URL | Creator | Licence (version) | Checked | Credit | Restrictions |
|---|---|---|---|---|---|---|---|
| Brick Wall 001 | godot/assets/polyhaven/brick_wall_001/ | https://polyhaven.com/a/brick_wall_001 | Rob Tuytel, Dimitrios Savva | CC0 1.0 | 2026-10-18, polyhaven.com/license | no (API: credit Poly Haven) | none |
```

Keep the licence file or a saved copy of the licence page next to each asset as evidence. Every asset
that needs credit, and Poly Haven when you used its API, goes on an in-app Credits screen, worded the
way its licence asks. Before calling the app done, check that every downloaded file under
`godot/assets/` has a row and that nothing in the manifest is rejected above.
