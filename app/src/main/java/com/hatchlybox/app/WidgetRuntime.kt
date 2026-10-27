package com.hatchlybox.app

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.util.Date

/** Visible-screen ticks only, aligned to real seconds/minutes and refreshed on resume. */
@Composable
internal fun rememberWidgetTime(interval: Long): Date {
    val owner = LocalLifecycleOwner.current
    var time by remember { mutableStateOf(Date()) }
    LaunchedEffect(owner, interval) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { time = Date(); delay((interval - System.currentTimeMillis() % interval).coerceAtLeast(100L)) }
        }
    }
    return time
}
