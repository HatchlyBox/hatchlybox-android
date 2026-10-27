title: Depth and glass in Compose: blur, translucent layers, light edges
tags: compose glass glassmorphism frosted blur translucent depth layers elevation border highlight modal focus kotlin app ui android 12 premium
styles: ui flat
cost: low
engine: compose
summary: blur the content behind a focused layer, translucent surfaces with a light top edge and soft coloured shadows: depth that makes key moments feel premium

## Method
Depth tells the user what's in front. Used sparingly (one focused layer at a time), it reads premium;
everywhere, it's noise.
- **Focus blur:** when a sheet, dialog or menu opens, blur the screen behind with `Modifier.blur`
  (Android 12+; ignored before, so also dim with a scrim) animated 0 → 16–24 dp.
- **Glass surfaces:** a translucent surface colour (alpha 0.55–0.75) over a colourful background, a
  1 dp border with a light-to-transparent gradient (light from above), and a large soft shadow tinted
  with the brand colour (`shadow(ambientColor, spotColor)`), not grey.
- Real backdrop blur (blurring only what's behind a card) isn't built into Compose: fake it by
  drawing the same background blurred inside the card's shape, or keep the card translucent.
- Contrast: text on glass must still pass 4.5:1; check on the brightest part of the background.

## Code
```kotlin
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// The screen behind blurs and dims while something is in focus in front of it.
@Composable
fun FocusLayers(focused: Boolean, background: @Composable BoxScope.() -> Unit, front: @Composable BoxScope.() -> Unit) {
    val blur by animateDpAsState(if (focused) 20.dp else 0.dp, label = "blur")
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().blur(blur), content = background)
        if (focused) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
            front()
        }
    }
}

// A translucent card with a light top edge and a soft shadow in the brand colour.
@Composable
fun GlassCard(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(modifier
        .shadow(24.dp, shape, ambientColor = tint, spotColor = tint)
        .clip(shape)
        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.62f))
        .border(BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0.05f)))), shape)
        .padding(20.dp), content = content)
}
```

## Validation
Peek screenshots: the focused layer is clearly in front (blurred, dimmed background), the glass
card's edge catches light at the top, text on it stays readable (`visual check` contrast). Scrolling
stays smooth with blur on (if not, blur a static snapshot instead).

## Adaptation
Weather or music: glass cards over a photo or the AGSL aurora. Productivity: no glass; use focus
blur only for dialogs. Dark themes: lower the border alpha, raise surface alpha. Games' UIs: glass
panels over the game view (render the game behind, not a blur of it, for cost).
