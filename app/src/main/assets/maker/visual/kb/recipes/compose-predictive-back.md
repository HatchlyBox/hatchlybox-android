title: Predictive back: screens that follow the back gesture
tags: compose predictive back gesture android 14 15 16 back preview animation sheet detail dismiss navigation kotlin app ui motion
styles: ui
cost: none
engine: compose
summary: PredictiveBackHandler gives the gesture's progress so a detail screen or sheet shrinks and slides with the finger, and commits or springs back

## Method
On Android 14+ the back swipe previews where it goes. Apps that animate their own screens with the
gesture feel native and modern; apps that don't feel dated.
- Opt in: `android:enableOnBackInvokedCallback="true"` on `<application>` in the manifest
  (Android 16 enables it by default for apps targeting it).
- Navigation Compose 2.8+ animates between destinations with the gesture by itself.
- For your own overlays (detail panes, sheets, full-screen viewers): `PredictiveBackHandler` gets a
  `Flow<BackEventCompat>`; map `progress` (0–1) to scale (1 → 0.9), corner radius and a slide toward
  the swipe edge (`swipeEdge`). When the flow completes, close; on cancellation, spring back to 0.

## Code
```kotlin
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException

// A full-screen detail that shrinks and slides with the back gesture, then closes (or springs back).
@Composable
fun PredictiveBackDetail(onClose: () -> Unit, content: @Composable () -> Unit) {
    var gesture by remember { mutableFloatStateOf(0f) }
    var fromLeft by remember { mutableFloatStateOf(1f) }
    val progress by animateFloatAsState(gesture, spring(stiffness = 600f), label = "back")
    PredictiveBackHandler { events ->
        try {
            events.collect { e ->
                fromLeft = if (e.swipeEdge == 0) 1f else -1f
                gesture = e.progress
            }
            onClose()
        } catch (e: CancellationException) {
            gesture = 0f
        }
    }
    Box(Modifier.fillMaxSize().graphicsLayer {
        val s = 1f - progress * 0.1f
        scaleX = s
        scaleY = s
        translationX = fromLeft * progress * 32.dp.toPx()
        shape = RoundedCornerShape((progress * 28f).dp)
        clip = true
    }) { content() }
}
```

## Validation
On an Android 14+ phone: start a back swipe on the detail: it shrinks, rounds its corners and moves
with the finger; release far enough: it closes; cancel: it springs back. No double-handling with
other BackHandlers (the innermost enabled handler wins).

## Adaptation
Sheets: translate down instead of scaling. Image viewers: scale more (to 0.8) and fade the scrim.
Games inside Compose: pause on gesture start, resume on cancel. Always keep a plain back path for
older Android versions (the handler still fires once without progress events).
