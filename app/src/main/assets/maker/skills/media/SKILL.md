---
name: media
description: Play sound and video - an audio player with play, pause and seek, sounds bundled with the app, internet streams, and video in a player view - using Media3 ExoPlayer. Use for music, radio, podcasts, meditation or white-noise apps, soundboards, or video.
---

# Sound and video

Media3's ExoPlayer plays files from the app (`res/raw`), from the phone, or streamed over https.
Working code: `example/Media.kt` (an audio player with play, pause, a seek bar and time; a video view).

## Gradle

```kotlin
implementation(libs.androidx.media3.exoplayer)
implementation(libs.androidx.media3.ui)   // only for video (PlayerView)
```

## Manifest

Streaming over the internet needs `<uses-permission android:name="android.permission.INTERNET" />`.
Bundled sounds in `res/raw` need nothing.

## How it fits together

- One player per screen: `remember { ExoPlayer.Builder(context).build() }`, released in
  `DisposableEffect`'s `onDispose`, or it keeps playing and leaks.
- Sources: `MediaItem.fromUri("https://…")`, or
  `MediaItem.fromUri("android.resource://${context.packageName}/${R.raw.rain}")` for a bundled sound.
- Follow the player's state with a `Player.Listener` (playing or not); read the position every
  half second in a `LaunchedEffect` while playing.
- Loop a sound with `repeatMode = Player.REPEAT_MODE_ONE`.

## Rules

- Only sounds the app may use: write tones yourself, use public-domain or CC0 files, or stream a
  station's official public URL. No copyrighted music in `res/raw`.
- Keep playing in the background only if the app is about listening (a radio, a sleep sound); that
  needs a `MediaSessionService`, a foreground-service permission and a Play declaration, so do it only
  when asked.
- Pause when the screen goes away unless background playback was asked for.
