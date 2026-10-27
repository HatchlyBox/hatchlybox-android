title: Loading, refreshing and optimistic UI that feel fast
tags: compose loading skeleton shimmer placeholder pull to refresh optimistic update perceived performance progress empty error state kotlin app ui network
styles: ui
cost: none
engine: compose
summary: skeletons shaped like the content with a shimmer, pull-to-refresh, and optimistic updates so the app never feels like it's waiting

## Method
Perceived speed is a design decision.
- **Skeletons, not spinners,** for content that has a known shape: grey blocks laid out like the real
  rows, with a soft shimmer. The layout doesn't jump when data arrives.
- **Optimistic UI:** apply the user's change immediately (like, delete, reorder), save in the
  background, and roll back with a snackbar ("Couldn't save. Retry") only on failure.
- **Pull to refresh** (`PullToRefreshBox`, Material 3 1.3) for lists that change on a server.
- Show cached data first, then refresh quietly; spinners only for actions under ~1 s; a progress
  bar with words for longer ones ("Uploading 3 of 8").
- Every async screen has four designed states: loading, content, empty, error (with retry).

## Code
```kotlin
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

// A placeholder block with a moving highlight.
@Composable
fun Shimmer(modifier: Modifier) {
    val x by rememberInfiniteTransition(label = "shimmer")
        .animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "x")
    val base = MaterialTheme.colorScheme.surfaceVariant
    val shine = MaterialTheme.colorScheme.surface
    Box(modifier.drawBehind {
        val w = size.width
        drawRect(Brush.linearGradient(listOf(base, shine, base), start = Offset(w * x - w * 0.6f, 0f), end = Offset(w * x, 0f)))
    })
}

// A skeleton shaped like a list row: avatar, title and subtitle.
@Composable
fun SkeletonRow() {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Shimmer(Modifier.size(48.dp).clip(CircleShape))
        Spacer(Modifier.width(16.dp))
        Column {
            Shimmer(Modifier.width(180.dp).height(16.dp).clip(RoundedCornerShape(8.dp)))
            Spacer(Modifier.height(8.dp))
            Shimmer(Modifier.width(120.dp).height(12.dp).clip(RoundedCornerShape(6.dp)))
        }
    }
}

// Pull down to refresh around any scrolling content.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Refreshable(refreshing: Boolean, onRefresh: () -> Unit, content: @Composable () -> Unit) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh) { content() }
}
```

## Validation
Cold start with slow network (or airplane mode): skeleton rows in the content's shape, then the
content in the same places (no jump). Toggling a favourite responds instantly; a forced failure rolls
back with a retry snackbar. Pull-to-refresh shows its indicator and stops when done.

## Adaptation
Feeds and shops: skeleton cards. Chat: optimistic messages with a "sending" tick. Dashboards: keep
the last values visible, dimmed, while refreshing. Offline-first apps: show the cached time
("Updated 5 min ago") instead of a spinner.
