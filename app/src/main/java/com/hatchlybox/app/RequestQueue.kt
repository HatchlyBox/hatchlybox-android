package com.hatchlybox.app

import android.content.Context
import androidx.compose.runtime.*
import org.json.*
import java.io.File
import java.util.UUID

object SharedTools { var updating by mutableStateOf(false) }

fun recoveredQueueState(state: String): String = when (state) { "", "queued" -> "queued"; else -> "interrupted" }
fun queueMayDispatch(states: List<String>): Boolean = states.all { it == "queued" }

object RequestQueue {
    val entries = mutableStateListOf<JSONObject>()
    var error by mutableStateOf("")
    private var loaded = false
    private lateinit var app: Context
    fun load(c: Context) {
        if (loaded) return
        app = c.applicationContext; loaded = true
        val file = File(app.filesDir, "request-queue.json")
        val rows = runCatching { JSONArray(SafeFiles.read(file)).objects() }.getOrElse {
            if (file.exists()) error = "The queue could not be read. Its file was kept; do not submit duplicates before reviewing Tasks."
            emptyList()
        }
        rows.forEach { entries += JSONObject(it.toString()).put("state", recoveredQueueState(it.text("state"))) }
        if (rows.isNotEmpty()) persist()
    }
    private fun persist(): Boolean = runCatching {
        SafeFiles.write(File(app.filesDir, "request-queue.json"), JSONArray(entries).toString()); error = ""; true
    }.getOrElse { error = "The queue could not be saved: ${it.message}. Review Tasks before repeating a request."; false }
    sealed class AddResult { object Added : AddResult(); data class Blocked(val reason: String) : AddResult() }
    fun add(c: Context, mode: String, helper: String, text: String, page: Int): AddResult {
        load(c)
        if (text.isBlank() || mode in setOf("auto", "ask")) return AddResult.Blocked("blank or unresolved mode")
        if (error.isNotBlank()) return AddResult.Blocked(error)
        val entry = JSONObject().put("id", UUID.randomUUID().toString()).put("mode", mode).put("agent", helper).put("text", text).put("page", page).put("state", "queued")
        entries += entry
        if (persist()) return AddResult.Added
        entries.remove(entry); return AddResult.Blocked(error)
    }
    fun remove(entry: JSONObject) {
        val index = entries.indexOf(entry); if (index < 0) return
        entries.removeAt(index)
        if (!persist()) entries.add(index, entry)
    }
    fun moveUp(entry: JSONObject) {
        val index = entries.indexOf(entry); if (index <= 0) return
        entries.removeAt(index); entries.add(index - 1, entry)
        if (!persist()) { entries.removeAt(index - 1); entries.add(index, entry) }
    }
    private fun replace(entry: JSONObject, next: JSONObject): JSONObject? {
        val index = entries.indexOf(entry); if (index < 0) return null
        entries[index] = next
        if (persist()) return next
        entries[index] = entry; return null
    }
    fun edit(entry: JSONObject, text: String) { if (text.isNotBlank()) replace(entry, JSONObject(entry.toString()).put("text", text)) }
    fun beginDispatch(entry: JSONObject): JSONObject? {
        if (entry.text("state") != "queued") return null
        return replace(entry, JSONObject(entry.toString()).put("state", "dispatching"))
    }
    fun retrySave() {
        val file = File(app.filesDir, "request-queue.json")
        runCatching {
            if (file.isFile) file.copyTo(File(app.filesDir, "request-queue-recovery-${System.currentTimeMillis()}.json"))
            persist()
        }.onFailure { error = "Could not preserve the old queue: ${it.message}" }
    }
    fun requeue(entry: JSONObject) { replace(entry, JSONObject(entry.toString()).put("state", "queued")) }
}

fun MakerModel.processQueue() {
    RequestQueue.load(getApplication())
    if (agentsBusy || installing || pendingIdea.isNotBlank() || RequestQueue.error.isNotBlank() || RequestQueue.entries.isEmpty()) return
    if (!queueMayDispatch(RequestQueue.entries.map { it.text("state") })) return
    val first = RequestQueue.entries.first(); val helper = first.text("agent")
    if (!agentsInstalled || !agentOk(helper) || first.text("mode") in setOf("app", "game") && !ready) return
    // Persist intent to dispatch before any external action. Uncertain attempts are never replayed automatically.
    val entry = RequestQueue.beginDispatch(first) ?: return
    val previous = agent; agent = helper
    val text = entry.text("text")
    val accepted = try {
        when (entry.text("mode")) {
            "app" -> create(text)
            "game" -> create("A game: $text")
            "chat", "do" -> AgentChat.send(getApplication(), runCatching { Agent.valueOf(helper.uppercase()) }.getOrDefault(Agent.CLAUDE), if (entry.text("mode") == "do") "Do this on the phone using its apps; do not build anything: $text" else text, display = text)
            "widget" -> makeWidget(text)?.let { id ->
                val page = entry.optInt("page", 0).coerceIn(0, launcher.pages.lastIndex.coerceAtLeast(0))
                launcher.add(page, "custom", id, sizeLater = true); true
            } ?: false
            else -> createFor(entry.text("mode"), text)
        }
    } catch (e: Exception) { agent = previous; notice = "Dispatch needs review: ${e.message}"; return }
    if (accepted) RequestQueue.remove(entry) else { agent = previous; RequestQueue.requeue(entry) }
}
