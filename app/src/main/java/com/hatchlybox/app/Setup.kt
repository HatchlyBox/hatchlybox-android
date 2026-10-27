package com.hatchlybox.nightly

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The tools install, owned by the process rather than a screen: switching to launcher mode, opening another
 * app or the activity being recreated never stops it, and a second screen never starts a second install.
 * Created on the main thread in MainActivity.onCreate, before any screen (see MakerRuntime).
 */
object Setup {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var installing by mutableStateOf(false)
    /** The first install's second part (agents, Java, Node) is running in the background. */
    var firstSetup by mutableStateOf(false)
    /** Godot (the engine for game apps) installing in the background, after the build tools. */
    var godotInstalling by mutableStateOf(false)
    var failure by mutableStateOf("")
    /** What the install or the start-up check is doing, in words. */
    var checking by mutableStateOf("")
    var setupLog by mutableStateOf("")
    /** Failed installs in a row and when the last one failed: retried by itself, a little later each time. */
    var failures = 0
    var failedAt = 0L
    /** The start-up check ran in this process (a launcher's process lives for days). */
    var startupDone = false
    var godotChecked = false

    /** Keeps the process (and the CPU, with the screen off) alive while something installs. */
    fun keepAlive(context: Context) {
        runCatching { context.startForegroundService(Intent(context, SetupService::class.java)) }
    }
}

class SetupService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var job: Job? = null
    override fun onBind(intent: Intent?) = null

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "setup").setSmallIcon(R.drawable.ic_stat_hatchlybox)
            .setContentTitle("Hatchlybox · Installing the tools").setContentText(text.ifBlank { "Runs in the background. You can keep using your phone." })
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive == true) return START_NOT_STICKY
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("setup", "Tools install", NotificationManager.IMPORTANCE_LOW))
        val first = notification(Setup.checking)
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(9, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(9, first)
        }.isSuccess
        if (!started) { stopSelf(); return START_NOT_STICKY }
        wake = runCatching { getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hatchlybox:setup").apply { acquire(3 * 60 * 60 * 1000L) } }.getOrNull()
        job = Setup.scope.launch {
            var shown = Setup.checking
            // A moment's grace: the install sets its flag just before asking for this service.
            delay(1500)
            while (Setup.installing || Setup.godotInstalling) {
                val now = if (Setup.godotInstalling && !Setup.installing) "Installing Godot for games" else Setup.checking
                if (now != shown) { shown = now; runCatching { manager.notify(9, notification(now)) } }
                delay(1500)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }

    override fun onDestroy() {
        job?.cancel()
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }
}
