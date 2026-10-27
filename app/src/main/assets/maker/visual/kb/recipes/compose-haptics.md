title: Haptics: a touch language for the app
tags: compose haptics haptic feedback vibration tick confirm reject long press slider toggle tactile feel kotlin app ui micro-interaction
styles: ui
cost: none
engine: compose
summary: consistent, subtle haptics for ticks, confirmations, rejections and long presses: the polish users feel before they see it

## Method
Premium apps have a haptic vocabulary, used consistently and sparingly:
- **Tick** for discrete steps (a slider or picker crossing a value, a list snapping, chart scrub).
- **Confirm** when something succeeds (saved, sent, completed a goal); **reject** when it fails or
  is not allowed. Android 11+ has dedicated `CONFIRM`/`REJECT` constants.
- **Long press** when a long press is recognised (context menus, drag start).
- Never on every tap or scroll; never for decoration. Haptics follow the system setting.
- Use the View's `performHapticFeedback` (system-tuned, no permission needed), not the vibrator.

## Code
```kotlin
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

// The app's haptic vocabulary in one place: haptics.tick(), confirm(), reject(), longPress().
class Haptics(private val view: View, private val compose: HapticFeedback) {
    fun tick() { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    fun confirm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        else compose.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    fun reject() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        else compose.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    fun longPress() { compose.performHapticFeedback(HapticFeedbackType.LongPress) }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val compose = LocalHapticFeedback.current
    return remember(view, compose) { Haptics(view, compose) }
}
```

## Validation
On the phone: a slider ticks once per step, saving gives one crisp confirm, an invalid action a
distinct reject, nothing buzzes on plain taps or scrolling. With system haptics off, nothing at all.

## Adaptation
Timers and workouts: confirm at each interval. Games in Compose: tick on combos, reject on a miss.
Finance: confirm only on completed payments. Pickers and charts: tick on each value change.
