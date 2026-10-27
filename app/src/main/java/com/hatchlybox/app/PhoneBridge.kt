package com.hatchlybox.nightly

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// The agents' way to the phone's actions: the `phone` command (assets/agents/phone.sh) in Hatchlybox's Linux reads
// the list from /root/.hatchlybox/phone/actions.json and asks for one by writing requests/<id>.json; the home screen
// answers in results/<id>.json. An agent's request is never trusted more than a tap: secure actions check it's the
// owner, confirm ones wait for the user's yes, and only the harmless ones run at once.

object PhoneBridge {
    private var serveJob: kotlinx.coroutines.Job? = null
    @Volatile private var currentModel: MakerModel? = null
    @Volatile private var currentItems: () -> List<LibItem> = { emptyList() }
    fun keepServing(context: Context, m: MakerModel, items: () -> List<LibItem>) {
        currentModel = m; currentItems = items
        if (serveJob?.isActive == true) return
        serveJob = Setup.scope.launch { serve(context.applicationContext) }
    }
    private const val DIR = "/root/.hatchlybox/phone"
    // Held here: an unreferenced FileObserver stops when it's collected.
    private var watch: android.os.FileObserver? = null
    // Agents make actions themselves (their instructions say how), not through a chat with another agent.
    private val hidden = setOf("new_action")

    /** An agent's request waiting for the user's yes. */
    var asking by mutableStateOf<Pair<Choice, CompletableDeferred<Boolean>>?>(null)

    fun install(context: Context) = runCatching {
        val linux = Linux(context.applicationContext)
        if (!linux.hasBasics) return@runCatching
        val bin = linux.hostFile("/root/.local/bin/phone")
        bin.parentFile?.mkdirs()
        bin.writeBytes(context.assets.open("agents/phone.sh").use { it.readBytes() }); bin.setExecutable(true, false)
        writeList(context)
    }

    /** Every action an agent may ask for, with its level and details, for `phone list`. */
    fun writeList(context: Context) = runCatching {
        val linux = Linux(context.applicationContext)
        if (!linux.hasBasics) return@runCatching
        val a = JSONArray()
        Hands.all().filter { it.name !in hidden }.forEach { h ->
            a.put(JSONObject().put("name", h.name).put("title", h.title).put("description", h.description).put("level", h.level.name.lowercase())
                .put("params", JSONArray(h.params.map { p -> JSONObject().put("name", p.name).put("type", p.type).put("description", p.description).apply { if (p.enum.isNotEmpty()) put("enum", JSONArray(p.enum)) } }))
                .apply { h.custom?.let { put("folder", "/root/actions/" + it.name) } })
        }
        val dir = linux.hostFile(DIR).apply { mkdirs() }
        File(dir, "actions.json.tmp").apply { writeText(a.toString(2)); renameTo(File(dir, "actions.json")) }
    }

    /** Answers the agents' requests, one at a time, for as long as the home screen is up. */
    private suspend fun serve(context: Context) = withContext(Dispatchers.IO) {
        val linux = Linux(context.applicationContext)
        val requests = linux.hostFile("$DIR/requests"); val results = linux.hostFile("$DIR/results")
        // Woken the moment a request lands, not on the next round.
        requests.mkdirs()
        val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
        watch = object : android.os.FileObserver(requests.path, MOVED_TO or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) { if (path?.endsWith(".json") == true) wake.trySend(Unit) }
        }.apply { startWatching() }
        while (true) {
            requests.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.lastModified() }.forEach { f ->
                val j = runCatching { JSONObject(f.readText()) }.getOrNull()
                f.delete()
                val done = if (j == null) Done("The request wasn't valid JSON.", false) else currentModel?.let { model -> handle(context, model, currentItems(), j) } ?: Done("Return to Hatchlybox before retrying.", false)
                results.mkdirs()
                val id = f.name.removeSuffix(".json")
                File(results, "$id.tmp").apply { writeText(JSONObject().put("ok", done.ok).put("say", done.say).apply { done.image?.let { put("image", it) } }.toString()); renameTo(File(results, "$id.json")) }
            }
            kotlinx.coroutines.withTimeoutOrNull(1000) { wake.receive() }
        }
    }

    private suspend fun handle(context: Context, m: MakerModel, items: List<LibItem>, j: JSONObject): Done = withContext(Dispatchers.Main) {
        if (j.optBoolean("reload")) {
            CustomActions.load(context)
            return@withContext Done("Loaded. Actions agents made: " + Hands.custom.joinToString { it.name }.ifEmpty { "none (check /root/actions/<name>/action.json)" } + ".")
        }
        val name = j.optString("action")
        val h = Hands.all().firstOrNull { it.name == name && name !in hidden }
            ?: return@withContext Done("There's no action \"$name\". `phone list` lists them; to make one, see \"The phone's actions\" in your instructions.", false)
        val args = j.optJSONObject("args")?.let { o -> o.keys().asSequence().associateWith { k -> o.opt(k).takeIf { it != JSONObject.NULL } } }.orEmpty()
        val choice = Choice(h, args)
        val what = Act.describe(h, args)
        // With no owner check chosen, a secure action from an agent still waits for a yes.
        val yes = when {
            h.level == Level.OPEN -> true
            h.level == Level.SECURE && Guard.method != "none" -> Guard.verify("An AI helper asks: $what")
            else -> {
                val answer = CompletableDeferred<Boolean>(); asking = choice to answer
                try { kotlinx.coroutines.withTimeoutOrNull(110000) { answer.await() } ?: false }
                finally { if (asking?.second === answer) asking = null }
            }
        }
        if (!yes) ActionLog.add(context, what, "an AI helper", "Not confirmed", false)
        if (!yes) return@withContext Done("Not done: the user didn't confirm \"$what\". Don't ask again unless they say so.", false)
        val d = Act.run(context, m, h, args, items, by = "an AI helper")
        if (d.say.isBlank()) Done(if (d.ok) "Done: $what." else "Not done: $what.", d.ok) else d
    }
}

/** The yes an agent's request waits for. */
@Composable
fun PhoneAsk() {
    val (c, d) = PhoneBridge.asking ?: return
    val close = { yes: Boolean -> PhoneBridge.asking = null; d.complete(yes); Unit }
    AlertDialog(
        onDismissRequest = { close(false) },
        icon = { Icon(c.hand.icon, null, tint = Color(0xFFE2B33C)) },
        title = { Text("An AI helper asks to") },
        text = { Text(Act.describe(c.hand, c.args)) },
        confirmButton = { TextButton(onClick = { close(true) }) { Text("Do it") } },
        dismissButton = { TextButton(onClick = { close(false) }) { Text("Not now") } },
    )
}
