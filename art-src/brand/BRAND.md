# Hatchlybox brand

One logo, two colour versions of the same mark: an H with a see-through diagonal cut in its bar and the
signature mint fold beside it. Never two different logos.

| Colour | Hex | Use |
| --- | --- | --- |
| Navy | `#101828` | the H on light backgrounds; the app icon's background |
| Mint | `#56DCC9` | the fold, always, on every version |
| White | `#F8FAFC` | the H on dark backgrounds |

| File | Where |
| --- | --- |
| `hatchlybox-mark-navy.svg` | light backgrounds: light-mode website |
| `hatchlybox-mark-white.svg` | dark backgrounds: dark-mode website |
| `hatchlybox-app-icon.svg` | app icon and favicon: the white mark on a navy rounded square |

The Android icon is `app/src/main/res/drawable/ic_fg.xml` (the white mark, scale 0.15, centred) on `@color/ic_bg`
(navy). Vector drawables have no masks, so the cut is drawn as shapes there.
