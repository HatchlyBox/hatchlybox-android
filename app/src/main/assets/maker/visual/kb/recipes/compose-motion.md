title: Compose motion that feels designed: springs, choreography, micro-interactions
tags: compose motion animation spring physics micro-interaction press feedback staggered list entrance animated content counter expand material motion kotlin app ui polish
styles: ui flat
cost: none
engine: compose
summary: spring-based press feedback, staggered entrances, rolling numbers and size changes: the small motions that make an app feel premium

## Method
Professional apps move with purpose: motion explains what changed and where things came from.
- **Springs, not durations,** for anything the user touches: they respond to interruption naturally.
  Medium-bouncy for playful brands, no-bounce for serious ones: decide it once in the brief.
- **Press feedback:** scale the pressed card to ~0.96 with a spring; release overshoots slightly.
- **Choreography:** stagger list entrances by 30–50 ms per item (max ~8 items), fade + rise 16–40 px.
- **State changes in place:** `AnimatedContent` for values that change (numbers roll up or down by
  direction), `animateContentSize` for expanding cards.
- Keep durations short (150–350 ms), respect "Remove animations" (system animator scale 0 makes
  Compose animations instant automatically), and never block input on an animation.

## Code
```kotlin
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// A card that squashes slightly while pressed and springs back.
@Composable
fun PressableCard(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.96f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "press"
    )
    Card(onClick = onClick, interactionSource = source,
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale }.animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

// Wrap each list item: it fades and rises in, a little after the one above it.
@Composable
fun StaggeredEntrance(index: Int, content: @Composable () -> Unit) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(8) * 40L)
        t.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow))
    }
    Box(Modifier.graphicsLayer { alpha = t.value; translationY = (1f - t.value) * 32.dp.toPx() }) { content() }
}

// A number that rolls up when it grows and down when it shrinks.
@Composable
fun RollingNumber(value: Int, modifier: Modifier = Modifier) {
    AnimatedContent(value, modifier, transitionSpec = {
        if (targetState > initialState) (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
        else (slideInVertically { -it } + fadeIn()) togetherWith (slideOutVertically { it } + fadeOut())
    }, label = "number") { Text("$it", style = MaterialTheme.typography.displayMedium) }
}
```

## Validation
On the phone (`hatchlybox peek`): a pressed card visibly dips and bounces back, list items cascade in
on first show, a changing total rolls in the right direction. No jank when scrolling (no allocation in
graphicsLayer lambdas).

## Adaptation
Calm or professional apps: `DampingRatioNoBouncy`, smaller distances. Playful or kids' apps:
`DampingRatioHighBouncy` on presses, bigger rise. Fitness or finance: RollingNumber for totals and
streaks. Pair with haptics (compose-haptics recipe) on confirmations.
