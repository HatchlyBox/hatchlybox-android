title: Brand typography in Compose: font pairing, variable fonts, numbers
tags: compose typography fonts font pairing google fonts variable font weight tabular numbers display headline brand type scale kotlin app ui identity
styles: ui flat
cost: none
engine: compose
summary: a display face with character plus a readable body face, mapped onto the Material type scale; variable weights and tabular numbers for polish

## Method
Typography is most of an app's personality, and stock Roboto makes every app look alike.
- **Pick a pairing** from the brief: a display face with character for headlines and numbers
  (geometric, slab, serif, rounded, condensed) and a highly readable body face. Google Fonts (all
  OFL-licensed: free to ship) — download the TTFs from github.com/google/fonts into `res/font/`
  (lowercase file names), or use system families (`FontFamily.Serif`, `Monospace`) when offline.
- **Map them onto the Material type scale** (`Typography.copy`): display/headline = display face
  with tighter letter-spacing, title/body/label = body face. Every screen then inherits it.
- **Variable fonts:** one file, any weight (`FontVariation.Settings(FontVariation.weight(n))`):
  animate weight for emphasis, fine-tune per size.
- **Numbers:** tabular figures (`fontFeatureSettings = "tnum"`) for anything that updates or lines
  up (timers, prices, scores), so digits don't jitter.
- Hierarchy by size and weight before colour; at most 2 families; check large-text accessibility.

## Code
```kotlin
import androidx.annotation.FontRes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The Material type scale with the brand's display face on top and its body face below.
fun brandTypography(display: FontFamily, body: FontFamily): Typography {
    val b = Typography()
    fun d(s: TextStyle, w: FontWeight, track: Float) = s.copy(fontFamily = display, fontWeight = w, letterSpacing = track.sp)
    fun t(s: TextStyle) = s.copy(fontFamily = body)
    return b.copy(
        displayLarge = d(b.displayLarge, FontWeight.Black, -1.5f),
        displayMedium = d(b.displayMedium, FontWeight.Black, -1f),
        displaySmall = d(b.displaySmall, FontWeight.ExtraBold, -0.5f),
        headlineLarge = d(b.headlineLarge, FontWeight.Bold, -0.5f),
        headlineMedium = d(b.headlineMedium, FontWeight.Bold, -0.25f),
        headlineSmall = d(b.headlineSmall, FontWeight.Bold, 0f),
        titleLarge = t(b.titleLarge).copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t(b.titleMedium), titleSmall = t(b.titleSmall),
        bodyLarge = t(b.bodyLarge), bodyMedium = t(b.bodyMedium), bodySmall = t(b.bodySmall),
        labelLarge = t(b.labelLarge), labelMedium = t(b.labelMedium), labelSmall = t(b.labelSmall),
    )
}

// One variable font file at several weights: FontFamily(variable(R.font.inter_variable, 400), variable(..., 700)).
@OptIn(ExperimentalTextApi::class)
fun variable(@FontRes res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

// Digits of equal width for anything that ticks or lines up.
fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")
```

## Validation
Peek screenshots: headlines clearly in the display face, body text in the body face, numbers in
lists aligned, a timer that doesn't jitter. Large font size (Settings → Display) doesn't clip.

## Adaptation
Editorial or journaling: serif display, sans body. Fitness or finance: condensed or geometric
display, tabular numbers everywhere. Kids: rounded display, generous size. Developer tools: monospace
for data only, not for body text. Use the brief's typography decision; don't default to Roboto.
