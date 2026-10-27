package com.hatchlybox.app

import android.app.*
import android.content.*
import android.os.*
import android.util.AtomicFile
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.*
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Commit complete state or retain the last good version. All access to a file shares one lock. */
object SafeFiles {
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    fun write(file: File, text: String) = synchronized(locks.getOrPut(file.absolutePath) { Any() }) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(text.toByteArray()); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    fun read(file: File): String = synchronized(locks.getOrPut(file.absolutePath) { Any() }) {
        AtomicFile(file).openRead().bufferedReader().use { it.readText() }
    }
    fun space(context: Context, needed: Long) {
        require(context.filesDir.usableSpace >= needed + 128L * 1024 * 1024) { "Not enough free space. Open Tasks → Storage to free space, then retry. Your files are kept." }
    }
}

object TaskHistory {
    val entries = mutableStateListOf<JSONObject>()
    private var loaded = false
    private lateinit var app: Context
    fun load(context: Context) {
        if (loaded) return
        loaded = true; app = context.applicationContext
        val saved = runCatching { JSONArray(SafeFiles.read(File(app.filesDir, "tasks.json"))) }.getOrDefault(JSONArray())
        saved.objects().forEach {
            if (it.text("state") in setOf("running", "starting", "stopping")) it.put("state", "interrupted").put("result", "Interrupted. Check the last action before retrying; it may already have happened.")
            entries += it
        }
        save()
    }
    var storageError by mutableStateOf("")
    private fun save(): Boolean = runCatching { SafeFiles.write(File(app.filesDir, "tasks.json"), JSONArray(entries.take(100)).toString()); storageError = ""; true }.getOrElse { storageError = "Task history could not be saved: ${it.message}"; false }
    fun begin(context: Context, title: String, kind: String): String {
        load(context)
        val id = UUID.randomUUID().toString()
        val versions = runCatching { JSONObject(Linux(app).hostFile("/root/.cache/hatchlybox/tool-versions.json").readText()) }.getOrDefault(JSONObject())
        entries.add(0, JSONObject().put("id", id).put("title", title.take(20000)).put("kind", kind).put("at", System.currentTimeMillis()).put("state", "running").put("toolVersions", versions))
        while (entries.size > 100) entries.removeAt(entries.lastIndex)
        if (!save()) { entries.removeAt(0); return "" }
        return id
    }
    fun finish(id: String, state: String, result: String) {
        val at = entries.indexOfFirst { it.text("id") == id }; if (at < 0) return
        entries[at] = JSONObject(entries[at].toString()).put("state", state).put("result", result.take(4000)).put("finished", System.currentTimeMillis()).put("durationMs", System.currentTimeMillis() - entries[at].optLong("at"))
        save()
    }
}

/** Owns chat/phone tasks even while the activity is covered. Stopping a service cancels its task. */
class ChatTaskService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!AgentChat.running) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            else main.postDelayed(this, 1500)
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { AgentChat.stop(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("chat-work", "Agent tasks", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 72, Intent(this, MainActivity::class.java).putExtra("openTask", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 73, Intent(this, ChatTaskService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        try {
            startForeground(72, Notification.Builder(this, "chat-work").setSmallIcon(R.drawable.ic_stat_hatchlybox).setContentTitle("Hatchlybox · Agent working")
                .setContentText("Tap to return. Stop is available in Chat.").setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build())
        } catch (e: Exception) { AgentChat.stop(); stopSelf(); return START_NOT_STICKY }
        main.removeCallbacks(tick); main.postDelayed(tick, 1500)
        return START_NOT_STICKY
    }
    override fun onDestroy() { main.removeCallbacks(tick); if (AgentChat.running) AgentChat.stop(); super.onDestroy() }
    override fun onTimeout(startId: Int, fgsType: Int) { AgentChat.stop(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
}

data class StorageItem(val label: String, val file: File, val bytes: Long, val clearable: Boolean = false)
object NightlyStorage {
    fun size(file: File): Long {
        if (Files.isSymbolicLink(file.toPath())) return 0
        if (file.isFile) return file.length()
        return file.listFiles().orEmpty().sumOf { size(it) }
    }
    fun scan(c: Context): List<StorageItem> {
        val l = Linux(c)
        val categories = listOf(
            Triple("Projects", "/root/apps", false), Triple("SDK, tools and engine", "/root/.local/share", false),
            Triple("Build and download cache", "/root/.cache", false), Triple("Gradle cache", "/root/.gradle", false),
            Triple("Backups and signing material", "/root/Backups", false), Triple("Deleted projects", "/root/.local/share/hatchlybox-trash", true),
            Triple("Deleted widgets", "/root/widgets/.trash", true)
        ).map { StorageItem(it.first, l.hostFile(it.second), size(l.hostFile(it.second)), it.third) } +
            StorageItem("Local action model", File(c.filesDir, "assist"), size(File(c.filesDir, "assist")))
        val projects = l.hostFile("/root/apps").listFiles().orEmpty().filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
        val outputs = projects.flatMap { project -> listOf("build", "app/build", ".gradle", ".kotlin", "godot/.godot").map { File(project, it) } }.filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
        return categories + outputs.map { StorageItem("Rebuildable: ${it.parentFile?.name}/${it.name}", it, size(it), true) }
    }
    fun trash(c: Context): List<File> = Linux(c).hostFile("/root/.local/share/hatchlybox-trash").listFiles().orEmpty().filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }.sortedByDescending { it.lastModified() }
    fun restore(c: Context, file: File) {
        val root = Linux(c).hostFile("/root/.local/share/hatchlybox-trash").canonicalFile
        require(file.canonicalFile.parentFile == root) { "Invalid trash entry" }
        val meta = runCatching { JSONObject(File(file, ".hatchlybox.json").readText()) }.getOrNull()
        val slug = meta?.text("slug")?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]+")) } ?: error("Cannot identify this project safely")
        val dest = Linux(c).hostFile("/root/apps/$slug")
        require(!dest.exists()) { "A project named $slug already exists; it was kept." }
        check(file.renameTo(dest)) { "Could not restore the project" }
    }
    private fun deleteSafely(file: File) {
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory) file.listFiles().orEmpty().forEach { deleteSafely(it) }
        check(file.delete() || !file.exists()) { "Could not remove ${file.name}" }
    }
    fun purge(c: Context, file: File) {
        val roots = listOf("/root/.local/share/hatchlybox-trash", "/root/widgets/.trash").map { Linux(c).hostFile(it).canonicalFile }
        val projects = Linux(c).hostFile("/root/apps").canonicalFile
        val path = file.canonicalFile
        val generated = projects.listFiles().orEmpty().filter { !Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == projects }.any { project -> listOf("build", "app/build", ".gradle", ".kotlin", "godot/.godot").any { File(project, it).canonicalFile == path && path.toPath().startsWith(project.canonicalFile.toPath()) } }
        require(generated || roots.any { root -> path == root || path.parentFile == root }) { "Only trash or known generated directories can be removed here" }
        if (file.exists()) deleteSafely(file)
    }
}

@Composable
fun TasksAndStorageSettings(m: MakerModel) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { TaskHistory.load(c); RequestQueue.load(c) }
    LaunchedEffect(show) { if (show) { TaskHistory.load(c); RequestQueue.load(c) } }
    TextButton(onClick = { show = true }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(if (AgentChat.stopping || MakerRuntime.stopping) "Stopping… · Tasks and storage" else if (AgentChat.running || MakerRuntime.running) "Working · Tasks and storage" else "Tasks and storage")
    }
    if (show) {
        var tab by remember { mutableIntStateOf(0) }; var search by remember { mutableStateOf("") }; var detail by remember { mutableStateOf<JSONObject?>(null) }; var storage by remember { mutableStateOf(listOf<StorageItem>()) }
        var trash by remember { mutableStateOf(listOf<File>()) }; var error by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf<File?>(null) }; var scanning by remember { mutableStateOf(false) }
        val idle = !m.agentsBusy && !m.installing && !Shells.active && !DeviceSync.running && !DeviceSync.serving
        fun scan() { scope.launch { scanning = true; try { withContext(Dispatchers.IO) { storage = NightlyStorage.scan(c); trash = NightlyStorage.trash(c) } } finally { scanning = false } } }
        AlertDialog(onDismissRequest = { show = false }, title = { Text("Tasks and storage") }, confirmButton = { TextButton(onClick = { show = false }) { Text("Done") } }, text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row { TextButton(onClick = { tab = 0 }) { Text("Tasks") }; TextButton(onClick = { tab = 1; scan() }) { Text("Storage") } }
                if (tab == 0) {
                    AgentQuestionCard()
                    if (TaskHistory.storageError.isNotBlank()) Text(TaskHistory.storageError, color = MaterialTheme.colorScheme.error)
                    Text("Device sync: ${DeviceSync.serveMessage}")
                    if (DeviceSync.lastSuccess > 0) Text("Last success: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(DeviceSync.lastSuccess))}")
                    if (DeviceSync.serving) TextButton(onClick = { DeviceSync.setAuto(c, m, false) }) { Text("Stop automatic sync") }
                    else if (DeviceSync.devices.isNotEmpty()) TextButton(onClick = { DeviceSync.setAuto(c, m, true) }) { Text("Start automatic sync") }
                    if (RequestQueue.error.isNotBlank()) { Text(RequestQueue.error, color = MaterialTheme.colorScheme.error); TextButton(onClick = { RequestQueue.retrySave() }, enabled = !m.agentsBusy) { Text("Save queue again") } }
                    RequestQueue.entries.toList().forEach { entry ->
                        var draft by remember(entry.text("id")) { mutableStateOf(entry.text("text")) }
                        Text("${entry.text("state").replaceFirstChar { it.uppercase() }} · ${entry.text("mode")} · ${entry.text("agent")}")
                        if (entry.text("state") in setOf("interrupted", "dispatching")) {
                            Text("Dispatch was attempted. Review Tasks and the target app before repeating this request; it may already have happened.")
                            TextButton(onClick = { RequestQueue.requeue(entry) }, enabled = !m.agentsBusy) { Text("Reviewed — queue again") }
                        }
                        OutlinedTextField(draft, { draft = it }, label = { Text("Request") })
                        Row { TextButton(onClick = { RequestQueue.edit(entry, draft) }) { Text("Save") }; TextButton(onClick = { RequestQueue.moveUp(entry) }) { Text("Move up") }; TextButton(onClick = { RequestQueue.remove(entry) }) { Text("Cancel") } }
                        if (!m.agentOk(entry.text("agent"))) TextButton(onClick = { m.connect(entry.text("agent")) }) { Text("Connect ${entry.text("agent")}") }
                    }
                    AgentChat.archives(c).take(20).forEach { archive -> TextButton(onClick = { AgentChat.restore(c, archive); AgentChat.show(); show = false }, enabled = !AgentChat.running) { Text("Open conversation ${archive.nameWithoutExtension}") } }
                    if (m.pendingIdea.isNotBlank()) { Text("Queued: ${m.pendingIdea}"); TextButton(onClick = { m.dropPending() }) { Text("Cancel queued app") } }
                    if (AgentChat.running) { Text("Agent task is running. Inspect the result before repeating a phone action."); TextButton(onClick = { AgentChat.stop() }, enabled = !AgentChat.stopping) { Text("Stop agent") }; TextButton(onClick = { AgentChat.show(); show = false }) { Text("Open chat") } }
                    if (MakerRuntime.running) { Text(MakerRuntime.title); TextButton(onClick = { m.showConsole = true; show = false }) { Text("Open task console") }; TextButton(onClick = { MakerRuntime.stop() }, enabled = !MakerRuntime.stopping) { Text("Stop task") } }
                    if (TaskHistory.entries.isEmpty()) Text("Accepted tasks and their outcomes appear here.")
                    OutlinedTextField(search, { search = it }, label = { Text("Search task history") }, modifier = Modifier.fillMaxWidth())
                    TaskHistory.entries.filter { search.isBlank() || it.text("title").contains(search, true) || it.text("state").contains(search, true) }.take(30).forEach { e ->
                        Text("${e.text("state").replaceFirstChar { it.uppercase() }} · ${e.text("title").take(180)}\n${e.text("result").take(350)}")
                        TextButton(onClick = { detail = e }) { Text("View details") }
                        if (e.text("kind").startsWith("agent:") || e.text("kind") in setOf("action", "engine")) TextButton(onClick = { m.restoredRequest = e.text("title"); show = false }) { Text("Edit as a new request") }
                        HorizontalDivider()
                    }
                    var report by remember { mutableStateOf<File?>(null) }
                    TextButton(onClick = { report = Diagnostics.create(c, m) }) { Text("Prepare redacted diagnostics") }
                    report?.let { file -> Text("Report includes versions, resource status and task IDs. Prompts, outputs, contacts and credentials are omitted."); TextButton(onClick = { Diagnostics.share(c, file) }) { Text("Share diagnostic report…") } }
                } else {
                    Text("Free: ${android.text.format.Formatter.formatFileSize(c, c.filesDir.usableSpace)}")
                    if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                    storage.forEach { item -> Text("${item.label}: ${android.text.format.Formatter.formatFileSize(c, item.bytes)}"); if (item.clearable) TextButton(onClick = { confirm = item.file }, enabled = idle && item.bytes > 0) { Text(if (item.label.startsWith("Rebuildable:")) "Clear generated files" else "Empty ${item.label.lowercase()}") } }
                    if (Assist.state == "downloading") TextButton(onClick = { Assist.cancelDownload() }) { Text("Pause model download") }
                    if (Assist.state == "ready" || Assist.state == "downloading") TextButton(onClick = { Assist.remove(c); scan() }, enabled = !AgentChat.running) { Text("Remove local action model") }
                    listOf("claude", "codex", "opencode").forEach { helper ->
                        if (m.linux.hostFile("/root/.local/share/hatchlybox-helper-backups/$helper/current/manifest.json").isFile) TextButton(onClick = {
                            MakerRuntime.start(c, "Restore $helper helper", "python3 /root/.local/bin/helper-backup restore ${quote(helper)}"); m.showConsole = true; show = false
                        }, enabled = idle) { Text("Restore previous $helper version") }
                    }
                    m.engineVersions().forEach { release -> TextButton(onClick = { m.selectEngine(release) }, enabled = idle) { Text("Use engine ${release.name}") } }
                    Text("Projects, SDKs, signing keys and backups are protected. Cleanup is disabled while sync or another task is active.")
                    trash.forEach { f -> Text(f.name); Row { TextButton(onClick = { scope.launch { error = runCatching { withContext(Dispatchers.IO) { NightlyStorage.restore(c, f) }; m.refresh(); scan() }.exceptionOrNull()?.message.orEmpty() } }, enabled = idle) { Text("Restore") }; TextButton(onClick = { confirm = f }, enabled = idle) { Text("Delete permanently") } } }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        })
        detail?.let { e -> AlertDialog(onDismissRequest = { detail = null }, title = { Text(e.text("state")) }, text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(e.text("title") + "\n\n" + e.text("result"), Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) } }, confirmButton = { TextButton(onClick = { detail = null }) { Text("Done") } }) }
        confirm?.let { f -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Delete permanently?") }, text = { Text("${f.name} will be removed. Generated build files can be rebuilt; deleted projects cannot be recovered after this step.") }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("Keep") } }, confirmButton = { TextButton(onClick = { confirm = null; scope.launch { error = runCatching { withContext(Dispatchers.IO) { NightlyStorage.purge(c, f) }; scan() }.exceptionOrNull()?.message.orEmpty() } }, enabled = idle) { Text("Delete") } }) }
    }
}

@Composable
fun AgentQuestionCard() {
    val question = AgentScreen.pendingQuestion
    if (question.isBlank()) return
    var answer by remember(question) { mutableStateOf("") }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Waiting for your answer", style = MaterialTheme.typography.labelLarge)
            Text(question)
            AgentScreen.pendingOptions.forEach { option -> TextButton(onClick = { AgentScreen.answered(AgentScreen.askNo, option) }) { Text(option) } }
            OutlinedTextField(answer, { answer = it }, label = { Text("Your answer") }, modifier = Modifier.fillMaxWidth())
            Row { TextButton(onClick = { AgentScreen.answered(AgentScreen.askNo, answer) }, enabled = answer.isNotBlank()) { Text("Answer") }; TextButton(onClick = { AgentScreen.answered(AgentScreen.askNo, "") }) { Text("Not now") } }
        }
    }
}

class SyncTaskService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { if (!DeviceSync.serving) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } else main.postDelayed(this, 3000) } }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { DeviceSync.stopServing(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("device-sync", "Device synchronization", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 74, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 75, Intent(this, SyncTaskService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        try { startForeground(74, Notification.Builder(this, "device-sync").setSmallIcon(R.drawable.ic_stat_hatchlybox).setContentTitle("Hatchlybox · Device sync")
            .setContentText("Listening for your paired devices").setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()) }
        catch (e: Exception) { DeviceSync.stopServing(); stopSelf(); return START_NOT_STICKY }
        main.removeCallbacks(tick); main.postDelayed(tick, 3000)
        return START_NOT_STICKY
    }
    override fun onDestroy() { main.removeCallbacks(tick); DeviceSync.stopServing(); super.onDestroy() }
    override fun onTimeout(startId: Int, fgsType: Int) { DeviceSync.stopServing(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
}

object Diagnostics {
    fun create(c: Context, m: MakerModel): File {
        val packageInfo = c.packageManager.getPackageInfo(c.packageName, 0)
        val engine = runCatching { m.linux.hostFile("/root/.local/share/hatchlybox/standalone-version").readText().trim() }.getOrDefault("not installed")
        val text = buildString {
            appendLine("Hatchlybox diagnostic report")
            appendLine("App: ${packageInfo.versionName}; Android SDK ${Build.VERSION.SDK_INT}; engine $engine")
            appendLine("Free storage: ${c.filesDir.usableSpace}; agent running: ${AgentChat.running}; engine running: ${MakerRuntime.running}")
            appendLine("Setup running: ${Setup.installing}; sync running: ${DeviceSync.serving}")
            appendLine("Recent task metadata (request text, output, contacts and credentials omitted):")
            TaskHistory.entries.take(30).forEach { e -> appendLine("${e.text("id")} ${e.text("kind")} ${e.text("state")} ${e.optLong("at")} durationMs=${e.optLong("durationMs")} versions=${e.optJSONObject("toolVersions") ?: "unknown"}") }
        }
        val file = File(c.filesDir, "diagnostics/nightly-report.txt")
        SafeFiles.write(file, text); return file
    }
    fun share(c: Context, file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(c, "${c.packageName}.files", file)
        c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share diagnostic report"))
    }
}
