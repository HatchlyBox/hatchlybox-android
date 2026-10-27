title: Shared element transitions: list to detail like a premium app
tags: compose shared element transition hero animation list detail gallery card expand container transform navigation kotlin app ui motion
styles: ui flat
cost: none
engine: compose
summary: SharedTransitionLayout morphs a thumbnail into the detail header (and back), so navigation feels continuous instead of cutting

## Method
The "container transform": the tapped card grows into the next screen. It tells the user where they
are and is the signature motion of premium apps (Photos, Play Store, Material 3).
- Wrap both states in `SharedTransitionLayout` and an `AnimatedContent` (or Navigation Compose's
  `NavHost`, whose destinations are `AnimatedVisibilityScope`s too).
- Mark matching elements on both sides with `Modifier.sharedElement(rememberSharedContentState(key),
  animatedVisibilityScope)`: same key = same element. Use `sharedBounds` for containers whose content
  differs (a card becoming a whole page).
- Keep keys unique per item (`"image-$id"`), apply the shared modifier before size/clip modifiers,
  and handle back (`BackHandler`) to reverse the transition.
- Compose 1.7 API (this project's BOM): `@OptIn(ExperimentalSharedTransitionApi::class)`.

## Code
```kotlin
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

data class Tile(val id: Int, val title: String, val color: Color)

// A grid of tiles; tapping one grows it into the detail header, back shrinks it into place again.
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun GalleryWithDetail(tiles: List<Tile>) {
    var selected by remember { mutableStateOf<Tile?>(null) }
    BackHandler(enabled = selected != null) { selected = null }
    SharedTransitionLayout {
        AnimatedContent(selected, label = "gallery") { tile ->
            if (tile == null) {
                LazyVerticalGrid(GridCells.Adaptive(140.dp), contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(tiles, key = { it.id }) { t ->
                        Box(Modifier
                            .sharedElement(rememberSharedContentState("tile-${t.id}"), animatedVisibilityScope = this@AnimatedContent)
                            .aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(t.color)
                            .clickable { selected = t })
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier
                        .sharedElement(rememberSharedContentState("tile-${tile.id}"), animatedVisibilityScope = this@AnimatedContent)
                        .fillMaxWidth().height(320.dp).clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
                        .background(tile.color).clickable { selected = null })
                    Text(tile.title, Modifier.padding(24.dp), style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
    }
}
```

## Validation
On the phone: the tapped tile visibly grows into the header (no cut, no flash), back shrinks it into
its grid slot, other tiles fade. Rapid taps don't break it.

## Adaptation
Shopping or recipes: the product photo is the shared element, the title a second one
(`"title-$id"`). Music: album art from list to player. Notes: `sharedBounds` on the whole card to the
editor. With Navigation Compose: put `SharedTransitionLayout` around the `NavHost` and pass the
`AnimatedContentScope` from each `composable {}` destination.
