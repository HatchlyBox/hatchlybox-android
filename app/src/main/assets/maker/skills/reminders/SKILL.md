---
name: reminders
description: Send notifications and run work later or regularly - a reminder in 20 minutes, a daily nudge, a timer that ends while the app is closed. Uses WorkManager and a notification channel, and asks for the notification permission on Android 13+. Use for habit trackers, timers, medication or water reminders.
---

# Reminders and background work

WorkManager runs work later (once, after a delay) or regularly (every day), even after the app or the
phone restarts. The work posts a notification. Working code: `example/Reminders.kt`.

## Gradle

```kotlin
implementation(libs.androidx.work.runtime.ktx)
```

## Manifest

Inside `<manifest>`, before `<application>`:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

## How it fits together

1. `Notifications.createChannel(context)` once, when the app starts (in `MainActivity.onCreate`).
2. Ask for permission from the screen with `rememberNotificationPermission()` (example): on Android 13
   and later a notification needs the user's yes. Ask when the user turns reminders on, not at startup.
3. `Reminders.remindIn(context, minutes, title, text)` for once; `Reminders.daily(context, title, text)`
   for every day (one unique daily job, replaced when called again); `Reminders.cancelAll(context)`.
4. A tap on the notification opens the app.

## Rules

- Periodic work runs at most every 15 minutes and Android may shift it: say "around 9:00", don't promise
  the exact minute. Don't use exact alarms (`SCHEDULE_EXACT_ALARM` needs a Play declaration).
- If permission is refused, the app still works: show a line saying reminders are off, with a button
  that asks again.
- Notification icon: `android.R.drawable.ic_popup_reminder` works everywhere; a monochrome vector
  drawable of the app's own is nicer.
