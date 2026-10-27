package com.hatchlybox.app

import android.app.Application
import android.content.*
import android.net.Uri
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import android.os.Build
import org.json.*
import java.io.File

fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

/** A making word, then within a few words a thing that gets made: "make me a tiny habit tracker app", "build a game". */

/** A request that starts by using the phone: "open WhatsApp and…", "play some jazz on Spotify", "turn on wifi". */
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONObject.text(key: String, fallback: String = ""): String = if (isNull(key)) fallback else optString(key, fallback)
fun JSONArray?.strings(): List<String> = this?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()

/** Where an app stands, in words, and what can be done now: the first action is the main one. */
data class AppStatus(val key: String, val word: String, val detail: String, val tone: String, val actions: List<String>)
// "interrupted": stopped before it finished; the engine's health check carries it on from the saved project.
private val activePhases = setOf("queued", "agent", "checking", "building", "fixing", "art", "installing", "uploading", "interrupted")

/** A launchable app on the phone, for the home page's launcher grid. */
/** removable: not part of the phone's system, so it can be uninstalled. */
class PhoneApp(val label: String, val pkg: String, val activity: String, val icon: ImageBitmap?, val removable: Boolean = true)

class MakerModel(app: Application) : AndroidViewModel(app) {
    val linux = Linux(app)
    var apps by mutableStateOf(listOf<JSONObject>())
    /** The library as creations (the engine's creations.json): each once, with a system for each build. */
    var creations by mutableStateOf(listOf<JSONObject>())
    var job by mutableStateOf(JSONObject())
    var setup by mutableStateOf(JSONObject())
    var settings by mutableStateOf(JSONObject())
    var github by mutableStateOf(JSONObject())
    var play by mutableStateOf(JSONObject())
    var skills by mutableStateOf(JSONArray())
    var community by mutableStateOf(JSONObject())
    var testing by mutableStateOf(JSONObject())
    var log by mutableStateOf("")
    var buildError by mutableStateOf("")
    var toolsInstalled by mutableStateOf(false)
    /** The AI helpers are in: chat, web apps, widgets and themes work while Java and the Android SDK still install. */
    var agentsInstalled by mutableStateOf(false)
    /** Something else holds the private Linux: an engine task, or the part of the install the AI helpers wait for. */
    val agentsBusy get() = MakerRuntime.running || busy || updatingAgents || AgentChat.running || godotInstalling || installing && !agentsInstalled
    /** What the install is on now, for the notice when something has to wait for it. */
    val installStage get() = when {
        !basics -> "Hatchlybox is setting up its Linux (about a minute)"
        !agentsInstalled -> "the AI helpers are installing first (a few minutes)"
        !toolsInstalled -> "the AI helpers are ready; the Android build tools are installing"
        else -> "the Android build tools are being set up"
    }
    /** Says, kindly, that this has to wait for the tools; the home screen, library and apps work meanwhile. */
    fun stillInstalling(what: String) {
        notice = "$what as soon as the tools are ready: $installStage. Your home screen, library and apps work meanwhile."
    }
    /** Linux's basics and the engine are in: the app can be used while the rest installs. */
    var basics by mutableStateOf(false)
    // The install belongs to the process (Setup), so a new screen or launcher mode never stops or repeats it.
    var setupLog by Setup::setupLog
    var firstSetup by Setup::firstSetup
    var godotInstalling by Setup::godotInstalling
    var installing by Setup::installing
    var failure by Setup::failure
    /** What the start-up check is doing, in words, while it runs: shown on the home page. */
    var checking by Setup::checking
    private val prefs = app.getSharedPreferences("maker", 0)
    var page by mutableStateOf(prefs.getString("page", "apps") ?: "apps")
    var selected by mutableStateOf(prefs.getString("selected", "") ?: "")
    fun saveNavigation() { prefs.edit().putString("page", page).putString("selected", selected).apply() }
    fun draft(key: String) = prefs.getString("draft-$key", "") ?: ""
    var requestReferences by mutableStateOf(prefs.getString("draft-references", "") ?: "")
    fun saveDraft(key: String, value: String) { if (key == "references") requestReferences = value; prefs.edit().putString("draft-$key", value).apply() }
    /** Opens the app page with its Google Play section expanded. */
    var openPlay by mutableStateOf(false)
    // OpenCode by default: it works without an account (its free models), so the first idea needs no sign-in.
    var agent by mutableStateOf(prefs.getString("agent", "opencode") ?: "opencode")
    var autoUpdate by mutableStateOf(prefs.getBoolean("autoUpdate", true))
    /** An idea typed before the tools were ready: it's built as soon as they are. */
    var pendingIdea by mutableStateOf(prefs.getString("pendingIdea", "") ?: "")
    var showConsole by mutableStateOf(false)
    var notice by mutableStateOf("")
    var restoredRequest by mutableStateOf<String?>(null)
    var links by mutableStateOf(listOf<String>())
    val busy get() = job.text("phase") in activePhases
    val ready get() = toolsInstalled && setup.text("state") == "done"
    val app get() = apps.firstOrNull { it.text("slug") == selected }
    /** The selected creation when it has no Android version (made only for other systems). */
    val creation get() = creations.firstOrNull { it.text("slug") == selected }
    fun openCreation(slug: String) { selected = slug; openPlay = false; page = "app" }
    /** The systems a creation is made for, Android first. */
    fun systems(c: JSONObject): List<String> =
        c.optJSONObject("systems")?.keys()?.asSequence()?.toList().orEmpty().sortedBy { if (it == "android") "" else it }
    /** The finished version whose content changed last: what a new version is made from. */
    fun newestSystem(c: JSONObject): String {
        val s = creations.firstOrNull { it.text("slug") == c.text("slug") }?.optJSONObject("systems") ?: return "other"
        return s.keys().asSequence().filter { s.optJSONObject(it)?.text("word") !in setOf("being made", "not finished") }
            .maxByOrNull { s.optJSONObject(it)?.optLong("content") ?: 0L } ?: "other"
    }
    /** A system's line from the engine, e.g. "ready to export", for a creation's slug. */
    fun systemWord(slug: String, system: String): String =
        creations.firstOrNull { it.text("slug") == slug }?.optJSONObject("systems")?.optJSONObject(system)?.text("word").orEmpty()
    private val cache get() = linux.hostFile("/root/.cache/hatchlybox")
    private val data get() = linux.hostFile("/root/.local/share/hatchlybox")
    /** The tools were installed before this start: the start screen offers "Start app" at once. */
    val installedAtLaunch = linux.hasBasics && linux.hostFile("/root/.local/share/hatchlybox/standalone-version").exists()
    private var refreshing = false

    init {
        viewModelScope.launch {
            if (!installing) {
                withContext(Dispatchers.IO) { runCatching { if (!MakerRuntime.running) syncEngine() }.onFailure { e -> withContext(Dispatchers.Main) { failure = e.message ?: "Could not update the bundled tools" } } }
                if (linux.isReady && !MakerRuntime.running) withContext(Dispatchers.IO) { runCatching { linux.run("hatchlybox health") } }
            }
            refresh()
            startupCheck()
            while (isActive) {
                refresh(); buildPending(); processQueue(); ensureGodot()
                // A job stopped before it finished, with nothing watching it: the health check carries it on.
                if (job.text("phase") == "interrupted" && !MakerRuntime.running && linux.isReady) withContext(Dispatchers.IO) { runCatching { linux.run("hatchlybox health") } }
                if (!Setup.startupDone) startupCheck() else keepToolsWell()
                delay(if (busy || MakerRuntime.running || installing) 1500 else if (Gold.front) 8000 else 30000)
            }
        }
    }
    /**
     * A launcher's process lives for days, so "the next start" may be far off: a failed install is retried by
     * itself (after 1, 2, 4… minutes, up to six times), and the daily update runs while it's open too.
     */
    private fun keepToolsWell() {
        if (installing || busy || MakerRuntime.running || godotInstalling || updatingAgents || AgentChat.running || Shells.active) return
        val now = System.currentTimeMillis()
        if (failure.isNotBlank()) {
            if (Setup.failures in 1..6 && now - Setup.failedAt > 60_000L shl (Setup.failures - 1)) install(auto = true, why = "Trying the setup again")
            return
        }
        if (autoUpdate && toolsInstalled && now - prefs.getLong("toolsUpdated", now) > 24 * 60 * 60 * 1000L) install(auto = true, why = "Updating the tools")
        else if (autoUpdate && agentsInstalled && now - prefs.getLong("agentsUpdated", 0) > 6 * 60 * 60 * 1000L) updateAgents()
    }
    private var updatingAgents by SharedTools::updating
    /**
     * The AI helpers to their latest versions, quietly, every few hours (Linux's full update is daily). Only
     * while nothing uses them; terminal widgets running one that changed restart on the new version.
     */
    private fun updateAgents() {
        if (updatingAgents || installing || busy || MakerRuntime.running || godotInstalling || AgentChat.running || Shells.active) return
        updatingAgents = true
        prefs.edit().putLong("agentsUpdated", System.currentTimeMillis()).apply()
        val log = { line: String -> runCatching { File(cache, "setup.log").apply { parentFile?.mkdirs(); if (length() > 2_000_000) renameTo(File(cache, "setup.previous.log")); appendText(line + "\n") } }; Unit }
        Setup.scope.launch {
            val changed = withContext(Dispatchers.IO) { runCatching { linux.updateAgents(log) }.getOrDefault(emptyList()) }
            updatingAgents = false
            agentsChanged(changed)
        }
    }
    private fun agentsChanged(changed: List<String>) {
        if (changed.isEmpty()) return
        Shells.agentsUpdated(changed)
        notice = "Updated " + changed.distinct().joinToString(", ") { agentName(it) } + " to the latest version."
        checkAgents()
    }
    private fun bundledEngine() = runCatching { getApplication<Application>().assets.open("maker/VERSION").bufferedReader().use { it.readText().trim() } }.getOrDefault("")
    /** Copies the engine this APK carries into the private Linux and marks it installed. */
    private fun copyEngine() {
        SafeFiles.space(getApplication(), 128L * 1024 * 1024)
        data.mkdirs()
        val versionKey = bundledEngine().replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val releases = File(data, ".releases").apply { mkdirs() }
        val release = File(releases, versionKey)
        if (!File(release, ".complete").isFile) {
            val staged = File(releases, "$versionKey.staging-${System.currentTimeMillis()}")
            try {
                copyAssets("maker", staged)
                check(File(staged, "bin/hatchlybox").length() > 0 && File(staged, "VERSION").readText().trim() == bundledEngine()) { "Bundled engine did not validate" }
                listOf("bin", "visual/bin").forEach { dir -> File(staged, dir).listFiles().orEmpty().forEach { it.setExecutable(true) } }
                File(staged, "template/gradlew").setExecutable(true)
                SafeFiles.write(File(staged, ".complete"), bundledEngine())
                check(staged.renameTo(release)) { "Could not commit staged engine" }
            } finally { if (staged.exists()) staged.deleteRecursively() }
        }
        val pointer = File(data, "bundle.next")
        java.nio.file.Files.deleteIfExists(pointer.toPath())
        java.nio.file.Files.createSymbolicLink(pointer.toPath(), java.nio.file.Paths.get(".releases/$versionKey"))
        java.nio.file.Files.move(pointer.toPath(), File(data, "bundle").toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        // Every immutable component follows the same atomic bundle pointer. Runtime settings stay outside.
        getApplication<Application>().assets.list("maker").orEmpty().forEach { name ->
            val link = File(data, name)
            if (!java.nio.file.Files.isSymbolicLink(link.toPath())) {
                if (link.exists()) check(link.renameTo(File(data, ".legacy-$name-${System.currentTimeMillis()}"))) { "Cannot preserve old engine component $name" }
                java.nio.file.Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get("bundle/$name"))
            }
        }
        linux.hostFile("/root/.local/bin").apply { mkdirs() }.let { local ->
            File(local, "visual").writeText("#!/bin/bash\nexec /root/.local/share/hatchlybox/visual/bin/visual \"\$@\"\n")
            File(local, "visual").setExecutable(true)
        }
        writeDevice()
        val version = bundledEngine()
        File(data, "standalone-version").writeText(version)
        // The engine's doctor wants its skills library marked with its own version.
        File(data, ".library").writeText(Regex("^(?:standalone|hatchlybox)-(\\d+)").find(version)?.groupValues?.get(1) ?: "")
    }
    /** What Android knows about this phone, for `visual device` (Linux inside can't see the model, screen or GPU). */
    private fun writeDevice() = runCatching {
        val app = getApplication<Application>()
        val m = app.resources.displayMetrics
        val display = (app.getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager).getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val gles = (app.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).deviceConfigurationInfo.glEsVersion
        val json = JSONObject()
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("soc", if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE)
            .put("gpu", "OpenGL ES $gles")
            .put("width", minOf(m.widthPixels, m.heightPixels)).put("height", maxOf(m.widthPixels, m.heightPixels))
            .put("dpi", m.densityDpi).put("hz", display?.refreshRate?.let { Math.round(it) } ?: 60)
            .put("android", Build.VERSION.SDK_INT)
        File(data, "device.json").writeText(json.toString())
    }
    /** After an update of this APK, its newer engine replaces the installed one (Linux and the SDK stay). */
    private fun syncEngine() {
        val installed = File(data, "standalone-version")
        if (prefs.getBoolean("enginePinned", false)) return
        if (!installed.exists() || installed.readText().trim() == bundledEngine()) return
        copyEngine()
        linux.run("hatchlybox list >/dev/null 2>&1")
    }
    /**
     * Every start: install everything on the first run (the basics first, the rest in the background);
     * otherwise check the tools are all there (repairing what's missing), update them once a day unless
     * automatic updates are off, and finish the Android SDK setup for this engine.
     * Never while a task runs: the next start checks again.
     */
    private suspend fun startupCheck() {
        // Nothing downloads before the welcome screen is accepted (it says what's downloaded, and asks for the SDK licence).
        if (!Legal.accepted(getApplication())) return
        if (Setup.startupDone || installing || MakerRuntime.running || busy) return
        Setup.startupDone = true
        if (!linux.hasBasics || !basics) { install(auto = true, why = "Setting up Hatchlybox"); return }
        if (!linux.isReady || !toolsInstalled) { install(auto = true, why = if (linux.hasAgents) "Installing the Android build tools" else "Installing the AI helpers"); return }
        checking = "Checking the tools…"
        val missing = withContext(Dispatchers.IO) {
            runCatching { linux.run("for t in bash git jq java claude codex opencode hatchlybox; do command -v \$t >/dev/null || echo \$t; done").second }.getOrDefault("linux")
        }.lines().map { it.trim() }.filter { it.isNotEmpty() }
        checking = ""
        val day = 24 * 60 * 60 * 1000L
        when {
            missing.isNotEmpty() -> { install(auto = true, why = "Installing what's missing: ${missing.joinToString(", ")}"); return }
            autoUpdate && System.currentTimeMillis() - prefs.getLong("toolsUpdated", 0) > day -> { install(auto = true, why = "Updating the tools"); return }
        }
        ensureSdk()
    }
    /** The engine's own setup (Android SDK, template): again after a failure or a newer engine. */
    private fun ensureSdk() {
        if (MakerRuntime.running || busy || installing || godotInstalling) return
        val engineVersion = if (prefs.getBoolean("enginePinned", false)) runCatching { File(data, "standalone-version").readText().trim() }.getOrDefault(bundledEngine()) else bundledEngine()
        val engine = Regex("^(?:standalone|hatchlybox)-(\\d+)").find(engineVersion)?.groupValues?.get(1)?.toIntOrNull()
        val done = setup.text("state") == "done" && (engine == null || setup.optInt("version") == engine)
        if (!done) action("Set up Android build tools", listOf("setup"))
    }
    private fun installedAt(pkg: String): Long? = runCatching {
        getApplication<Application>().packageManager.getPackageInfo(pkg, 0).lastUpdateTime
    }.getOrNull()
    /** The engine's status (apps.json .state), a running job on top, and what Android says is installed. */
    fun status(a: JSONObject): AppStatus {
        val slug = a.text("slug")
        if (busy && job.text("slug") == slug) return AppStatus("working", when (job.text("phase")) {
            "queued" -> "Starting"; "agent" -> "Writing the code"; "checking" -> "Checking the code"; "building" -> "Building the APK"
            "fixing" -> "Fixing a problem"; "interrupted" -> "Carrying on"; "installing" -> "Installing"; "art" -> "Drawing store pictures"; "uploading" -> "Sending to Google Play"
            else -> "Working"
        }, job.text("msg", "Working on it. You'll get a notification when it's done."), "accent", listOf("log", "stop"))
        val s = a.optJSONObject("state")
        val st = if (s != null) AppStatus(s.text("key"), s.text("word").replaceFirstChar { it.uppercase() }, s.text("detail").replaceFirstChar { it.uppercase() }, s.text("tone"), s.optJSONArray("actions").strings())
        else AppStatus(a.text("status", "new"), a.text("status", "new").replaceFirstChar { it.uppercase() }, "", "muted", if (a.has("apk")) listOf("install", "download") else listOf("retry", "log"))
        // Android knows whether it's installed, and when: an install from before the build is out of date.
        if (st.key !in setOf("ready", "downloaded", "update", "installed") || !a.has("apk")) return st
        val built = (a.optJSONObject("artifacts")?.optJSONObject("apk")?.optLong("at") ?: 0L) * 1000
        val at = installedAt(a.text("pkg"))
        return when {
            at == null -> AppStatus("ready", "Ready to install", "Built and ready to put on this phone.", "yellow", listOf("install", "download"))
            at >= built -> AppStatus("installed", "Installed", "A package is installed; exact build is unverified. Open it or install this build.", "green", listOf("open", "improve"))
            else -> AppStatus("update", "Update ready", "A newer version is built but not on the phone yet.", "yellow", listOf("install", "open"))
        }
    }
    private fun json(file: File, previous: JSONObject = JSONObject()) = EngineState.json(file, previous)
    private fun tail(file: File, limit: Int = 60000) = EngineState.tail(file, limit)
    private var stateObservers = listOf<EngineObserver>()
    private var stateUpdate: Job? = null
    private fun watchState() {
        if (stateObservers.isNotEmpty() || !linux.hasBasics || !cache.isDirectory) return
        stateObservers = listOf(cache, data).filter { it.isDirectory }.map { dir -> EngineObserver(dir) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                stateUpdate?.cancel(); stateUpdate = viewModelScope.launch { delay(250); refresh() }
            }
        }.also { it.startWatching() } }
    }
    override fun onCleared() { stateObservers.forEach { it.stopWatching() }; super.onCleared() }
    suspend fun refresh() {
        if (refreshing) return
        watchState()
        refreshing = true
        try {
            withContext(Dispatchers.IO) {
                val a = EngineState.array(File(cache, "apps.json"), apps)
                val cr = EngineState.array(File(cache, "creations.json"), creations)
                val j = json(File(cache, "job.json"), job); val s = json(File(cache, "setup.json"), setup)
                val conf = json(File(data, "settings.json")); val gh = json(File(cache, "github.json")); val pl = json(File(cache, "play.json"))
                val sk = runCatching { JSONArray(File(cache, "skills.json").readText()) }.getOrDefault(JSONArray()); val co = json(File(cache, "community.json")); val te = json(File(cache, "testing.json"))
                val l = tail(File(cache, "log.txt")); val sl = tail(File(cache, "setup.log")); val err = tail(File(cache, "build-err.txt"))
                val engine = File(data, "standalone-version").exists()
                val urlDir = linux.hostFile("/root/.cache/hatchlybox-links")
                val urls = urlDir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.mapNotNull {
                    val u = runCatching { it.readText().trim() }.getOrDefault(""); it.delete()
                    u.takeIf { value -> value.startsWith("https://") || value.startsWith("http://127.0.0.1:") }
                }
                withContext(Dispatchers.Main) {
                    apps = a; creations = cr; job = j; setup = s; settings = conf; github = gh; play = pl; skills = sk; community = co; testing = te
                    log = l; if (!installing) setupLog = sl; buildError = err; basics = engine && linux.hasBasics; toolsInstalled = engine && linux.isReady; agentsInstalled = engine && linux.hasAgents
                    if (urls.isNotEmpty()) links = (links + urls).distinct().takeLast(10)
                    if (agent.isEmpty()) agent = conf.text("agent", "opencode")
                }
            }
        } finally { refreshing = false }
    }
    /**
     * Installs or updates Linux, the agents and the engine, then runs the engine's setup (the SDK).
     * On the first start the basics come first, about a minute, so the app opens while the rest installs.
     */
    fun install(auto: Boolean = false, why: String = "Updating the tools") {
        if (installing || godotInstalling || MakerRuntime.running || busy || AgentChat.running || updatingAgents || Shells.active) { notice = "Finish active tasks and close terminal sessions before updating tools."; return }
        if (!auto) prefs.edit().putBoolean("enginePinned", false).apply()
        installing = true; failure = ""; checking = why
        if (!auto) page = "settings"
        Setup.keepAlive(getApplication())
        val log = { line: String -> runCatching { File(cache, "setup.log").apply { parentFile?.mkdirs(); if (length() > 2_000_000) renameTo(File(cache, "setup.previous.log")); appendText(line + "\n") } }; Setup.scope.launch { setupLog = (setupLog + "\n" + line).takeLast(50000) }; Unit }
        // In the process's scope, not this screen's: it carries on when the activity is replaced (launcher mode) or left.
        Setup.scope.launch {
            try {
                if (!linux.hasBasics || !basics) {
                    withContext(Dispatchers.IO) { linux.installBasics(log); prepareEngine() }
                    basics = true
                }
                firstSetup = !linux.isReady
                if (firstSetup) checking = if (linux.hasAgents) "Installing the Android build tools" else "Installing the AI helpers"
                val changed = ArrayList<String>()
                withContext(Dispatchers.IO) {
                    linux.install({ line -> log(line); Regex("^✓ updated (\\S+)").find(line)?.let { changed += it.groupValues[1] } }) {
                        // The AI helpers work from here on, while Java and adb install.
                        Setup.scope.launch { agentsInstalled = true; if (firstSetup) checking = "AI helpers ready · installing the Android build tools"; checkAgents() }
                    }
                    prepareEngine()
                }
                toolsInstalled = true
                prefs.edit().putLong("toolsUpdated", System.currentTimeMillis()).putLong("agentsUpdated", System.currentTimeMillis()).apply()
                agentsChanged(changed)
                installing = false; firstSetup = false; Setup.failures = 0
                refresh()
                // A first install, or an SDK that isn't set up for this engine: the engine's setup next.
                if (!auto || !ready || setup.optInt("version") == 0) action("Set up Android build tools", listOf("setup")) else ensureSdk()
            } catch (e: Exception) {
                Setup.failures++; Setup.failedAt = System.currentTimeMillis()
                failure = (e.message ?: "Setup failed") + (if (Setup.failures <= 6) ". Hatchlybox tries again by itself in a minute or two." else ". Tap Try again when you're online.")
            }
            finally { installing = false; firstSetup = false; checking = "" }
        }
    }
    /** The engine, its command and the browser hook inside the private Linux. */
    private fun prepareEngine() {
        copyEngine()
        val local = linux.hostFile("/root/.local/bin").apply { mkdirs() }
        File(local, "hatchlybox").writeText("#!/bin/bash\nexec /root/.local/share/hatchlybox/bin/hatchlybox \"\$@\"\n")
        File(local, "hatchlybox").setExecutable(true)
        File(local, "xdg-open").writeText("#!/bin/sh\nmkdir -p /root/.cache/hatchlybox-links\nprintf '%s' \"\$1\" > /root/.cache/hatchlybox-links/\"\$(date +%s)-\$\$\"\n")
        File(local, "xdg-open").setExecutable(true)
        // These defaults belong only to this APK's private Linux, never to the launcher.
        if (!File(data, "settings.json").exists()) File(data, "settings.json").writeText("{\"mode\":\"simple\",\"agent\":\"claude\",\"agentIsolation\":\"compatible\"}")
    }
    fun chooseAutoUpdate(on: Boolean) { autoUpdate = on; prefs.edit().putBoolean("autoUpdate", on).apply() }
    fun engineVersions(): List<File> = File(data, ".releases").listFiles().orEmpty().filter { File(it, ".complete").isFile }.sortedByDescending { it.lastModified() }
    fun selectEngine(release: File) {
        if (agentsBusy || installing || DeviceSync.serving) { notice = "Stop active tasks and sync before switching engines."; return }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                require(release.canonicalFile.parentFile == File(data, ".releases").canonicalFile && File(release, ".complete").isFile)
                val pointer = File(data, "bundle.next"); java.nio.file.Files.deleteIfExists(pointer.toPath())
                java.nio.file.Files.createSymbolicLink(pointer.toPath(), java.nio.file.Paths.get(".releases/${release.name}"))
                java.nio.file.Files.move(pointer.toPath(), File(data, "bundle").toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                val version = File(release, "VERSION").readText().trim()
                SafeFiles.write(File(data, "standalone-version"), version)
                SafeFiles.write(File(data, ".library"), Regex("^(?:standalone|hatchlybox)-(\\d+)").find(version)?.groupValues?.get(1).orEmpty())
            } }.onSuccess { prefs.edit().putBoolean("enginePinned", true).apply(); notice = "Engine selected and pinned: ${release.name}. Update tools installs the bundled engine again." }.onFailure { notice = it.message.orEmpty() }
        }
    }
    private fun copyAssets(path: String, target: File) {
        val assets = getApplication<Application>().assets
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) { target.parentFile?.mkdirs(); assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } } }
        else { target.mkdirs(); children.forEach { copyAssets("$path/$it", File(target, it)) } }
    }
    /** Quotes each argument; commands run only in this APK's private rootfs. */
    /** agentsOnly: it needs only the AI helpers (a version for another system), so it runs while the Android tools install. */
    fun action(title: String, args: List<String>, interactive: Boolean = false, agentsOnly: Boolean = false) {
        if (if (agentsOnly) agentsBusy else agentsBusy || installing || godotInstalling) { notice = "Finish the current task first."; return }
        if (!(if (agentsOnly) agentsInstalled else toolsInstalled)) { stillInstalling(title); return }
        val command = args.joinToString(" ", transform = ::quote)
        val monitor = if (args.firstOrNull() in setOf("new", "change", "retry", "again", "build", "create", "make", "wrap", "native") || args.take(2) in listOf(listOf("play", "prepare"), listOf("play", "upload"))) """
            rc=${'$'}?
            [ ${'$'}rc -eq 0 ] || exit ${'$'}rc
            # Until it's done. A worker that stopped (dead, or "interrupted") goes to the health check, which
            # carries it on as a new job (watched here too) or marks it failed.
            expected=${'$'}(jq -er '.id' /root/.cache/hatchlybox/job.json) || exit 1
            # What the agent and the build are doing, as they do it: log.txt always points at the current job's log.
            tail -n 40 -F /root/.cache/hatchlybox/log.txt 2>/dev/null & tailer=${'$'}!
            trap 'kill ${'$'}tailer 2>/dev/null' EXIT
            while jq -e '.phase | IN("queued","agent","checking","building","fixing","art","installing","uploading","interrupted")' /root/.cache/hatchlybox/job.json >/dev/null 2>&1; do
                [ "${'$'}(jq -r '.id' /root/.cache/hatchlybox/job.json)" = "${'$'}expected" ] || { echo "Task identity changed; check Tasks."; exit 1; }
                pid=${'$'}(jq -r '.pid // 0' /root/.cache/hatchlybox/job.json)
                if [ "${'$'}(jq -r .phase /root/.cache/hatchlybox/job.json)" = interrupted ] || { [ "${'$'}pid" -gt 0 ] && ! kill -0 "${'$'}pid" 2>/dev/null; }; then hatchlybox health >/dev/null; fi
                sleep 2
            done
            sleep 1   # the log's last lines
            phase=${'$'}(jq -r '.phase' /root/.cache/hatchlybox/job.json)
            [ "${'$'}(jq -r '.id' /root/.cache/hatchlybox/job.json)" = "${'$'}expected" ] || exit 1
            case "${'$'}phase" in ready|done|installed|accepted|live|testers) exit 0 ;; *) echo "Task needs review: ${'$'}phase"; exit 1 ;; esac
        """.trimIndent() else ""
        val keepDownloads = if (args.take(2) == listOf("play", "backup")) "\nrc=\$?; [ \$rc -eq 0 ] && sleep 125; exit \$rc" else ""
        // Google's SDK licence, accepted on the welcome screen: the engine records it before its setup downloads the SDK.
        val license = if (args.firstOrNull() == "setup" && Legal.accepted(getApplication())) "hatchlybox license accept android-sdk >/dev/null 2>&1\n" else ""
        MakerRuntime.start(getApplication(), title, "${license}hatchlybox $command\n$monitor$keepDownloads", request = if (args.firstOrNull() in setOf("new", "change", "create", "make")) args.lastOrNull() else null)
        showConsole = interactive
        notice = "$title started"
    }
    val deletedSlug get() = prefs.getString("deletedSlug", "") ?: ""
    /** The deleted project's folder name in the trash: its name plus when, so deleting the same name twice never clashes. */
    private val deletedTrash get() = prefs.getString("deletedTrash", "")?.ifBlank { null } ?: deletedSlug
    fun deleteApp(a: JSONObject) {
        if (MakerRuntime.running || busy || installing || godotInstalling) { notice = "Finish the current task first."; return }
        val slug = a.text("slug")
        if (!slug.matches(Regex("[a-zA-Z0-9_-]+"))) { notice = "Invalid app name"; return }
        val name = "$slug-${System.currentTimeMillis() / 1000}"
        val trash = "/root/.local/share/hatchlybox-trash"
        prefs.edit().putString("deletedSlug", slug).putString("deletedTrash", name).commit()
        // Every step says what went wrong in words. A project that's already gone (a creation stopped before its
        // folder was made) still leaves the list; a busy app list refreshes on its own later.
        MakerRuntime.start(getApplication(), "Move $slug to trash", """
            mkdir -p ${quote(trash)} || { echo "couldn't make the trash folder"; exit 1; }
            if [ -e ${quote("/root/apps/$slug")} ]; then
              mv ${quote("/root/apps/$slug")} ${quote("$trash/$name")} || { echo "couldn't move $slug to the trash"; exit 1; }
            else echo "$slug's folder was already gone"; fi
            job=/root/.cache/hatchlybox/job.json
            if [ "${'$'}(jq -r '.slug // empty' ${'$'}job 2>/dev/null)" = ${quote(slug)} ] && ! jq -e '.phase | IN("queued","agent","checking","building","fixing","art","installing","uploading")' ${'$'}job >/dev/null 2>&1; then rm -f ${'$'}job; fi
            hatchlybox list >/dev/null || echo "the list of apps refreshes next time"
            echo "moved $slug to the trash"
        """.trimIndent())
        page = "apps"; notice = "Project moved to trash when the task finishes. Undo is in Settings → Recently deleted."
    }
    fun undoDelete() {
        if (MakerRuntime.running || busy || installing || godotInstalling) return
        val slug = deletedSlug
        val name = deletedTrash
        if (!slug.matches(Regex("[a-zA-Z0-9_-]+")) || !name.matches(Regex("[a-zA-Z0-9_-]+"))) return
        MakerRuntime.start(getApplication(), "Restore $slug", """
            [ -e ${quote("/root/.local/share/hatchlybox-trash/$name")} ] || { echo "$slug isn't in the trash any more"; exit 1; }
            [ ! -e ${quote("/root/apps/$slug")} ] || { echo "there's already an app called $slug"; exit 1; }
            mv ${quote("/root/.local/share/hatchlybox-trash/$name")} ${quote("/root/apps/$slug")} || { echo "couldn't move $slug back"; exit 1; }
            hatchlybox list >/dev/null || true; echo "restored $slug"
        """.trimIndent())
    }
    fun diff(a: JSONObject) {
        if (MakerRuntime.running || busy) return
        val base = a.optJSONObject("pending")?.text("base") ?: return
        MakerRuntime.start(getApplication(), "Review code changes", "git -C ${quote("/root/apps/" + a.text("slug"))} diff --no-ext-diff ${quote(base)} -- . ':!gradle/wrapper/gradle-wrapper.jar'")
        showConsole = true
    }
    private var stopping = false
    fun cancel() {
        if (stopping) return
        stopping = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { linux.run("hatchlybox cancel") }.getOrNull() }
            notice = result?.second?.ifBlank { "Stop requested" } ?: "Couldn't stop this task"
            refresh()
            stopping = false
        }
    }
    fun logout() {
        logout(agent)
    }
    /** Signs one agent out; the check after it ends shows the new state (MakerScreen: "Sign out " task). */
    fun logout(a: String) {
        if (MakerRuntime.running) { notice = "Finish the current task first."; return }
        val ag = runCatching { Agent.valueOf(a.uppercase()) }.getOrDefault(Agent.CLAUDE)
        MakerRuntime.start(getApplication(), "Sign out $a", ag.logoutCommand)
        if (ag.interactiveLogout) showConsole = true
    }
    fun backups(): List<File> = linux.hostFile("/root/Backups/hatchlybox").listFiles().orEmpty().filter { it.extension == "enc" }.sortedByDescending { it.lastModified() }
    fun shareBackup(file: File) {
        val uri = FileProvider.getUriForFile(getApplication(), "com.hatchlybox.app.files", file)
        launch(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share encrypted backup").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    fun importBackup(uri: Uri) {
        if (MakerRuntime.running || busy || installing || godotInstalling) return
        viewModelScope.launch {
            try {
                val id = System.currentTimeMillis().toString(36)
                val path = "/root/.cache/hatchlybox/import-$id.enc"
                withContext(Dispatchers.IO) {
                    val target = linux.hostFile(path).apply { parentFile!!.mkdirs() }
                    SafeFiles.space(getApplication(), 256L * 1024 * 1024)
                    try {
                        getApplication<Application>().contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { output ->
                            val buf = ByteArray(65536); var copied = 0L
                            while (true) { val n = input.read(buf); if (n < 0) break; copied += n; require(copied <= 2L * 1024 * 1024 * 1024 && target.usableSpace > 128L * 1024 * 1024) { "Backup too large or storage is full" }; output.write(buf, 0, n) }
                        } }
                    } catch (e: Exception) { target.delete(); throw e }
                }
                MakerRuntime.start(getApplication(), "Restore project as restored-$id", "hatchlybox restore ${quote("restored-$id")} ${quote(path)}; rc=\$?; rm -f ${quote(path)}; exit \$rc")
                showConsole = true
            } catch (e: Exception) { notice = e.message ?: "Couldn't read backup" }
        }
    }
    fun saveNotes(a: JSONObject, text: String) {
        if (MakerRuntime.running || busy || installing) { notice = "Finish the current task first."; return }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { file(a, "play/release-notes.txt").apply { parentFile?.mkdirs(); writeText(text) } } }
            catch (e: Exception) { notice = e.message ?: "Could not save release notes"; return@launch }
            action("Save release notes", listOf("play", "set", a.text("slug"), "notes"))
        }
    }
    fun create(idea: String): Boolean {
        if (idea.isBlank()) return false
        if (pendingIdea.isNotBlank()) { notice = "An app is already queued. Open Tasks to edit or cancel it."; return false }
        if (!ready) {
            pendingIdea = idea.trim(); prefs.edit().putString("pendingIdea", pendingIdea).putString("pendingAgent", agent).commit()
            notice = "Queued until the Android tools are ready. You can cancel it in Tasks."
            return true
        }
        if (agentsBusy || installing) { notice = "Another task is using the tools. Your draft is kept."; return false }
        selected = ""; action("Build a new app", listOf("new", agent, CreationPolicy.request(idea.trim(), "app")))
        if (MakerRuntime.running) page = "job"
        return MakerRuntime.running
    }

    /**
     * Once per start, when the build tools are ready: install or repair Godot in its own Linux process
     * (the engine's setup starts one too, but proot stops it when setup's session ends).
     */
    private fun ensureGodot() {
        if (!prefs.getBoolean("gameToolsRequested", false)) return
        if (pendingIdea.isNotBlank() || Setup.godotChecked || godotInstalling || !ready || installing || busy || MakerRuntime.running) return
        Setup.godotChecked = true
        val done = File(data, "godot").listFiles().orEmpty().any { it.name.startsWith(".ready-") }
        Setup.scope.launch {
            godotInstalling = !done
            if (!done) Setup.keepAlive(getApplication())
            try {
                val result = withContext(Dispatchers.IO) { linux.run("hatchlybox godot >> /root/.cache/hatchlybox/godot.log 2>&1", 1_800_000) }
                if (result.first != 0) notice = "Godot setup did not finish. Update tools in Settings to retry."
            } catch (e: Exception) { notice = e.message ?: "Godot setup failed" }
            finally { godotInstalling = false }
        }
    }
    fun dropPending() { pendingIdea = ""; prefs.edit().remove("pendingIdea").remove("pendingAgent").commit() }
    /** Builds the idea saved during setup once nothing else is running. */
    private fun buildPending() {
        if (pendingIdea.isBlank() || !ready || busy || installing || godotInstalling || MakerRuntime.running) return
        val idea = pendingIdea
        action("Build a new app", listOf("new", prefs.getString("pendingAgent", agent) ?: agent, CreationPolicy.request(idea, "app")))
        if (MakerRuntime.running) { dropPending(); selected = ""; page = "job" }
    }
    fun openApp(a: JSONObject, play: Boolean = false) { selected = a.text("slug"); openPlay = play; page = "app" }
    /** The app's icon: the one the engine drew for the list, else the installed app's own. */
    fun icon(a: JSONObject): android.graphics.Bitmap? {
        a.text("icon").takeIf { it.isNotBlank() }?.let { path ->
            linux.hostFile(path).takeIf { it.isFile }?.let { f -> android.graphics.BitmapFactory.decodeFile(f.path)?.let { return it } }
        }
        return runCatching { getApplication<Application>().packageManager.getApplicationIcon(a.text("pkg")).let { d ->
            val b = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 96, 96); d.draw(android.graphics.Canvas(b)); b
        } }.getOrNull()
    }
    fun chooseAgent(value: String) { agent = value; prefs.edit().putString("agent", value).apply() }

    // Permissions the user can switch off: notifications (build alerts, the background task) and installing
    // apps (the APKs it builds). Checked at start and whenever Hatchlybox comes back to the front.
    var notificationsOn by mutableStateOf(true)
    var installsOn by mutableStateOf(true)
    val missingPermissions get() = listOfNotNull("notifications".takeIf { !notificationsOn }, "installs".takeIf { !installsOn })
    /** The home notice was closed for exactly this set of missing permissions; a new gap shows it again. */
    var permissionsDismissed by mutableStateOf(prefs.getString("permissionsDismissed", null))
    fun dismissPermissions() { permissionsDismissed = missingPermissions.joinToString(","); prefs.edit().putString("permissionsDismissed", permissionsDismissed).apply() }
    /** Notifications were asked for once already: Android won't ask again, so Allow opens the settings. */
    var notificationsAsked by mutableStateOf(prefs.getBoolean("notificationsAsked", false))
    fun markNotificationsAsked() { notificationsAsked = true; prefs.edit().putBoolean("notificationsAsked", true).apply() }
    fun checkPermissions() {
        val context = getApplication<Application>()
        notificationsOn = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        installsOn = context.packageManager.canRequestPackageInstalls()
    }

    /** Shared by Settings and every Create box. Auto defaults to the Android host. */
    var createMode by mutableStateOf(CreateModes.firstOrNull { it.key == prefs.getString("createMode2", "auto") }?.key ?: "auto")
    fun chooseCreateMode(value: String) {
        if (CreateModes.none { it.key == value }) return
        createMode = value
        prefs.edit().putString("createMode2", value).remove("createType").apply()
    }

    // ---------- the create box: what it offers (Settings → Create box) ----------

    /** Kinds hidden from the box's picker (Auto always stays: it's where everything else goes). */
    val boxModesOff = mutableStateListOf<String>().apply { addAll(prefs.getStringSet("boxModesOff", emptySet()).orEmpty().filter { it != "auto" }) }
    fun boxMode(key: String, on: Boolean) {
        if (key == "auto") return
        if (on) boxModesOff.remove(key) else if (key !in boxModesOff) boxModesOff.add(key)
        prefs.edit().putStringSet("boxModesOff", boxModesOff.toSet()).apply()
        if (!on && createMode == key) chooseCreateMode("auto")
    }
    /**
     * Its features, all on unless switched off: "actions" (phone actions from the local model, Enter runs them),
     * "quick" (suggestions while typing: apps, people, timers, settings), "ghost" (the rest of the best one,
     * faded), "examples" (example ideas while it's empty), "agent" (the agent picker above it), "gold" (the gold thread).
     */
    private val boxOptions = mutableStateMapOf<String, Boolean>().apply {
        listOf("actions", "quick", "ghost", "examples", "agent", "gold").forEach { k -> put(k, prefs.getBoolean("box.$k", true)) }
        Gold.enabled = this["gold"] != false
    }
    fun boxOn(key: String) = boxOptions[key] != false
    fun setBox(key: String, on: Boolean) {
        boxOptions[key] = on; prefs.edit().putBoolean("box.$key", on).apply()
        if (key == "gold") Gold.enabled = on
    }

    /** Auto is working out what a request is (a quick question to the agent). */
    var deciding by mutableStateOf(false)

    /** A request Auto couldn't place: the box offers the choices (do it on the phone, make something, chat) until one is picked. */
    var unsure by mutableStateOf<String?>(null)

    /**
     * Auto: what to make of a request, as a create mode, or "do" (use the phone's apps) or "ask" (the user picks).
     * Clear words decide at once ("make a widget…", "open WhatsApp and…", a question); anything else is reasoned
     * about by the agent, which answers "unsure" when it could go either way. Without an agent, only a request
     * that says to make something becomes an app; the rest is asked.
     */
    fun decide(idea: String, then: (String) -> Unit) {
        // Ambiguous intent is a user choice, never an expensive speculative agent run.
        then(guessMode(idea) ?: "ask")
    }
    fun cancelDecision() { deciding = false; unsure = null }

    /**
     * A request to make something it names in so many words (a widget, an app, a game, a website…): never a phone
     * action, whatever words it has ("create a widget of a Disney style cat" isn't asking to change the theme).
     */
    fun namesCreation(idea: String): Boolean = guessMode(idea).let { it != null && it != "chat" && it != "do" }

    /** Says to make something new: a making word with a thing to make soon after it ("build me a small timer app"). */
    private fun guessMode(idea: String): String? = IntentRouter.classify(idea)

    /** The phone's own apps on the home page, like a launcher. */
    var showPhoneApps by mutableStateOf(prefs.getBoolean("phoneApps", true))
    fun choosePhoneApps(on: Boolean) { showPhoneApps = on; prefs.edit().putBoolean("phoneApps", on).apply(); if (on) loadPhoneApps() }
    val phoneApps = mutableStateListOf<PhoneApp>()
    private val phoneIcons = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap?>()
    private var loadingPhoneApps = false

    /** Every launchable app on the phone (the manifest's launcher query), by name. Icons are loaded once each. */
    fun loadPhoneApps() {
        if (loadingPhoneApps || !showPhoneApps) return
        loadingPhoneApps = true
        val context = getApplication<Application>()
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    val pm = context.packageManager
                    pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                        .filter { it.activityInfo.packageName != context.packageName }
                        .map { r ->
                            val key = r.activityInfo.packageName + "/" + r.activityInfo.name
                            val icon = phoneIcons.getOrPut(key) {
                                runCatching { r.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull()
                            }
                            PhoneApp(r.loadLabel(pm).toString(), r.activityInfo.packageName, r.activityInfo.name, icon,
                                r.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0)
                        }
                        .distinctBy { it.pkg + "/" + it.activity }
                        .sortedBy { it.label.lowercase() }
                }.getOrNull()
            }
            loadingPhoneApps = false
            if (found != null && found.map { it.pkg + "/" + it.activity + it.label } != phoneApps.map { it.pkg + "/" + it.activity + it.label }) {
                phoneApps.clear(); phoneApps.addAll(found)
            }
        }
    }

    fun launch(app: PhoneApp) {
        val context = getApplication<Application>()
        runCatching {
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(app.pkg, app.activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }.onFailure { notice = "Couldn't open ${app.label}. It may have been removed."; loadPhoneApps() }
    }
    /** The clock widget's tap: the phone's alarms. */
    fun openClock() = launch(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS))
    fun appInfo(pkg: String) =launch(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    /** Android's own uninstall dialog for it; the library reloads the phone's apps when Hatchlybox comes back. */
    fun uninstall(pkg: String) = launch(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
    /** It's on this phone (whatever version). */
    fun isInstalled(a: JSONObject) = a.text("pkg").isNotBlank() && installedAt(a.text("pkg")) != null

    /** A library app as an icon on the home screen, in the first free place on this page (else a new one); one is enough. */
    fun addToHome(kind: String, ref: String, name: String) {
        library = false
        launcher.pages.indexOfFirst { list -> list.any { it.kind == kind && it.ref == ref } }.takeIf { it >= 0 }?.let { at ->
            homePage = at; notice = "$name is already on your home screen."; return
        }
        val pages = launcher.pages.size
        homePage = launcher.add(homePage.coerceIn(0, (pages - 1).coerceAtLeast(0)), kind, ref)
        notice = "$name is on your home screen" + if (launcher.pages.size > pages) ", on a new page: the other was full." else "."
    }

    // ---------- launcher mode: Hatchlybox as the phone's home screen ----------

    private val homeAlias get() = ComponentName(getApplication<Application>(), "com.hatchlybox.app.HomeAlias")
    /** The home-screen entry is switched on (the phone may still use another launcher until it's chosen). */
    var launcherMode by mutableStateOf(runCatching { getApplication<Application>().packageManager.getComponentEnabledSetting(homeAlias) == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED }.getOrDefault(false))
    /** Hatchlybox is the phone's home screen now. Checked on every resume. */
    var isHome by mutableStateOf(false)
    init { checkHome() }
    fun checkHome() {
        val context = getApplication<Application>()
        isHome = runCatching { context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == context.packageName }.getOrDefault(false)
    }
    /** Switches the home-screen entry on or off; false when Android refused. */
    fun enableHomeAlias(on: Boolean): Boolean {
        runCatching { getApplication<Application>().packageManager.setComponentEnabledSetting(homeAlias,
            if (on) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP) }.onFailure { notice = "Couldn't change the home screen setting: ${it.message}"; return false }
        launcherMode = on
        return true
    }
    /** Off: the home entry goes and the phone's settings open, to pick the other launcher. (On goes through MakerScreen's role request.) */
    fun setLauncher(on: Boolean) {
        if (!enableHomeAlias(on)) return
        if (on || isHome) chooseHome()
    }
    /** The first start asks once whether Hatchlybox should be the home screen. */
    var launcherAsked by mutableStateOf(prefs.getBoolean("launcherAsked", false))
    fun markLauncherAsked() { launcherAsked = true; prefs.edit().putBoolean("launcherAsked", true).apply() }
    /** The home screen's pages and where each widget sits on them. */
    val launcher = LauncherLayout(app.getSharedPreferences("launcher", 0), linux)
    /** The phone's own "Default home app" screen, where Hatchlybox is picked (or another launcher put back). */
    fun chooseHome() {
        val context = getApplication<Application>()
        val home = Intent(android.provider.Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(home) }.onFailure { launch(Intent(android.provider.Settings.ACTION_SETTINGS)) }
    }
    /** The home screen's page shown now (0 = the first). */
    var homePage by mutableIntStateOf(0)
    /** The Home button: back to the main screen from anywhere. */
    fun goHome() { showConsole = false; library = false; page = "apps"; homePage = 0 }

    // ---------- widgets, made and changed by the agents ----------

    val widgetEngine = WidgetEngine(app, linux)
    /** An agent makes a new widget (id null) or changes one, in /root/widgets/<id>, following WIDGETS.md. */
    /** Returns the widget's folder name when the agent started, else null (and says why). */
    fun makeWidget(description: String, id: String? = null): String? {
        if (description.isBlank()) return null
        if (!agentsInstalled) { stillInstalling("Widgets can be made"); return null }
        if (agentsBusy) { busyNotice(); return null }
        if (!agentOk(agent)) { connect(agent) { makeWidget(description, id) }; return null }
        runCatching { widgetEngine.install(); Look.installDocs(getApplication()); Look.writeThemeFile(getApplication()) }
        val target = id ?: widgetEngine.newId(description)
        if (id != null) widgetEngine.adopt(id)
        val dir = "$WIDGETS_DIR/$target"
        val prompt = (if (id == null) "Make a home-screen widget for Hatchlybox: ${description.trim()}\n\nYou are in its folder, $dir (empty). "
            else "Change this home-screen widget ($dir): ${description.trim()}\n\n") +
            "First read $WIDGETS_DIR/WIDGETS.md: it is the widget format, the only thing the home screen can draw: a drawn widget (widget.json with a ui tree, an optional data command for live data, actions, " +
            "and pictures that move: a \"canvas\" of shapes with \"anim\", see Pictures and motion) " +
            "or a terminal widget (widget.json with \"terminal\": {\"command\": ...}) that runs an interactive program such as opencode, claude, codex or htop full screen. " +
            "Pick the kind with the table in WIDGETS.md: a terminal widget only when the user wants to type into a program or names one (every tap on it opens the keyboard); " +
            "a pet cat is the `pet` node (\"Pets\" in WIDGETS.md, the Wifoz example in $WIDGETS_DIR/examples/wifoz); any other animal, a character, a scene, an animation or a toy is a drawn widget with a canvas that moves and reacts to taps (read $WIDGETS_DIR/examples/detective-cat first and borrow from it), never a terminal or a still image, and check it with `widget check $target motion`. " +
            "Start from the nearest working example: `widget examples` lists them and $WIDGETS_DIR/examples holds their files. " +
            "Write widget.json in this folder" + (if (id == null) "" else " (read the current one first and keep what the user didn't ask to change)") + ", plus a data script if it needs live data (test it by running it). " +
            "Make it minimal, calm and beautiful: one idea, glanceable, the smallest size that fits, and a layout that still works when the user resizes it. " +
            "When there's more worth seeing than the tile holds (a forecast, a history, a whole list, the details behind a number), give it a \"screen\": a full-screen page a tap opens (see Screens in WIDGETS.md), and check it with `widget check $target screen`. " +
            "Match the launcher's look: read $WIDGETS_DIR/THEME.json and use its named colours (they follow the theme, light and dark). No API keys or personal details in the files. " +
            "Then run `widget check $target`: it checks the file, runs the data script and draws the widget into $dir/.check.png with the live data at its real size. " +
            "Look at that picture (it's small) and fix what looks wrong, and fix every line starting with ! until it says OK. Also check one other size, e.g. `widget check $target small`. " +
            "$WIDGETS_DIR/errors.txt says what the home screen ignored in each widget. The home screen shows every save within two seconds. " +
            "When it checks OK, your very last step is `widget done $target \"<what you made or changed, in one short line>\"`: Hatchlybox then ends this task, so do nothing after it."
        val ag = runCatching { Agent.valueOf(agent.uppercase()) }.getOrDefault(Agent.CLAUDE)
        MakerRuntime.start(getApplication(), if (id == null) "Make a widget" else "Change ${widgetEngine.widgets.firstOrNull { it.id == id }?.name ?: id}",
            "mkdir -p ${quote(dir)} && cd ${quote(dir)} && export PROMPT=${quote(prompt)} && ${ag.runCommand(false)}", doneMark = linux.hostFile("$dir/.done"), request = description)
        notice = "${agentName(agent)} is " + (if (id == null) "making your widget" else "changing the widget") + ". It shows here when it's ready."
        return if (MakerRuntime.running) target else null
    }
    /**
     * The built-in widgets (clock, terminal, apps) are drawn by the app; an agent changes their look through
     * /root/widgets/builtin-<placed id>/style.json, within the keys the app understands.
     */
    fun styleWidget(kind: String, placedId: String, description: String) {
        if (description.isBlank()) return
        if (!agentsInstalled) { stillInstalling("Widgets can be changed"); return }
        if (agentsBusy) { busyNotice(); return }
        if (!agentOk(agent)) { connect(agent) { styleWidget(kind, placedId, description) }; return }
        runCatching { widgetEngine.install(); Look.installDocs(getApplication()); Look.writeThemeFile(getApplication()) }
        val dir = "$WIDGETS_DIR/builtin-$placedId"
        val extra = when (kind) {
            "clock" -> "format (a Java date pattern for the time, e.g. \"HH:mm\" or \"h:mm a\"), date (true/false), dateFormat (e.g. \"EEEE d MMMM\"), caps (true/false: the date in capitals)"
            "terminal" -> "fontSize (sp, 7 to 18), prompt (a colour for the › and the cursor), command (a program to run full screen instead of bash, e.g. \"opencode\", \"claude --continue || claude\" or \"htop\"; " +
                "check it exists with `command -v`; leave it out for a plain bash shell), title (the small label at the top)"
            else -> "label (true/false: the centred app's name), iconSize (dp, 36 to 96)"
        }
        val prompt = "Restyle the home screen's built-in ${kind} widget in Hatchlybox: ${description.trim()}\n\n" +
            "The app draws this widget itself; you change only its look" + (if (kind == "terminal") " and the program it runs" else "") + ", in $dir/style.json (you are in that folder; keep what still fits if the file exists). " +
            "Use only these keys: background (a colour, or \"none\"), text (colour), accent (colour), corner (dp), opacity (0 to 1, the background's), " +
            "font (\"thin\", \"light\", \"regular\", \"medium\", \"bold\", \"mono\" or \"serif\"), scale (text size, 0.6 to 1.6), align (\"start\", \"center\" or \"end\"), padding (dp), and for this widget: $extra. " +
            "Colours are theme names (primary, secondary, tertiary, text, muted, surface, container, card, outline) or #hex. Match the launcher's look: read $WIDGETS_DIR/THEME.json and prefer its named colours. " +
            "Keep it minimal and calm. Finish by checking the file with `jq . style.json`."
        val ag = runCatching { Agent.valueOf(agent.uppercase()) }.getOrDefault(Agent.CLAUDE)
        MakerRuntime.start(getApplication(), "Restyle the $kind", "mkdir -p ${quote(dir)} && cd ${quote(dir)} && export PROMPT=${quote(prompt)} && ${ag.runCommand(false)}")
        notice = "${agentName(agent)} is restyling the $kind. It changes on the home screen when it's done."
    }

    /**
     * An agent makes a launcher theme (/root/themes/<id>/theme.json, THEMES.md), or changes the one in use:
     * an agent's own theme in place, a built-in style as a copy. It's chosen by itself when saved (Look.pending).
     */
    fun makeTheme(description: String, change: Boolean): Boolean {
        if (description.isBlank()) return false
        if (!agentsInstalled) { stillInstalling("Themes can be made"); return false }
        if (agentsBusy) { busyNotice(); return false }
        if (!agentOk(agent)) { connect(agent) { makeTheme(description, change) }; return false }
        val app = getApplication<Application>()
        Look.installDocs(app); Look.writeThemeFile(app)
        val mine = Look.currentCustom?.id?.takeIf { change }
        val id = mine ?: run {
            val base = description.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').split('-').filter { it.length > 2 }.take(3).joinToString("-").ifBlank { "theme" }
            var t = base; var n = 2
            while (linux.hostFile("$THEMES_DIR/$t").exists()) t = "$base-${n++}"
            t
        }
        val dir = "$THEMES_DIR/$id"
        val file = linux.hostFile("$dir/theme.json")
        // Like moving an example up in the launcher: the agent starts from a working file, never a blank one.
        // A change to a built-in look starts from a copy of it; a new theme from the current look, renamed.
        if (mine == null) runCatching {
            file.parentFile?.mkdirs()
            file.writeText(Look.describe().put("name", if (change) Look.current.name + " (mine)" else description.trim().take(40)).toString(2))
        }
        val prompt = (if (change) "Change the Hatchlybox launcher theme in $dir/theme.json: ${description.trim()}\n\nKeep what still fits. "
            else "Make a new launcher theme for Hatchlybox: ${description.trim()}\n\nYou are in its folder, $dir. theme.json here is a copy of the current look to start from: rewrite it into the new theme, with a fitting name. ") +
            "Read $THEMES_DIR/THEMES.md first: the format, every key with its range, and what each style, motion and clock looks like. " +
            "`theme defaults`, `theme styles` and `theme palettes` list what's built in; `theme palette <id>` gives a palette's colours to borrow. " +
            "Make it one coherent idea: colours with good contrast in light and dark, including the seven tones (red, orange, yellow, green, cyan, blue, magenta) tuned to the mood, and motion that suits it. " +
            "The launcher shows theme.json on the phone within two seconds of every save, so save as you go. " +
            "Then run `theme check` in this folder and fix everything it reports until it prints OK; /root/themes/errors.txt is what the launcher itself ignored. " +
            "No personal details in the files."
        Look.pending = id to (if (file.exists()) file.lastModified() else 0L)
        themeTask = true; themeId = id
        val ag = runCatching { Agent.valueOf(agent.uppercase()) }.getOrDefault(Agent.CLAUDE)
        MakerRuntime.start(app, if (change) "Change the theme" else "Make a theme", "mkdir -p ${quote(dir)} && cd ${quote(dir)} && export PROMPT=${quote(prompt)} && ${ag.runCommand(false)}")
        notice = "${agentName(agent)} is " + (if (change) "changing the theme" else "making your theme") + ". Each save shows on the home screen at once."
        return true
    }

    /** A theme task is running (makeTheme), and the theme it works on. */
    var themeTask by mutableStateOf(false)
    private var themeId = ""

    /** The theme task ended: load the result once more and say how it went, in words. */
    fun themeEnded(code: Int?) {
        themeTask = false
        val id = themeId
        viewModelScope.launch {
            val app = getApplication<Application>()
            Look.reloadCustom(app)
            Look.pending = null
            val t = Look.custom.firstOrNull { it.id == id }
            notice = when {
                Look.broken.containsKey(id) -> "The theme file has a mistake, so it isn't shown: ${Look.broken[id]}. Ask again to fix it."
                t == null -> if (code == 0) "${agentName(agent)} didn't save a theme. Try describing it again." else "The theme task stopped (code $code). The task console has the details."
                else -> {
                    if (Look.style != "custom:$id") Look.choose(app, t)
                    if (t.problems.isEmpty()) "${t.name} is on. Long-press it under Themes to change or delete it."
                    else "${t.name} is on; the launcher ignored ${t.problems.size} part(s): ${t.problems.first()}"
                }
            }
        }
    }

    /** A new creation, first made for another system (web, linux, windows, omarchy, other), by the engine. */
    fun createFor(target: String, idea: String): Boolean {
        if (idea.isBlank()) return false
        if (agentsBusy) { notice = "The tools are in use. Your draft is kept."; return false }
        if (!agentsInstalled) { stillInstalling("Apps for ${portName(target)} can be made"); return false }
        if (!agentOk(agent)) { connect(agent); return false }
        action(if (target == "web") "Make a web app" else "Make a ${portName(target)} app", listOf("create", target, agent, CreationPolicy.request(idea.trim(), target)), agentsOnly = true)
        if (MakerRuntime.running) { selected = ""; page = "job" }
        return MakerRuntime.running
    }

    /**
     * The creation for a system: not made yet, made from its newest version (Android: built and installed
     * too); made already, changed (request) or brought up to date. "custom:<words>" names a system.
     */
    fun makeFor(slug: String, system: String, request: String = "") {
        val android = system == "android"
        if (android && !ready) { stillInstalling("The Android version can be made"); return }
        if (!android && !agentsInstalled) { stillInstalling("Versions for other systems can be made"); return }
        if (!agentOk(agent)) { connect(agent) { makeFor(slug, system, request) }; return }
        val label = creations.firstOrNull { it.text("slug") == slug }?.text("label", slug) ?: slug
        val name = if (system.startsWith("custom:")) system.removePrefix("custom:") else portName(system)
        action("${if (request.isBlank()) "Make" else "Change"} $label for $name", listOf("make", slug, system, agent) + listOfNotNull(request.trim().takeIf { it.isNotBlank() }), agentsOnly = !android)
        if (MakerRuntime.running) { selected = slug; page = "job" }
    }

    /** Its web version as an app on this phone: a fixed Android shell around it, made and installed with no agent. */
    fun wrapWeb(slug: String) {
        if (!ready) { stillInstalling("Putting it on the phone"); return }
        val label = creations.firstOrNull { it.text("slug") == slug }?.text("label", slug) ?: slug
        action("Put $label on this phone", listOf("wrap", slug))
        if (MakerRuntime.running) { selected = slug; page = "job" }
    }

    /** The wrapped app made a full native app (widgets, background work, deeper phone features) by the agent. */
    fun makeNative(slug: String) {
        if (!ready) { stillInstalling("The native app can be made"); return }
        if (!agentOk(agent)) { connect(agent) { makeNative(slug) }; return }
        val label = creations.firstOrNull { it.text("slug") == slug }?.text("label", slug) ?: slug
        action("Make $label a full native app", listOf("native", slug, agent))
        if (MakerRuntime.running) { selected = slug; page = "job" }
    }

    /** Whether the creation's web version is finished (so it can go on the phone with one tap). */
    fun webReady(slug: String) = systemWord(slug, "web") == "ready to open"

    /** Whether its Android version is the web version wrapped (not yet a full native app). */
    fun wrappedApp(slug: String) = creations.firstOrNull { it.text("slug") == slug }?.optJSONObject("systems")?.optJSONObject("android")?.optBoolean("wrapped") == true

    /** A creation's web version running inside Hatchlybox (its export/ folder, else its own). */
    var webRun by mutableStateOf<String?>(null)
    fun runWeb(slug: String) { webRun = slug; used("made:$slug") }
    fun webFolder(slug: String): File =
        linux.hostFile("/root/ports/$slug/web/export").takeIf { File(it, "index.html").isFile } ?: linux.hostFile("/root/ports/$slug/web")

    /** A widget's open action: a web page, a phone app (app:<package>), the phone's calendar, clock, battery or settings (phone:<screen>) or an app's page here (maker:<slug>). */
    fun openTarget(target: String) {
        val context = getApplication<Application>()
        when {
            target.startsWith("app:") -> context.packageManager.getLaunchIntentForPackage(target.removePrefix("app:"))
                ?.let { launch(it) } ?: run { notice = "That app isn't on this phone." }
            target.startsWith("maker:") -> apps.firstOrNull { it.text("slug") == target.removePrefix("maker:") }?.let { openApp(it) }
            // The phone's own screens, whichever app provides them.
            target == "phone:calendar" -> launch(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            target == "phone:clock" -> openClock()
            target == "phone:battery" -> launch(Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            target == "phone:settings" -> launch(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            target.startsWith("https://") || target.startsWith("http://") -> launch(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        }
    }

    fun login(a: String) = connect(a)

    // ---------- agents: connect, connecting, connected ----------

    /** Each agent's sign-in: "connected", "free" (OpenCode on its free models, no account), "disconnected", "missing" (not installed), "connecting"; absent until checked. */
    val agentState = mutableStateMapOf<String, String>()
    /** The agent whose sign-in task is running, and when it last finished connecting (for the brief "Connected"). */
    var connectingAgent by mutableStateOf("")
    var justConnected by mutableStateOf("")
    private var checkingAgents = false
    fun agentOk(a: String) = agentState[a].let { it == null || it == "connected" || it == "free" }
    var checkingAgentsNow by mutableStateOf(false)

    /** Asks each CLI whether it's signed in, off the main thread; quietly, a few seconds. */
    fun checkAgents() {
        if (checkingAgents || !agentsInstalled) return
        checkingAgents = true; checkingAgentsNow = true
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { Agent.entries.associate { ag -> ag.bin to statusOf(ag) } }
            checkingAgents = false; checkingAgentsNow = false
            found.forEach { (k, v) ->
                if (v == null) return@forEach
                if (k == connectingAgent && MakerRuntime.running && v != "connected") return@forEach
                if (v == "connected" && (agentState[k] == "connecting" || k == connectingAgent)) {
                    // Signed in after all: a sign-in still waiting in the console isn't needed, and would block everything else.
                    if (k == connectingAgent && MakerRuntime.running && MakerRuntime.title.startsWith("Connect ")) MakerRuntime.stop()
                    justConnected = k; connectingAgent = ""; viewModelScope.launch { delay(2500); if (justConnected == k) justConnected = "" }
                }
                agentState[k] = v
            }
        }
    }

    /** Whether an agent is signed in, asked of its CLI (blocking): null when it didn't answer in time. */
    private fun statusOf(ag: Agent): String? = runCatching {
        // A status command that hangs (no network) mustn't keep the old answer forever: 124 = no answer, keep what we had.
        val (code, out) = linux.run("command -v ${ag.bin} >/dev/null || exit 127; timeout 25 ${ag.statusCommand}")
        when {
            code == 127 -> "missing"
            code == 124 || code == 143 -> null
            ag.isConnected(code, out) -> if (ag.onFreeModels(out)) "free" else "connected"
            else -> "disconnected"
        }.also { if (ag == Agent.OPENCODE && it != null && it != "missing") openCodeModel(it == "free") }
    }.getOrNull()

    /** Something else is running: what, and how to stop it (rather than a bare "finish it first"). */
    fun busyNotice() {
        notice = if (MakerRuntime.running) "Still busy with “${MakerRuntime.title}”. Wait for it, or press Stop on its notification or its tile."
            else "Finish the current task first."
    }

    /**
     * OpenCode with no provider runs on OpenCode's free models. Its own pick changes from run to run and some
     * hang, so while no provider is signed in Hatchlybox sets one that answers (big-pickle, else the first free
     * one) as the default in /root/.config/opencode/opencode.json. A model the user set is never touched, and
     * ours is taken out again once a provider is signed in, so the provider's own models are used.
     */
    private fun openCodeModel(free: Boolean) = runCatching {
        val dir = linux.hostFile("/root/.config/opencode")
        if (File(dir, "opencode.jsonc").let { it.isFile && it.readText().contains("\"model\"") }) return@runCatching
        val file = File(dir, "opencode.json")
        val json = if (file.isFile) JSONObject(file.readText()) else JSONObject().put("\$schema", "https://opencode.ai/config.json")
        val ours = prefs.getString("opencodeModel", "") ?: ""
        val now = json.optString("model")
        if (free && now.isBlank()) {
            val (_, list) = linux.run("timeout 30 opencode models 2>/dev/null")
            val models = list.lines().map { it.trim() }.filter { it.startsWith("opencode/") }
            val pick = models.firstOrNull { it == "opencode/big-pickle" } ?: models.firstOrNull { it.endsWith("-free") } ?: return@runCatching
            dir.mkdirs(); file.writeText(json.put("model", pick).toString(2))
            prefs.edit().putString("opencodeModel", pick).apply()
        } else if (!free && now.isNotBlank() && now == ours) {
            json.remove("model"); file.writeText(json.toString(2))
            prefs.edit().remove("opencodeModel").apply()
        }
    }

    // ---------- the model each agent runs with ----------

    /** The model chosen for each agent ("" = the agent's own default), as the engine's settings hold it. */
    fun agentModel(a: String) = settings.text("${a}Model")

    /**
     * The models an agent offers: Claude's names (they always mean its newest of each), Codex's from the list
     * it keeps of what the account can use, OpenCode's from `opencode models` (the providers signed in, and the
     * free ones). Pairs of id and the name to show.
     */
    suspend fun modelChoices(a: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        when (a) {
            "claude" -> listOf("opus" to "Opus (most capable)", "sonnet" to "Sonnet (balanced)", "haiku" to "Haiku (fastest)", "opus[1m]" to "Opus, long context", "sonnet[1m]" to "Sonnet, long context")
            "codex" -> runCatching {
                val ms = JSONObject(linux.hostFile("/root/.codex/models_cache.json").readText()).getJSONArray("models")
                (0 until ms.length()).mapNotNull { ms.optJSONObject(it) }.filter { it.optString("visibility") == "list" }
                    .sortedBy { it.optInt("priority", 99) }.map { it.optString("slug") to it.optString("display_name", it.optString("slug")) }
            }.getOrDefault(emptyList())
            else -> linux.run("timeout 40 opencode models 2>/dev/null").second.lines().map { it.trim() }.filter { '/' in it && ' ' !in it }.map { it to it }
        }
    }

    /**
     * Sets an agent's model everywhere it runs: the engine's settings (apps it builds, passed as --model), and the
     * agent's own settings in Hatchlybox's Linux (the chat, the quick questions and the terminal widgets).
     */
    fun chooseModel(a: String, model: String) {
        if (!Regex("""[A-Za-z0-9._:/@#\[\]-]{0,120}""").matches(model)) { notice = "That isn't a model name."; return }
        settings = JSONObject(settings.toString()).put("${a}Model", model)
        viewModelScope.launch(Dispatchers.IO) {
            val (code, out) = linux.run("hatchlybox set ${a}Model ${quote(model)}")
            runCatching {
                when (a) {
                    "claude" -> {
                        val f = linux.hostFile("/root/.claude/settings.json")
                        val j = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
                        if (model.isEmpty()) j.remove("model") else j.put("model", model)
                        f.parentFile?.mkdirs(); f.writeText(j.toString(2))
                    }
                    "codex" -> {
                        val f = linux.hostFile("/root/.codex/config.toml")
                        val lines = if (f.isFile) f.readLines().toMutableList() else mutableListOf()
                        // The top-level key, above the first [table].
                        val top = lines.indexOfFirst { it.trimStart().startsWith("[") }.let { if (it < 0) lines.size else it }
                        val at = (0 until top).firstOrNull { Regex("""^\s*model\s*=""").containsMatchIn(lines[it]) }
                        if (at != null) lines.removeAt(at)
                        if (model.isNotEmpty()) lines.add(at ?: 0, "model = \"$model\"")
                        f.parentFile?.mkdirs(); f.writeText(lines.joinToString("\n", postfix = "\n"))
                    }
                    else -> {
                        val dir = linux.hostFile("/root/.config/opencode")
                        val f = File(dir, "opencode.jsonc").takeIf { it.isFile && it.readText().contains("\"model\"") } ?: File(dir, "opencode.json")
                        val j = if (f.isFile) JSONObject(f.readText()) else JSONObject().put("\$schema", "https://opencode.ai/config.json")
                        if (model.isEmpty()) j.remove("model") else j.put("model", model)
                        dir.mkdirs(); f.writeText(j.toString(2))
                        // The user's own choice: the free-model pick leaves it alone (and puts one back when it's cleared).
                        prefs.edit().remove("opencodeModel").apply()
                        if (model.isEmpty() && agentState["opencode"] == "free") openCodeModel(true)
                    }
                }
            }
            .onFailure { e -> withContext(Dispatchers.Main) { notice = "Existing configuration was kept: ${e.message}" }; return@launch }
            withContext(Dispatchers.Main) {
                notice = if (code != 0) "Saved for the chat and widgets; the build tools didn't take it: ${out.lines().lastOrNull().orEmpty()}"
                    else "${agentName(a)} now uses " + (model.ifEmpty { "its default model" }) + "."
                refresh()
            }
        }
    }

    /**
     * Starts the sign-in. The sign-in page opens by itself (the BROWSER shim); OpenCode's provider menu needs the console.
     * It asks the agent first: when it turns out signed in after all (an earlier check that failed), nothing opens and
     * [then] (what was being done) carries on.
     */
    fun connect(a: String, then: (() -> Unit)? = null) {
        if (agentState[a] == "missing") { if (installing) stillInstalling("${agentName(a)} can be signed in") else install(auto = true, why = "Installing ${agentName(a)}"); return }
        if (!agentsInstalled) { stillInstalling("You can sign in"); return }
        // Its sign-in is already open: back to it (finish it there, or Stop).
        if (MakerRuntime.running) { if (connectingAgent == a) { showConsole = true; notice = "Signing in to ${agentName(a)} is open: finish it here, or Stop it." } else busyNotice(); return }
        val ag = Agent.entries.firstOrNull { it.bin == a }
        if (ag != null && agentState[a] != "connecting" && agentState[a] != "free") {
            notice = "Checking ${agentName(a)}…"
            viewModelScope.launch {
                val now = withContext(Dispatchers.IO) { statusOf(ag) }
                if (now != null) agentState[a] = now
                if (now == "connected" || now == "free") { notice = if (then == null) "${agentName(a)} is signed in." else ""; then?.invoke() } else signIn(a)
            }
            return
        }
        signIn(a)
    }

    private fun signIn(a: String) {
        if (MakerRuntime.running) { busyNotice(); return }
        val cmd = when (a) { "claude" -> "claude auth login"; "codex" -> "codex login"; else -> "opencode auth login" }
        links = emptyList(); connectingAgent = a; agentState[a] = "connecting"
        MakerRuntime.start(getApplication(), "Connect $a", cmd)
        if (a == "opencode") showConsole = true
    }
    /** A code the sign-in page shows, typed back into the waiting CLI. */
    fun sendCode(code: String) { if (code.isNotBlank()) MakerRuntime.send(code.trim() + "\r") }
    /** The sign-in task ended (done, failed or stopped): check again, and drop "connecting". */
    fun connectEnded() {
        val a = connectingAgent
        if (a.isNotBlank() && agentState[a] == "connecting") agentState[a] = "disconnected"
        checkAgents()
        if (a.isNotBlank()) viewModelScope.launch { delay(4000); if (connectingAgent == a && !MakerRuntime.running) connectingAgent = "" }
    }

    // ---------- one word per app: Build, Building, Updating, Update, Install, Open ----------

    /** The app's one word and the action behind it. */
    fun simple(a: JSONObject): Pair<String, String> {
        if (busy && job.text("slug") == a.text("slug")) return (if (a.has("apk")) "Updating" else "Building") to "log"
        val s = status(a)
        return when (s.key) {
            "installed" -> "Open" to "open"
            "update", "review" -> "Update" to "install"
            "ready", "downloaded" -> "Install" to "install"
            "crashed" -> "Build" to "fix"
            "rebuild" -> "Build" to "rebuild"
            "failed", "stopped", "change-failed", "new" -> "Build" to "retry"
            else -> when {
                installedAt(a.text("pkg")) != null -> "Open" to "open"
                a.has("apk") -> "Install" to "install"
                else -> "Build" to "retry"
            }
        }
    }

    // ---------- the app's page: screenshots, description, the systems it's made for ----------

    /** Screenshots it has: store shots, the agents' peek screenshots, Godot renders. Newest first, at most 8. */
    fun screenshots(a: JSONObject): List<File> {
        val dir = file(a, "")
        val shots = file(a, "play/shots").listFiles().orEmpty().toList() + file(a, ".peek").listFiles().orEmpty().toList() +
            file(a, "design/renders").walkTopDown().maxDepth(2).filter { it.name.startsWith("shot-") }.toList()
        return shots.filter { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp") && it.length() > 0 && it.path.startsWith(dir.path) }
            .sortedByDescending { it.lastModified() }.take(8)
    }
    /** A thumbnail about 640 px tall, decoded off the main thread by the caller. */
    fun thumbnail(f: File): android.graphics.Bitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1; while (bounds.outHeight / (sample * 2) >= 640) sample *= 2
        android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
    /** The store listing's descriptions, else the brief's hook, else the idea it was made from. */
    fun description(a: JSONObject): Pair<String, String> {
        runCatching { JSONObject(file(a, "play/listing.json").readText()) }.getOrNull()?.let { l ->
            val short = l.text("shortDescription"); val full = l.text("fullDescription")
            if (short.isNotBlank() || full.isNotBlank()) return short to full
        }
        val hook = runCatching { file(a, "design/brief.md").useLines { ls -> ls.firstOrNull { it.startsWith("# ") } } }.getOrNull()
            ?.removePrefix("# ")?.substringAfter(": ", "")?.trim().orEmpty()
        val idea = a.optJSONArray("history")?.optJSONObject(0)?.text("prompt").orEmpty()
            .removePrefix("Build this Android app in the current directory: ").lineSequence().firstOrNull().orEmpty()
        return hook.replaceFirstChar { it.uppercase() } to idea
    }
    // ---------- the library: every app in one place ----------

    /** The library is open (it slides up over the main screen). */
    var library by mutableStateOf(false)
    /** An app the library should scroll to and show (an action is about to open it, as the user would). */
    var reveal by mutableStateOf<String?>(null)
    /** Favourites, in the order they were added: "apk:<slug>", "web:<id>", "port:<slug>/<target>", "phone:<pkg>/<activity>". */
    val favourites = mutableStateListOf<String>().apply { addAll((prefs.getString("favourites", "") ?: "").split('\n').filter { it.isNotBlank() }) }
    /** How often each library app was opened: the carousel shows the most used after the favourites. */
    val uses = mutableStateMapOf<String, Int>().apply {
        runCatching { val o = JSONObject(prefs.getString("uses", "{}") ?: "{}"); o.keys().forEach { put(it, o.optInt(it)) } }
    }
    /** The library apps opened most recently, newest first (the library's Recent row). */
    val recent = mutableStateListOf<String>().apply { addAll((prefs.getString("recent", "") ?: "").split('\n').filter { it.isNotBlank() }) }
    fun used(key: String) {
        uses[key] = (uses[key] ?: 0) + 1
        recent.remove(key); recent.add(0, key)
        while (recent.size > 24) recent.removeAt(recent.lastIndex)
        prefs.edit().putString("uses", JSONObject(uses.toMap()).toString()).putString("recent", recent.joinToString("\n")).apply()
    }
    fun toggleFavourite(key: String) {
        if (!favourites.remove(key)) favourites.add(key)
        prefs.edit().putString("favourites", favourites.joinToString("\n")).apply()
    }
    class Port(val slug: String, val target: String, val created: Long, val exported: Boolean)
    /** Every app made for another system, from all the Android apps. */
    fun allPorts(): List<Port> = linux.hostFile("/root/ports").listFiles().orEmpty().filter { it.isDirectory }.flatMap { app ->
        app.listFiles().orEmpty().filter { it.isDirectory }.map { dir ->
            val created = runCatching { JSONObject(File(dir, ".port.json").readText()).optLong("created") }.getOrDefault(dir.lastModified() / 1000)
            Port(app.name, dir.name, created, File(dir, "export").isDirectory)
        }
    }

    /** The other systems it has been made for: folders in /root/ports/<slug>/<target>. */
    fun ports(a: JSONObject): List<String> =
        linux.hostFile("/root/ports/${a.text("slug")}").listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.sorted()

    /** A full copy under a new name and package, built straight away. */
    fun clone(a: JSONObject) {
        if (MakerRuntime.running || busy || installing || godotInstalling) { notice = "Finish the current task first."; return }
        val slug = a.text("slug"); val label = a.text("label", slug) + " copy"
        val script = """
            set -e
            src=/root/apps/$slug; dst=/root/apps/$slug-copy; n=2
            while [ -e "${'$'}dst" ]; do dst=/root/apps/$slug-copy-${'$'}n; n=${'$'}((n + 1)); done
            new=${'$'}(basename "${'$'}dst"); seg=${'$'}(printf '%s' "${'$'}new" | tr -c 'a-z0-9' '_')
            case ${'$'}seg in [0-9]*) seg=app_${'$'}seg ;; esac
            old=${'$'}(jq -r .pkg "${'$'}src/.hatchlybox.json"); pkg=com.hatchlybox.${'$'}seg
            echo "Copying ${'$'}src to ${'$'}dst (${'$'}pkg)"
            mkdir "${'$'}dst"
            tar -C "${'$'}src" --exclude=./app/build --exclude=./build --exclude=./.gradle --exclude=./.kotlin --exclude=./.peek --exclude='.l2s.*' -cf - . | tar -C "${'$'}dst" -xf -
            oldp=${'$'}(printf '%s' "${'$'}old" | tr . /); newp=${'$'}(printf '%s' "${'$'}pkg" | tr . /)
            for d in main test androidTest; do j="${'$'}dst/app/src/${'$'}d/java"; [ -d "${'$'}j/${'$'}oldp" ] || continue; mkdir -p "${'$'}(dirname "${'$'}j/${'$'}newp")"; mv "${'$'}j/${'$'}oldp" "${'$'}j/${'$'}newp"; done
            esc=${'$'}(printf '%s' "${'$'}old" | sed 's/\./\\./g')
            grep -rlIF --exclude-dir=.git "${'$'}old" "${'$'}dst" | xargs -r sed -i "s/${'$'}esc\([^A-Za-z0-9_]\)/${'$'}pkg\1/g; s/${'$'}esc${'$'}/${'$'}pkg/"
            jq --arg s "${'$'}new" --arg p "${'$'}pkg" --arg l ${quote(label)} --argjson t "${'$'}(date +%s)" '{slug:${'$'}s, pkg:${'$'}p, label:${'$'}l, created:${'$'}t, updated:${'$'}t, status:"new", history:[], clonedFrom:.slug}' "${'$'}src/.hatchlybox.json" > "${'$'}dst/.hatchlybox.json"
            cd "${'$'}dst"; git add -A; git -c user.name=hatchlybox -c user.email=hatchlybox@localhost commit -qm "Clone of $slug" || true
            hatchlybox list >/dev/null
            echo "Building ${'$'}new"
            hatchlybox build "${'$'}new"
        """.trimIndent()
        MakerRuntime.start(getApplication(), "Clone ${a.text("label", slug)}", script)
        notice = "Making a copy: $label. It appears in your apps and builds by itself."
    }

    /** A port's files as one archive, to share. */
    fun sharePort(a: JSONObject, target: String) {
        if (MakerRuntime.running) { notice = "Finish the current task first."; return }
        val slug = a.text("slug"); val out = "/root/.cache/hatchlybox/apks/$slug-$target-export.tar.gz"
        viewModelScope.launch {
            // The export (everything needed to run it there) when the agent made one, else the whole folder.
            val ok = withContext(Dispatchers.IO) { runCatching { linux.run("cd /root/ports/${quote(slug)}/${quote(target)} && if [ -d export ]; then tar czf ${quote(out)} -C export .; else cd .. && tar czf ${quote(out)} --exclude=node_modules --exclude=.git ${quote(target)}; fi").first == 0 }.getOrDefault(false) }
            val f = linux.hostFile(out)
            if (!ok || !f.isFile) { notice = "Couldn't pack the files."; return@launch }
            val uri = FileProvider.getUriForFile(getApplication(), "com.hatchlybox.app.files", f)
            launch(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/gzip").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export for ${portName(target)}").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }
    fun external(url: String) {
        if (!url.startsWith("https://") && !url.startsWith("http://127.0.0.1:")) return
        launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
    private fun launch(intent: Intent) { runCatching { getApplication<Application>().startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { notice = "No app can open this item." } }
    fun artifact(a: JSONObject, share: Boolean = false, bundle: Boolean = false) {
        viewModelScope.launch {
            val ext = if(bundle) "aab" else "apk"
            val f = File(cache, "apks/${a.text("slug")}.$ext")
            val expected = a.optJSONObject("artifacts")?.optJSONObject(ext)?.text("sha256").orEmpty()
            val actual = withContext(Dispatchers.IO) {
                runCatching {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    f.inputStream().use { input -> val buffer = ByteArray(65536); while(true) { val n = input.read(buffer); if(n < 0) break; digest.update(buffer, 0, n) } }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }.getOrDefault("")
            }
            if(expected.isBlank() || actual != expected) { notice = "This file has no matching build receipt. Rebuild before installing or sharing."; return@launch }
            openArtifact(a, share, bundle)
        }
    }
    private fun openArtifact(a: JSONObject, share: Boolean, bundle: Boolean) {
        val ext = if(bundle) "aab" else "apk"
        val f = File(cache, "apks/${a.text("slug")}.$ext")
        if (!f.isFile) { notice = "Build ${if(bundle) "the Play bundle" else "this app"} first."; return }
        val uri = FileProvider.getUriForFile(getApplication(), "com.hatchlybox.app.files", f)
        val mime = if(bundle) "application/octet-stream" else "application/vnd.android.package-archive"
        val intent = if(share || bundle) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launch(if(share || bundle) Intent.createChooser(intent, "Share ${a.text("label")}").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) else intent)
    }
    fun launchApp(a: JSONObject) {
        val intent = getApplication<Application>().packageManager.getLaunchIntentForPackage(a.text("pkg"))
        if (intent == null) notice = "Install this app first." else launch(intent)
    }
    fun shareUploadKey(a: JSONObject) {
        val file = File(cache, "apks").listFiles().orEmpty().filter { it.name.startsWith(a.text("slug") + "-") && it.name.endsWith("-upload-key.txt") }.maxByOrNull { it.lastModified() }
        if (file == null) { notice = "Create an encrypted upload-key backup first."; return }
        val uri = FileProvider.getUriForFile(getApplication(), "com.hatchlybox.app.files", file)
        launch(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Save encrypted upload-key backup").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    fun restoreUploadKey(a: JSONObject, uri: Uri) {
        if(MakerRuntime.running) return
        viewModelScope.launch {
            try {
                val path = "/root/.cache/hatchlybox/import-upload-key.txt"
                withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openInputStream(uri)!!.use { input -> linux.hostFile(path).outputStream().use { input.copyTo(it) } } }
                MakerRuntime.start(getApplication(), "Restore upload key", "hatchlybox play restore ${quote(a.text("slug"))} ${quote(path)}; rc=\$?; rm -f ${quote(path)}; exit \$rc")
                showConsole = true
            } catch(e: Exception) { notice = e.message ?: "Couldn't import upload-key backup" }
        }
    }
    fun importPlayKey(uri: Uri) {
        if (MakerRuntime.running || busy || installing || godotInstalling) return
        viewModelScope.launch {
            try {
                val path = "/root/.cache/hatchlybox/import-service-account.json"
                withContext(Dispatchers.IO) {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { input -> val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= 100000) { "Key file is too large" }; out.write(buffer, 0, n) }; out.toByteArray() }
                    require(bytes.size <= 100000) { "Key file is too large" }
                    val j = JSONObject(String(bytes)); require(j.text("type") == "service_account" && j.has("private_key")) { "Choose a service account JSON key" }
                    linux.hostFile(path).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
                }
                // Remove the temporary import even if the backend rejects it.
                MakerRuntime.start(getApplication(), "Connect Google Play", "hatchlybox play key ${quote(path)}; rc=\$?; rm -f ${quote(path)}; exit \$rc")
                showConsole = true
            } catch(e: Exception) { notice = e.message ?: "Couldn't import key" }
        }
    }
    fun file(a: JSONObject, relative: String) = linux.hostFile("/root/apps/${a.text("slug")}/$relative")
    fun saveListing(a: JSONObject, text: String) {
        if (MakerRuntime.running || busy || installing) { notice = "Finish the current task first."; return }
        try { JSONObject(text) } catch (_: Exception) { notice = "Listing must be valid JSON."; return }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { file(a, "play/listing.json").writeText(text) }.onSuccess { withContext(Dispatchers.Main) { notice = "Listing saved. Review the release again before uploading." } }.onFailure { e -> withContext(Dispatchers.Main) { notice = e.message ?: "Could not save listing" } }
        }
    }
}

fun agentName(a: String) = when (a) { "claude" -> "Claude Code"; "codex" -> "Codex"; "opencode" -> "OpenCode"; else -> a }
fun portName(t: String) = when (t) { "linux" -> "Linux"; "omarchy" -> "Omarchy"; "windows" -> "Windows"; "web" -> "Web"; else -> t.replace('-', ' ').replaceFirstChar { it.uppercase() } }
