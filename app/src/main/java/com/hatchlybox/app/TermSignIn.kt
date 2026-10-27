package com.hatchlybox.nightly

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * A terminal widget signing in. While you're in the browser the launcher is in the background, and Android cuts a
 * background app's network, so the CLI's last step (trading the browser's code for a token) fails with
 * "getaddrinfo EAI_AGAIN". Settings' sign-in runs in a foreground service and doesn't have this problem; this gives
 * a terminal widget the same: from the moment it shows a sign-in link until it says it's signed in, its session
 * ends, or ten minutes pass.
 */
object TermSignIn {
    private val link = Regex("""https?://[^\s"'<>]*(/oauth|oauth2|/authorize\?|/login/device|/device\?)[^\s"'<>]*""", RegexOption.IGNORE_CASE)
    private val done = Regex("""login successful|logged in|signed in|successfully authenticated|authentication successful""", RegexOption.IGNORE_CASE)
    private val waiting = HashSet<String>()
    private val seen = HashMap<String, MutableSet<String>>()
    private val tails = HashMap<String, String>()

    /** A piece of session [id]'s output, as it arrives. */
    fun saw(context: Context, id: String, chunk: String) {
        val start: Boolean
        val stop: Boolean
        synchronized(this) {
            // A little of what came before, for a link or a message split across reads.
            val tail = ((tails[id] ?: "") + Ansi.strip(chunk)).takeLast(4000)
            tails[id] = tail
            val urls = link.findAll(tail).map { it.value }.toList()
            val fresh = urls.filter { seen.getOrPut(id) { HashSet() }.add(it) }
            stop = id in waiting && done.containsMatchIn(tail.substringAfterLast(urls.lastOrNull() ?: "\u0000"))
            if (stop) { waiting -= id; tails[id] = "" }
            start = !stop && fresh.isNotEmpty() && waiting.add(id)
        }
        if (start) runCatching { context.startForegroundService(Intent(context, TermSignInService::class.java)) }
        if (stop) settle(context)
    }

    /** Session [id] ended or was closed. */
    fun ended(context: Context, id: String) {
        val was = synchronized(this) { tails.remove(id); seen.remove(id); waiting.remove(id) }
        if (was) settle(context)
    }

    @Synchronized fun clear() { waiting.clear() }

    private fun settle(context: Context) {
        if (synchronized(this) { waiting.isEmpty() }) runCatching { context.startService(Intent(context, TermSignInService::class.java).setAction("stop")) }
    }
}

/** Keeps Hatchlybox online while a terminal widget signs in (see [TermSignIn]). */
class TermSignInService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("builds", "Builds and account sign-in", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "builds").setSmallIcon(R.drawable.ic_stat_hatchlybox)
            .setContentTitle("Hatchlybox · Signing in").setContentText("Staying online while you sign in to the terminal's helper")
            .setOngoing(true).setOnlyAlertOnce(true).build()
        if (intent?.action == "stop") { finish(); return START_NOT_STICKY }
        if (Build.VERSION.SDK_INT >= 29) startForeground(75, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(75, n)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ TermSignIn.clear(); finish() }, 10 * 60_000L)
        return START_NOT_STICKY
    }

    private fun finish() {
        handler.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) { TermSignIn.clear(); finish() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
