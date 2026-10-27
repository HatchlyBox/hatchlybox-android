package com.hatchlybox.nightly

import android.app.*
import android.content.*
import android.os.*
import androidx.compose.runtime.*
import java.io.File
import kotlin.concurrent.thread

/** Owns the standalone app's process, so a build continues when its activity goes away. */
object MakerRuntime {
    var running by mutableStateOf(false)
    var title by mutableStateOf("")
    var output by mutableStateOf("")
    var code by mutableStateOf<Int?>(null)
    /** When the task last printed something (ms): a long silence may mean it's stuck. */
    var lastOutput by mutableLongStateOf(0L)
    /** Asks for the task console (a tap on the task's notification); MakerScreen opens it and sets this back to 0. */
    var opens by mutableIntStateOf(0)
    /** What the agent said it did, from its done mark. */
    var doneNote by mutableStateOf("")
    // A file the agent writes as its last step (`widget done`): the task ends soon after, even if the agent keeps running.
    @Volatile private var doneMark: File? = null
    @Volatile private var endedByMark = false
    @Volatile private var cancelled = false
    var stopping by mutableStateOf(false)
    private var taskId = ""
    @Volatile private var process: java.lang.Process? = null
    private val main = Handler(Looper.getMainLooper())
    private val input = java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "task-input").apply { isDaemon = true } }
    fun send(value: String) { val target = process ?: return; input.execute { runCatching { synchronized(target) { target.outputStream.apply { write(value.toByteArray()); flush() } } }.onFailure { main.post { output += "\nCould not send input: ${it.message}" } } } }
    fun stop() { cancelled = true; stopping = running; process?.destroy() }
    /** The last line worth showing in the notification: what the task is doing now. */
    fun status(): String = output.lineSequence().toList().asReversed().asSequence()
        .map { line -> if (line.trimStart().startsWith("{")) Agent.CLAUDE.render(line.trim())?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: "" else Ansi.strip(line) }
        .map { it.trim().trimStart('>', '|', '›', '•', '*', '·', '-').trim() }.firstOrNull { it.length > 2 }?.take(100) ?: "Starting…"
    fun start(context: Context, title: String, command: String, doneMark: File? = null, request: String? = null): Boolean {
        if (running) return false
        doneMark?.delete(); this.doneMark = doneMark; endedByMark = false; doneNote = ""
        cancelled = false; stopping = false
        taskId = TaskHistory.begin(context, request ?: title, "engine")
        if (taskId.isBlank()) { output = "Could not save task state. Free storage before retrying."; code = -1; return false }
        running = true; this.title = title; output = ""; code = null; lastOutput = System.currentTimeMillis()
        val intent = Intent(context, MakerService::class.java).putExtra("command", command)
        runCatching { context.startForegroundService(intent) }.onFailure { running = false; output = it.message ?: "Couldn't start task"; code = -1; TaskHistory.finish(taskId, "failed", output) }
        return running
    }
    fun execute(context: Context, command: String, done: () -> Unit) {
        thread(name = "hatchlybox-task") {
            val screen = Screen()
            var result = -1
            try {
                val p = Linux(context).start(command, pty = true)
                process = p
                if (cancelled) p.destroy()
                watchDone(p)
                val reader = p.inputStream.reader()
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf); if (n < 0) break
                    screen.feed(String(buf, 0, n))
                    val text = screen.text().takeLast(100000)
                    main.post { if (text != output) lastOutput = System.currentTimeMillis(); output = text }
                }
                result = p.waitFor()
            } catch (e: Exception) { main.post { output += "\n${e.message}" } }
            val rc = if (cancelled) 130 else if (endedByMark) 0 else result
            doneMark?.delete(); doneMark = null
            process?.destroy()
            process = null
            main.post { code = rc; running = false; stopping = false
                TaskHistory.finish(taskId, if (cancelled) "cancelled" else if (rc == 0) "finished" else "needs-attention", doneNote.ifBlank { output.takeLast(2000) })
                done() }
        }
    }
    /** Once the done mark appears, the agent gets 20 s to write its last words; then the task ends as finished. */
    private fun watchDone(p: java.lang.Process) {
        val mark = doneMark ?: return
        thread(name = "task-done", isDaemon = true) {
            while (p.isAlive && !mark.exists()) Thread.sleep(2000)
            if (!p.isAlive) return@thread
            val note = runCatching { mark.readText().trim() }.getOrDefault("")
            main.post { doneNote = note }
            if (p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) return@thread
            endedByMark = true
            main.post { output += "\n\n✓ Finished. The agent was still running after it said it was done, so Hatchlybox ended it." }
            p.destroy()
        }
    }
}

class MakerService : Service() {
    private var executing = false
    override fun onBind(intent: Intent?) = null
    override fun onDestroy() { if (executing) MakerRuntime.stop(); super.onDestroy() }
    override fun onTimeout(startId: Int, fgsType: Int) { MakerRuntime.stop(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Stop, from the notification.
        if (intent?.action == "stop") { MakerRuntime.stop(); return START_NOT_STICKY }
        val command = intent?.getStringExtra("command") ?: return START_NOT_STICKY
        if (executing) return START_NOT_STICKY
        executing = true
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("builds", "Builds and account sign-in", NotificationManager.IMPORTANCE_LOW))
        // A tap opens the task console, where the agent's output shows as it works.
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("openTask", true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = Notification.Action.Builder(null, "Stop", PendingIntent.getService(this, 1, Intent(this, MakerService::class.java).setAction("stop"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        fun progress(text: String) = Notification.Builder(this, "builds").setSmallIcon(R.drawable.ic_stat_create)
            .setContentTitle("Hatchlybox · ${MakerRuntime.title}").setContentText(text).setSubText("Tap to watch")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(stop).build()
        startForeground(7, progress("Starting…"))
        // What the agent is doing now, every few seconds while it runs.
        val ticker = Handler(Looper.getMainLooper())
        var shown = ""
        val tick = object : Runnable { override fun run() {
            if (!executing) return
            val now = MakerRuntime.doneNote.ifBlank { MakerRuntime.status() }
            if (now != shown) { shown = now; runCatching { manager.notify(7, progress(now)) } }
            ticker.postDelayed(this, 3000)
        } }
        ticker.postDelayed(tick, 3000)
        MakerRuntime.execute(this, command) {
            executing = false
            ticker.removeCallbacks(tick)
            stopForeground(STOP_FOREGROUND_REMOVE)
            manager.createNotificationChannel(NotificationChannel("results", "Task results", NotificationManager.IMPORTANCE_DEFAULT))
            val result = Notification.Builder(this, "results").setSmallIcon(R.drawable.ic_stat_hatchlybox)
                .setContentTitle(if (MakerRuntime.code == 0) "Task finished" else "Task needs attention")
                .setContentText(MakerRuntime.doneNote.ifBlank { MakerRuntime.title }).setContentIntent(open).setAutoCancel(true).build()
            runCatching { manager.notify(8, result) }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}
