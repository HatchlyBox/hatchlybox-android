# Improvement pass

The centered Create your app home, its whitespace, carousel, and folded app/settings surfaces remain. Package, bundled maker engine, dependency catalog, toolchain, Play files, and start-screen game are unchanged.

## Reliability fixes

- SDK setup can resume a stale running marker after process death when no runtime owns it. Cache polling retains the previous library/job/setup snapshot during partial JSON writes.
- Startup invokes engine health before reading job state, allowing abandoned job state to be reconciled after process death. Engine synchronization failures are surfaced.
- Native setup, Godot installation, and engine mutations now reject overlapping work. App action buttons include installation in their idle checks; stop requests reject double taps.
- Agent installation checks every required executable before writing the ready marker. Failed agent downloads can no longer silently count as successful setup.
- Setup log output is also written to disk for subsequent launches.
- Pending ideas keep their original helper and remain saved when runtime startup fails; they are cleared only after the runtime accepts the task. Pending builds take priority over optional Godot installation.
- Godot exit failures now show a message; running state clears in finally.
- Version readers close their streams. Linux command and setup processes are destroyed in finally; HTTP metadata connections disconnect.
- Runtime terminal input uses one serial executor, captures the destination process, and reports write errors. Runtime exits destroy their process. Duplicate service starts cannot spawn parallel tasks. Android foreground-service timeout stops its process and service.
- Terminal scrollback is bounded to 2,000 rows. Escape sequences and numeric cursor parameters are bounded to prevent runaway allocations from malformed output.
- Claude stream rendering skips non-object content blocks rather than crashing.
- Navigation and home/change drafts persist locally. Listing/release-note dialogs survive recreation. Project helper choice and sorting persist locally.
- Release-note and listing write failures show messages. Service-account imports enforce the size limit during reading, before allocating an entire document.
- Legacy Linux reset is disabled with an explanation: native and web projects share that environment. Legacy updates reject active work; native work also blocks legacy prompts/sign-ins.
- Legacy status results publish on the main thread. Disconnect resets the checking flag before refreshing. Closing a sign-in while its process starts destroys the late process. Completed chat/console processes are cleaned up.
- Legacy library saves use a temporary file and atomic rename, and report failures. Malformed rows are skipped; project IDs use UUIDs. Chat output is bounded.
- Missing/unreadable WebView resources return an HTTP error instead of throwing on the interception thread.
- Wallpaper-driven theme changes are removed in favor of the existing indigo light/dark palette. The home submit control has a 48dp target. Existing scaffold inset/IME handling is retained.

## Finished features and parity decisions

- Per-app helper picker for each improvement, independent of the new-app default.
- Library search is available even for small libraries; matches name, slug, or package. Sort by newest or name, with an explicit no-results state. Controls stay inside See all.
- Confirmed removal moves project code/history into private trash. Settings → Recently deleted restores the latest removed project without replacing an existing folder. Build artifacts and upload keys remain intact. This is intentionally recoverable removal, not permanent deletion; older trash folders are retained.
- Foreground tasks issue a separate completion/attention notification with a link back to the app. Notification denial does not prevent tasks from completing.
- Existing live engine phase/message, stop confirmation, APK sharing with receipt validation, and copy-log actions were retained rather than duplicated.
- Existing GitHub, backups, Play preparation/review/upload, feature checks, and wireless verification surfaces were retained after comparison with PARITY.md and engine command dispatch.
- Subtle recipes: existing expanding-fold motion, light display typography and tabular labels, progress bars paired with task wording, predictive-back drag/cancel feedback, system-respecting fold haptics. No added home decoration.

## Validation limits

All application Kotlin files were reviewed; no Gradle invocation or additional coding agents were used, as required by the managed job. No tools were installed. `hatchlybox peek look` could not see the app because wireless debugging is off. Compilation and runtime interaction remain for the managed backend and subsequent phone verification; no runtime passes are claimed. First-install work still retries on next launch if Android kills the application process; service-backed build tasks retain the engine's existing durable job state.
