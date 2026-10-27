---
name: data
description: Save the user's data on the phone so it's still there after the app closes - lists of items in a Room database, and settings or small values in DataStore. Use for to-do lists, logs, history, favourites, scores, or any setting the user changes.
---

# Saving data on the phone

- **Lists of things** (tasks, expenses, entries): a Room database. Queries return a `Flow`, so the
  screen updates by itself when the data changes.
- **Settings and single values** (dark mode, a name, the last score): DataStore Preferences.

Working code: `example/Data.kt` (database, settings, a ViewModel and a screen). Copy it, rename the
package to the app's, and adapt the entity's fields.

## Gradle

```kotlin
// app/build.gradle.kts
plugins {
    // ...the template's three, plus:
    alias(libs.plugins.ksp)
}
dependencies {
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
}
```

KSP is already declared in the root `build.gradle.kts`; only the `alias(libs.plugins.ksp)` line goes in
the app's plugins block. Don't use kapt.

## Rules

- One `AppDatabase` for the whole app (`AppDatabase.get(context)`), never a new one per screen.
- DAO functions that change data are `suspend`; reads return `Flow<List<...>>`.
- In the ViewModel: `dao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())`.
- Changing the entity after a release needs a migration: bump `version` and add
  `.fallbackToDestructiveMigration()` only while the app isn't published yet.
- Keep `exportSchema = false` (no schema folder in this project).
- Nothing leaves the phone: say so in the store listing (`"dataCollected": false`).
