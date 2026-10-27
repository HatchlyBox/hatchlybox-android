---
name: camera
description: Use the phone's camera - a live preview and taking photos saved in the app's own storage - with CameraX and the camera permission. Use for scanners, photo diaries, plant or receipt snapshots, mirrors, or anything that needs the camera.
---

# The camera

CameraX shows the live preview in a `PreviewView` and takes photos with `ImageCapture`. Photos are saved
in the app's own folder (`filesDir`), so no storage permission is needed. Working code:
`example/Camera.kt` (permission, preview, shutter button, the last photo shown).

## Gradle

```kotlin
implementation(libs.androidx.camera.camera2)
implementation(libs.androidx.camera.lifecycle)
implementation(libs.androidx.camera.view)
implementation(libs.androidx.lifecycle.runtime.compose)
implementation(libs.coil.compose)   // to show saved photos
```

## Manifest

```xml
<uses-feature android:name="android.hardware.camera" android:required="false" />
<uses-permission android:name="android.permission.CAMERA" />
```

`required="false"` keeps the app installable on devices without a camera; check
`packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)` before showing it.

## Rules

- Ask for the camera when the user opens the camera screen, never at startup. If they say no, explain
  why it's needed and offer the button again.
- Bind the camera to the screen's lifecycle (`LocalLifecycleOwner` from `androidx.lifecycle.compose`)
  so it stops when the screen goes away.
- Save to `context.filesDir`; to share a photo, use a FileProvider or the share sheet.
- Barcode or QR scanning needs ML Kit, which isn't in the tested library list: build it only if the user
  asks, and expect to fix versions.
