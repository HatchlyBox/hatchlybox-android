title: Adaptive layouts: phones, foldables, tablets and landscape
tags: compose adaptive layout responsive list detail two pane navigation rail bar foldable tablet landscape window size kotlin app ui large screen
styles: ui
cost: none
engine: compose
summary: one layout that becomes a navigation rail and a list-detail split on wide windows, and a bar with single panes on phones

## Method
Android 16 ignores orientation locks on large screens, and Google Play ranks large-screen quality, so
apps must adapt rather than stretch.
- Decide by **available width**, not device type: < 600 dp compact (bottom bar, one pane), 600–840 dp
  medium (navigation rail, one or two panes), ≥ 840 dp expanded (rail or drawer, list + detail side
  by side).
- `BoxWithConstraints` gives the width where the layout is; keep state (selected item, tab) above
  the switch so it survives rotation and folding.
- **List-detail:** wide = both panes, the detail shows the selection or an inviting empty state;
  narrow = the list, and the detail as its own screen with back.
- Cap line length (~600 dp for reading text) and use the extra space for content, not margins.

## Code
```kotlin
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

data class NavTab(val label: String, val icon: ImageVector)

// Bottom bar on phones, navigation rail from 600 dp.
@Composable
fun AdaptiveNav(tabs: List<NavTab>, current: Int, onSelect: (Int) -> Unit, content: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail {
                    tabs.forEachIndexed { i, t ->
                        NavigationRailItem(selected = i == current, onClick = { onSelect(i) },
                            icon = { Icon(t.icon, contentDescription = null) }, label = { Text(t.label) })
                    }
                }
                content(Modifier.weight(1f).fillMaxSize())
            }
        } else {
            Scaffold(bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { i, t ->
                        NavigationBarItem(selected = i == current, onClick = { onSelect(i) },
                            icon = { Icon(t.icon, contentDescription = null) }, label = { Text(t.label) })
                    }
                }
            }) { padding -> content(Modifier.padding(padding).fillMaxSize()) }
        }
    }
}

// List and detail side by side from 840 dp; on narrower windows the detail replaces the list.
@Composable
fun <T> ListDetail(items: List<T>, selected: T?, onSelect: (T?) -> Unit,
                   row: @Composable (T) -> Unit, detail: @Composable (T) -> Unit, empty: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val list: @Composable (Modifier) -> Unit = { m ->
            LazyColumn(m) { items(items) { item -> Box(Modifier.clickable { onSelect(item) }) { row(item) } } }
        }
        if (maxWidth >= 840.dp) {
            Row(Modifier.fillMaxSize()) {
                list(Modifier.width(360.dp))
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (selected != null) detail(selected) else empty()
                }
            }
        } else if (selected != null) {
            BackHandler { onSelect(null) }
            detail(selected)
        } else {
            list(Modifier.fillMaxSize())
        }
    }
}
```

## Validation
Peek in portrait and landscape (and a tablet or foldable if available): no stretched single column
on wide screens, the rail appears from 600 dp, list and detail side by side from 840 dp, the
selection survives rotation.

## Adaptation
Notes, mail, shopping: ListDetail. Dashboards: grid columns by width (`GridCells.Adaptive`).
Media: the player as a side pane on wide screens. Games' menus: two columns of options in landscape.
