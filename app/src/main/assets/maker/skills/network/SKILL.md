---
name: network
description: Fetch data from the internet - a public API returning JSON, images from URLs - with loading, error and retry states. Uses Ktor with kotlinx.serialization and Coil for images. Use for weather, news, quotes, exchange rates, search, or any app that shows online data.
---

# Data from the internet

Ktor fetches, kotlinx.serialization turns JSON into Kotlin classes, Coil shows images from URLs.
Working code: `example/Network.kt` (an API object, a ViewModel with loading / error / data states, a
screen with retry).

## Gradle

```kotlin
// app/build.gradle.kts
plugins {
    // ...the template's three, plus:
    alias(libs.plugins.kotlin.serialization)
}
dependencies {
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)   // only for images from URLs
}
```

## Manifest

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## Rules

- Only `https://` URLs; plain http is blocked by Android.
- Only free APIs that need no key. If the idea needs one, add a settings field where the user pastes
  their own key, kept in DataStore (see the data skill). Never put a key in the code.
- Every screen that loads has three states: loading (a spinner), failed (a short message and "Try
  again"), and the data. No connection must never crash the app.
- `Json { ignoreUnknownKeys = true }` and default values on every field, so API changes don't crash it.
- Data leaves the phone: the store listing says `"dataCollected": true` only if the app sends the
  user's data somewhere; just fetching public data doesn't count.
