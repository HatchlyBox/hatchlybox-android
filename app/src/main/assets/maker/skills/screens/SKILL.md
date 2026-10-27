---
name: screens
description: Build an app with several screens (a list and its details, settings, tabs) using Navigation Compose and a ViewModel that keeps the state. Use when the app needs more than one page, a back button, a settings screen or a bottom navigation bar.
---

# Several screens

Navigation Compose moves between screens; one ViewModel holds the app's state so it survives rotation
and is shared by the screens. Working code: `example/ScreensApp.kt` (copy it, rename the package to the
app's, and adapt).

## Gradle

In `app/build.gradle.kts` add:

```kotlin
implementation(libs.androidx.navigation.compose)
implementation(libs.androidx.lifecycle.runtime.compose)
```

## How it fits together

- `NavHost(nav, startDestination = "list")` lists the screens as routes: `"list"`, `"detail/{id}"`, `"settings"`.
- Go forward with `nav.navigate("detail/$id")`, back with `nav.popBackStack()`. The system back gesture works on its own.
- Pass only ids in routes, never whole objects; the next screen looks the item up in the ViewModel.
- Read state with `collectAsStateWithLifecycle()`; change it only through ViewModel functions.
- Each screen is a `Scaffold` with a `TopAppBar`: give back arrows `Icons.AutoMirrored.Filled.ArrowBack`
  and pass the Scaffold's padding to the content (edge to edge).
- Bottom tabs: `NavigationBar` with one `NavigationBarItem` per top-level route; navigate with
  `launchSingleTop = true` and `restoreState = true` (see the example).

## Checklist

- Every screen reachable, every screen has a way back.
- Empty states: a list with nothing in it says what to do ("No notes yet: tap + to add one").
- Rotation keeps what was typed (state in the ViewModel or `rememberSaveable`).
