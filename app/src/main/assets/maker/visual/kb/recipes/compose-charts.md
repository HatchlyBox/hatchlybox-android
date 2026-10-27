title: Beautiful charts in Compose Canvas: animated, scrubbable, on brand
tags: compose chart graph line chart bar data visualisation dataviz canvas animation scrub touch statistics dashboard fitness finance kotlin app ui
styles: ui flat
cost: none
engine: compose
summary: a smooth line chart with gradient fill that draws itself in, and a finger scrub that shows the exact value, with no chart library

## Method
Charts are where many apps look cheap. A good one is quiet and precise:
- Smooth curves (cubic segments between points), a gradient fill fading to transparent, no heavy
  grid: 2–3 faint guide lines at most, labels only where they matter (first, last, max).
- **Draw-in animation** on first show (clip the path from left to right with an `Animatable`).
- **Scrub:** drag across the chart to read exact values; show the value and date above it, a thin
  guide and a dot; haptic tick per point (compose-haptics recipe).
- Colour: the brand's main colour for the series, the error colour only for "bad"; never rainbow
  palettes for one series. Respect dark mode (colours from the theme).
- Accessibility: a text summary ("Up 12% this week") above the chart, and `semantics` description.

## Code
```kotlin
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// A smooth line chart that draws itself in; dragging reports the index under the finger (null on release).
@Composable
fun LineChart(values: List<Float>, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary,
              onScrub: (Int?) -> Unit = {}) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(values) { reveal.snapTo(0f); reveal.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    var scrub by remember { mutableStateOf<Int?>(null) }
    val guide = MaterialTheme.colorScheme.outline
    Canvas(modifier.pointerInput(values) {
        fun index(x: Float) = (x / (size.width.toFloat() / (values.size - 1).coerceAtLeast(1))).roundToInt().coerceIn(0, values.lastIndex)
        detectDragGestures(
            onDragStart = { scrub = index(it.x); onScrub(scrub) },
            onDragEnd = { scrub = null; onScrub(null) },
            onDragCancel = { scrub = null; onScrub(null) },
        ) { change, _ -> val i = index(change.position.x); if (i != scrub) { scrub = i; onScrub(i) } }
    }) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val span = (values.max() - lo).takeIf { it > 0f } ?: 1f
        val step = size.width / (values.size - 1)
        fun pt(i: Int) = Offset(i * step, size.height * (0.95f - (values[i] - lo) / span * 0.9f))
        val line = Path().apply {
            moveTo(pt(0).x, pt(0).y)
            for (i in 1 until values.size) {
                val a = pt(i - 1); val b = pt(i); val mx = (a.x + b.x) / 2f
                cubicTo(mx, a.y, mx, b.y, b.x, b.y)
            }
        }
        val fill = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        clipRect(right = size.width * reveal.value) {
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent)))
            drawPath(line, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
        scrub?.let { i ->
            val p = pt(i)
            drawLine(guide.copy(alpha = 0.5f), Offset(p.x, 0f), Offset(p.x, size.height), strokeWidth = 1.dp.toPx())
            drawCircle(color, 7.dp.toPx(), p)
            drawCircle(Color.White, 3.dp.toPx(), p)
        }
    }
}
```

## Validation
Peek: the line draws in from the left, the fill fades out downward, dragging shows a guide and dot
on the nearest point (show the value in a Text above via onScrub). Works with 2 and with 365 points.

## Adaptation
Fitness: weekly bars with rounded tops and today's bar highlighted. Finance: green/red only for the
delta label, the line in the brand colour. Habits: a contribution grid (7 rows × weeks of rounded
squares, opacity by count). Sleep or mood: a smooth area chart with a gradient by value.
