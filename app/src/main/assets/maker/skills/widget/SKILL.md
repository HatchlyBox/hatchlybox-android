---
name: widget
description: Add a home-screen widget to the app with Jetpack Glance - a small view on the user's home screen that shows the app's data and opens the app when tapped, updated when the data changes. Use when the user wants something visible on the home screen (a counter, today's habits, a quote, a countdown).
---

# A home-screen widget

Glance builds a home-screen widget in Compose style. It needs three pieces: the widget, a receiver,
and an info file. Working code: `example/HomeWidget.kt` and `example/res/xml/skill_widget_info.xml`.

## Gradle

```kotlin
implementation(libs.androidx.glance.appwidget)
implementation(libs.androidx.glance.material3)
```

## Manifest

Inside `<application>`:

```xml
<receiver android:name=".HomeWidgetReceiver" android:exported="true">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
    </intent-filter>
    <meta-data android:name="android.appwidget.provider" android:resource="@xml/skill_widget_info" />
</receiver>
```

Copy `example/res/xml/skill_widget_info.xml` to `app/src/main/res/xml/` (rename it, and its reference,
to fit the app; give it a `<string>` description).

## How it fits together

- Glance has its own small set of building blocks: `androidx.glance.layout.Column/Row/Box`,
  `androidx.glance.text.Text`, `androidx.glance.Button`, `androidx.glance.Image`. Normal Compose
  (`androidx.compose.*`) composables don't work inside it: check every import.
- Read the data in `provideGlance` before `provideContent` (from Room or DataStore, see the data skill).
- After the app changes the data, call `HomeWidget().updateAll(context)` so the widget redraws.
- A tap opens the app: `actionStartActivity(component)` with the app's launch intent's non-null `.component`.
- `GlanceTheme` follows the phone's colours (dynamic colour on Android 12+).

## Rules

- Keep it small and glanceable: one number or a few lines. Sizes in the info file: `minWidth`
  `110dp`, `minHeight` `40dp` per cell.
- Widgets update at most every 30 minutes on their own (`updatePeriodMillis`); call `updateAll` for
  changes made in the app.
