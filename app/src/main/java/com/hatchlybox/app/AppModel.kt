package com.hatchlybox.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Kind { YOU, OUT, INFO }

class Entry(val kind: Kind, text: String = "") {
    var text by mutableStateOf(text)
}

class Chat {
    val entries = mutableStateListOf<Entry>()
    var running by mutableStateOf(false)
    var stopping by mutableStateOf(false)
    var startedAt by mutableStateOf(0L)
    var resume = false
    var process: Process? = null
}

// One app the user asked for: its own folder in Linux, its own conversation, built by one model.
class Project(val id: String, val agent: Agent, val created: Long, val idea: String, name: String, var autoName: Boolean) {
    var name by mutableStateOf(name)
    var hasApp by mutableStateOf(false)
    val chat = Chat()
    val dir get() = "/root/work/apps/$id"
}

sealed interface Page {
    data object Home : Page
    data object Settings : Page
    data class App(val id: String) : Page
    data class Run(val id: String) : Page
}

sealed interface Env {
    data object Missing : Env
    data object Installing : Env
    data object Ready : Env
    data class Failed(val message: String) : Env
}

enum class Status { UNKNOWN, CHECKING, CONNECTED, DISCONNECTED, MISSING }

// An interactive terminal for a CLI's own sign-in or sign-out flow.
class Console(val agent: Agent, val title: String, private val onUrl: (String) -> Unit) {
    private val screen = Screen()
    private val opened = mutableSetOf<String>()
    var text by mutableStateOf("")
    var urls by mutableStateOf(listOf<String>())
    var done by mutableStateOf(false)
    var process: Process? = null

    fun feed(s: String) {
        screen.feed(s)
        text = screen.text()
        urls = screen.urls()
        // Open the sign-in page once, the first time a link shows up.
        urls.firstOrNull()?.let { if (opened.isEmpty() && opened.add(it)) onUrl(it) }
    }

    fun send(s: String) {
        runCatching { process?.outputStream?.apply { write(s.toByteArray()); flush() } }
    }
}

class AppModel(app: Application) : AndroidViewModel(app) {
    private val linux = Linux(app)
    private val store = File(app.filesDir, "projects.json")

    // Web apps need only the AI helpers, which install before Java and the Android tools.
    var env by mutableStateOf<Env>(if (linux.hasAgents) Env.Ready else Env.Missing)
    val setupLog = mutableStateListOf<String>()
    var selected by mutableStateOf(Agent.OPENCODE)
    var screen by mutableStateOf<Page>(Page.Home)
    var console by mutableStateOf<Console?>(null)
    val projects = mutableStateListOf<Project>()
    val status = mutableStateMapOf<Agent, Status>().apply { Agent.entries.forEach { put(it, Status.UNKNOWN) } }

    init {
        load()
        if (linux.hasAgents) refreshStatus()
    }

    fun install() {
        if (env == Env.Installing || MakerRuntime.running || projects.any { it.chat.running } || console?.done == false) return
        env = Env.Installing
        setupLog.clear()
        viewModelScope.launch {
            env = withContext(Dispatchers.IO) {
                try {
                    linux.install(log = { line -> viewModelScope.launch { setupLog += line } })
                    Env.Ready
                } catch (e: Exception) {
                    Env.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
            if (env == Env.Ready) refreshStatus()
        }
    }

    // Deleting a few hundred MB takes a while, so it happens off the main thread. Saved chats
    // stay readable but start fresh, since the CLIs' own session files are gone.
    fun resetEnvironment() {
        env = Env.Failed("Reset is disabled because native apps share this environment. Use Update tools to repair it without losing projects or sign-ins.")
    }

    fun refreshStatus() {
        if (env != Env.Ready) return
        Agent.entries.forEach { a ->
            if (status[a] == Status.CHECKING) return@forEach
            status[a] = Status.CHECKING
            viewModelScope.launch(Dispatchers.IO) {
                val checked = runCatching {
                    val (code, out) = linux.run("command -v ${a.bin} >/dev/null || exit 127; ${a.statusCommand}")
                    when {
                        code == 127 -> Status.MISSING
                        a.isConnected(code, out) -> Status.CONNECTED
                        else -> Status.DISCONNECTED
                    }
                }.getOrDefault(Status.UNKNOWN)
                withContext(Dispatchers.Main) { status[a] = checked }
            }
        }
    }

    fun project(id: String) = projects.firstOrNull { it.id == id }

    /** The tools may have finished installing (in the Android-apps side) since this model started. */
    fun ensureReady(): Boolean {
        if (env == Env.Missing && linux.hasAgents) { env = Env.Ready; refreshStatus() }
        return env == Env.Ready
    }

    // Starts a new app from the home screen's idea and opens its page.
    fun build(idea: String) {
        val text = idea.trim()
        if (text.isEmpty() || env != Env.Ready) return
        val now = System.currentTimeMillis()
        val p = Project(java.util.UUID.randomUUID().toString(), selected, now, text, guessName(text), autoName = true)
        projects.add(0, p)
        screen = Page.App(p.id)
        send(p, text, buildPrompt(text))
    }

    // `shown` is what the chat shows; `prompt` is what the model gets.
    fun send(project: Project, shown: String, prompt: String = shown) {
        if (MakerRuntime.running) return
        val agent = project.agent
        val chat = project.chat
        if (chat.running || prompt.isBlank() || env != Env.Ready) return
        chat.entries += Entry(Kind.YOU, shown.trim())
        val out = Entry(Kind.OUT)
        chat.entries += out
        chat.running = true
        chat.stopping = false
        chat.startedAt = System.currentTimeMillis()
        save()
        val resume = chat.resume
        viewModelScope.launch(Dispatchers.IO) {
            var code = -1
            try {
                linux.hostFile(project.dir).mkdirs()
                val p = linux.start(agent.runCommand(resume), mapOf("PROMPT" to prompt.trim()), cwd = project.dir)
                chat.process = p
                if (chat.stopping) p.destroy()
                p.outputStream.close()
                // Output lands on the main thread, in order, so Compose never sees a half-written update.
                p.inputStream.bufferedReader().forEachLine { line ->
                    agent.render(line)?.let { r -> viewModelScope.launch { out.text = (if (out.text.isEmpty()) r else out.text + "\n" + r).takeLast(200000) } }
                }
                code = p.waitFor()
            } catch (e: Exception) {
                viewModelScope.launch { out.text += "\n! ${e.message}" }
            }
            chat.process?.destroy()
            val title = appTitle(project)
            withContext(Dispatchers.Main) {
                project.hasApp = title != null
                if (project.autoName && !title.isNullOrBlank()) project.name = title
                finish(agent, chat, out, code)
            }
        }
    }

    // The app's <title>, or "" when it has no title, or null when there's no index.html yet.
    private fun appTitle(project: Project): String? {
        val index = File(linux.hostFile(project.dir), "index.html")
        if (!index.exists()) return null
        val html = runCatching { index.readText() }.getOrDefault("")
        return Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?.replace(Regex("\\s+"), " ")?.trim()?.take(40) ?: ""
    }

    fun appFolder(project: Project): File = linux.hostFile(project.dir)

    private fun finish(agent: Agent, chat: Chat, out: Entry, code: Int) {
        out.text = out.text.trim()
        when {
            chat.stopping -> chat.entries += Entry(Kind.INFO, "Stopped")
            code == 0 -> chat.resume = true
            Regex("not logged in|/login|auth login|unauthori[sz]ed|authentication|\\b401\\b|no credentials", RegexOption.IGNORE_CASE).containsMatchIn(out.text) ->
                chat.entries += Entry(Kind.INFO, "${agent.label} isn't connected. Open Settings to sign in.")
            else -> chat.entries += Entry(Kind.INFO, "Exited with code $code")
        }
        if (out.text.isEmpty()) chat.entries.remove(out)
        chat.running = false
        chat.stopping = false
        chat.process = null
        save()
    }

    // The chat stays busy until the process has really exited, so a new prompt can't overlap the old run.
    fun stop(project: Project) {
        val chat = project.chat
        if (!chat.running || chat.stopping) return
        chat.stopping = true
        val p = chat.process ?: return
        viewModelScope.launch(Dispatchers.IO) {
            p.destroy()
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }

    fun rename(project: Project, name: String) {
        if (name.isBlank()) return
        project.name = name.trim()
        project.autoName = false
        save()
    }

    fun delete(project: Project) {
        project.chat.process?.destroy()
        projects.remove(project)
        if (screen == Page.App(project.id) || screen == Page.Run(project.id)) screen = Page.Home
        save()
        val folder = appFolder(project)
        viewModelScope.launch(Dispatchers.IO) { folder.deleteRecursively() }
    }

    fun connect(agent: Agent) = openConsole(agent, "Connect ${agent.label}", agent.loginCommand)

    fun disconnect(agent: Agent) {
        if (agent.interactiveLogout) return openConsole(agent, "Disconnect ${agent.label}", agent.logoutCommand)
        status[agent] = Status.CHECKING
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { linux.run(agent.logoutCommand) }
            withContext(Dispatchers.Main) { status[agent] = Status.UNKNOWN; refreshStatus() }
        }
    }

    private fun openConsole(agent: Agent, title: String, command: String) {
        if (env != Env.Ready || console?.done == false || MakerRuntime.running) return
        val c = Console(agent, title, ::openUrl)
        console = c
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val p = linux.start(command, pty = true)
                c.process = p
                if (console !== c) p.destroy()
                val reader = p.inputStream.reader()
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    val s = String(buf, 0, n)
                    viewModelScope.launch { c.feed(s) }
                }
                p.waitFor()
            } catch (e: Exception) {
                viewModelScope.launch { c.feed("\r\n! ${e.message}\r\n") }
            }
            c.process?.destroy()
            viewModelScope.launch { c.done = true; refreshStatus() }
        }
    }

    fun closeConsole() {
        val c = console ?: return
        console = null
        if (!c.done) { c.process?.destroy(); refreshStatus() }
    }

    fun openUrl(url: String) {
        val app = getApplication<Application>()
        runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun save() {
        val json = JSONObject().put("agent", selected.name).put("projects", JSONArray().apply {
            projects.forEach { p ->
                put(JSONObject().put("id", p.id).put("agent", p.agent.name).put("created", p.created).put("idea", p.idea)
                    .put("name", p.name).put("auto", p.autoName).put("resume", p.chat.resume)
                    .put("entries", JSONArray().apply { p.chat.entries.forEach { put(JSONObject().put("k", it.kind.name).put("t", it.text)) } }))
            }
        })
        runCatching { val temp = File(store.parentFile, "projects.json.tmp"); temp.writeText(json.toString()); check(temp.renameTo(store)) { "Could not save library" } }.onFailure { env = Env.Failed(it.message ?: "Could not save library") }
    }

    private fun load() {
        val json = runCatching { JSONObject(store.readText()) }.getOrNull() ?: return
        runCatching { selected = Agent.valueOf(json.getString("agent")) }
        val arr = json.optJSONArray("projects") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id").isBlank()) continue
            val agent = runCatching { Agent.valueOf(o.getString("agent")) }.getOrDefault(Agent.CLAUDE)
            val p = Project(o.getString("id"), agent, o.optLong("created"), o.optString("idea"), o.optString("name"), o.optBoolean("auto"))
            p.chat.resume = o.optBoolean("resume")
            val entries = o.optJSONArray("entries") ?: JSONArray()
            for (j in 0 until entries.length()) {
                val e = entries.optJSONObject(j) ?: continue
                p.chat.entries += Entry(runCatching { Kind.valueOf(e.getString("k")) }.getOrDefault(Kind.OUT), e.optString("t"))
            }
            p.hasApp = File(linux.hostFile(p.dir), "index.html").exists()
            projects += p
        }
    }

    fun choose(agent: Agent) {
        selected = agent
        save()
    }

    override fun onCleared() {
        projects.forEach { it.chat.process?.destroy() }
        console?.process?.destroy()
    }
}

// A first name for the library until the app's own <title> replaces it.
private fun guessName(idea: String): String {
    val words = idea.replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(' ').filter { it.isNotBlank() }
    val skip = setOf("a", "an", "the", "app", "make", "build", "create", "me", "i", "want", "need", "please", "simple", "for", "that", "to", "with", "which", "can", "my", "write")
    val keep = words.filter { it.lowercase() !in skip }.take(3)
    return (keep.ifEmpty { words.take(3) }).joinToString(" ") { it.replaceFirstChar(Char::uppercase) }.ifEmpty { "New app" }
}

private fun buildPrompt(idea: String) = """
Build this app: $idea

Make it a web app in the current folder, with index.html as the entry point. It runs on an Android phone, inside a WebView, from the files in this folder:
- Plain HTML, CSS and JavaScript with no build step. Put every file it needs in this folder: no CDN links or downloads at runtime.
- Design it for a phone in portrait: touch-friendly controls, readable text, nothing wider than the screen. Follow the system light/dark theme (prefers-color-scheme).
- Keep the user's data with localStorage so it survives closing the app.
- Give it a short name (two or three words) in <title>.
Make it polished and fully working, then reply with a two- or three-sentence summary of what you built.
""".trim()
