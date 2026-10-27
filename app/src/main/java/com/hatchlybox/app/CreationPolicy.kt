package com.hatchlybox.app

/** Shared execution guidance for immediate, setup-pending and queued creations. */
object CreationPolicy {
    fun request(idea: String, target: String): String = idea + """

Implementation guidance from Hatchlybox:
Honor the user's explicitly requested platforms, runtime, framework and native capabilities. The selected creation target is $target. Hatchlybox itself runs on Android: whatever the target, make it usable from this phone (a web app runs inside Hatchlybox and goes on the phone as an app with one tap), and never target the internal Linux build container itself. Favor portable domain logic, open data formats, export/import and separation of platform integrations from shared behavior. Where the requirements allow it, choose a maintained cross-platform approach that preserves current-device performance, accessibility and native capabilities. Avoid unnecessary platform coupling or dependencies and document practical conversion paths and limitations. Never promise automatic or lossless conversion or claim untested platforms work. Build and verify the selected target first; additional platform builds require the user's request and suitable toolchains.
""".trimEnd()
}
