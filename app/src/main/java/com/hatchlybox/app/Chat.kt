package com.hatchlybox.nightly

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Just talking to an agent: the create box's Chat mode. One conversation, kept on disk, run by the process
// (not a screen), so it carries on while you look elsewhere. Each turn sends the recent conversation along,
// which works the same for Claude Code, Codex and OpenCode.

class ChatMsg(val mine: Boolean, text: String, val agent: String) { var text by mutableStateOf(text) }

object AgentChat {
    val messages = mutableStateListOf<ChatMsg>()
    var running by mutableStateOf(false)
    /** The chat window is up (over the home screen or the library). */
    var shown by mutableStateOf(false)
    /** The pill that brings it back was put away; the next message brings it back. */
    var tucked by mutableStateOf(false)
    /** An answer came while the window was down. */
    var unread by mutableStateOf(false)
    fun show() { shown = true; tucked = false; unread = false }
    @Volatile private var process: java.lang.Process? = null
    private var loaded = false
    @Volatile private var cancelled = false
    var stopping by mutableStateOf(false)
    private var taskId = ""
    private var lastCheckpoint = 0L
    private fun file(context: Context) = File(context.filesDir, "chat.json")

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val a = JSONArray(SafeFiles.read(file(context)))
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { messages += ChatMsg(it.optBoolean("mine"), it.optString("text"), it.optString("agent")) }
        }.onFailure { if (file(context).exists()) messages += ChatMsg(false, "Chat history could not be read; its file was kept. Check Storage before clearing it.", "system") }
    }

    private fun save(context: Context) = runCatching {
        SafeFiles.write(file(context), JSONArray(messages.takeLast(200).map { JSONObject().put("mine", it.mine).put("text", it.text).put("agent", it.agent) }).toString())
    }

    /** display: what the chat shows of it (a long instruction can show as a short line). */
    fun send(context: Context, agent: Agent, text: String, display: String = text): Boolean {
        val app = context.applicationContext
        load(app)
        if (running || text.isBlank() || SharedTools.updating || MakerRuntime.running || Setup.installing && !Linux(app).hasAgents) return false
        val linux = Linux(app)
        val earlier = messages.takeLast(16).joinToString("\n\n") { (if (it.mine) "User: " else "${it.agent}: ") + it.text.take(4000) }.takeLast(14000)
        tucked = false
        AgentScreen.fresh()
        messages += ChatMsg(true, display.trim(), agent.bin)
        val out = ChatMsg(false, "", agent.bin)
        messages += out
        if (!linux.hasAgents) { out.text = "! The AI helpers are still installing. Your request was not started."; save(app); return false }
        running = true; cancelled = false; stopping = false
        taskId = TaskHistory.begin(app, display, "agent:${agent.bin}")
        if (taskId.isBlank()) { running = false; out.text = "Could not save task state. Your draft is kept; free storage and retry."; return false }
        while (messages.size > 200) messages.removeAt(0)
        runCatching { app.startForegroundService(Intent(app, ChatTaskService::class.java)) }.onFailure {
            running = false; out.text = "Could not start background work: ${it.message}. Your request was not started."
            TaskHistory.finish(taskId, "failed", out.text); save(app); return false
        }
        save(app)
        val prompt = "You are chatting with the user on their phone, inside Hatchlybox (an app that makes apps, games and widgets with coding agents). " +
            "Answer conversationally and to the point, in Markdown. You may use tools when a question needs them, but don't change files outside /root/chat unless asked. " +
            "To do something on the phone (open or close an app, text, call, set a reminder…), use the `phone` command from your instructions. " +
            "When you use the screen (phone screen …), carry the task through to its result yourself; ask the user only when you truly can't go on. " +
            "Finish with `phone screen done --say \"<the answer in a sentence or two>\"`: it hands the screen back; end your reply with that answer, the chat shows it." +
            "\nPinned user requirements: " + app.getSharedPreferences("maker", 0).getString("draft-chat-context", "").orEmpty().take(4000) +
            "\nRecent history may be incomplete. Ask when an essential earlier requirement is missing; do not invent it." +
            (if (earlier.isNotBlank()) "\n\nThe conversation so far:\n\n$earlier" else "") + "\n\nUser: ${text.trim()}"
        // Codex prints its progress on stderr and the answer on stdout: only the answer belongs in a chat.
        val command = agent.runCommand(false)
        val started = System.currentTimeMillis()
        Setup.scope.launch(Dispatchers.IO) {
            var code = -1
            try {
                linux.hostFile("/root/chat").mkdirs()
                val p = linux.start(command, mapOf("PROMPT" to prompt), cwd = "/root/chat")
                process = p
                if (cancelled) p.destroy()
                p.outputStream.close()
                p.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        val rendered = agent.render(line)?.takeIf { it.isNotBlank() } ?: continue
                        withContext(Dispatchers.Main) {
                            out.text = (if (out.text.isEmpty()) rendered else out.text + "\n" + rendered).takeLast(50000)
                            if (System.currentTimeMillis() - lastCheckpoint > 3000) { save(app); lastCheckpoint = System.currentTimeMillis() }
                        }
                    }
                }
                code = p.waitFor()
            } catch (e: Exception) { withContext(Dispatchers.Main) { out.text += "\n! ${e.message}" } }
            process?.destroy(); process = null
            withContext(Dispatchers.Main) {
                if (cancelled) out.text += "\n\nStopped. Check any phone action already performed before retrying."
                else if (code != 0) out.text += "\n\nTask failed (exit $code). Partial output is shown above; check connection and retry only after reviewing any actions."
                else if (out.text.isBlank()) out.text = "The agent exited without an answer. Completion is unverified."
                TaskHistory.finish(taskId, if (cancelled) "cancelled" else if (code == 0) "agent-finished" else "failed", out.text.takeLast(2000))
                running = false; stopping = false
                if (!shown) unread = true
                save(app)
                // It used the screen: the screen goes back to the user, and the answer comes as a notification.
                if (AgentScreen.lastUse >= started) AgentScreen.finishTurn(app, out.text)
            }
        }
        return true
    }

    fun stop() { cancelled = true; stopping = running; process?.destroy(); AgentScreen.answered(AgentScreen.askNo, "") }

    fun clear(context: Context) { if (!running && loaded) {
        if (messages.isNotEmpty()) {
            val archive = File(context.filesDir, "chat-archive/${System.currentTimeMillis()}.json")
            runCatching { SafeFiles.write(archive, JSONArray(messages.map { JSONObject().put("mine", it.mine).put("text", it.text).put("agent", it.agent) }).toString()) }.onFailure { return }
        }
        messages.clear(); save(context.applicationContext)
    } }
    fun archives(context: Context): List<File> = File(context.filesDir, "chat-archive").listFiles().orEmpty().filter { it.extension == "json" }.sortedByDescending { it.name }
    fun restore(context: Context, archive: File) {
        if (running || archive.canonicalFile.parentFile != File(context.filesDir, "chat-archive").canonicalFile) return
        val rows = runCatching { JSONArray(SafeFiles.read(archive)).objects() }.getOrNull() ?: return
        clear(context); messages.clear(); rows.takeLast(200).forEach { messages += ChatMsg(it.optBoolean("mine"), it.text("text"), it.text("agent")) }; save(context)
    }
}

/** The chat page: your messages on the right, the agent's answers as text, a box at the bottom. */
@Composable
fun ChatScreen(m: MakerModel) = ChatBody(m, Modifier.fillMaxSize())

/**
 * The chat as a window over the home screen and the library, in the theme's card style: the agent's name,
 * New chat and a button that puts it away (Back and a tap outside do too). It stays until the chat is
 * cleared: a small pill above the create box brings it back.
 */
@Composable
fun ChatWindow(m: MakerModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val look = LocalLook.current
    androidx.activity.compose.BackHandler { AgentChat.shown = false }
    Column(
        modifier.fillMaxWidth().fillMaxHeight(0.74f).lookFrame(null)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = maxOf(0.92f, look.cardOpacity)))
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {},
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            WidgetHeader("Chat · ${agentName(m.agent)}", Icons.Outlined.Forum, modifier = Modifier.weight(1f))
            if (AgentChat.messages.isNotEmpty()) IconButton(onClick = { AgentChat.clear(context) }, enabled = !AgentChat.running) {
                Icon(Icons.Outlined.AddComment, "New chat", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { AgentChat.shown = false }) { Icon(Icons.Default.KeyboardArrowDown, "Put the chat away", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        ChatBody(m, Modifier.weight(1f).fillMaxWidth())
    }
}

/** The pill above the create box while a chat is put away: tap to bring it back, ✕ to put the pill away. */
@Composable
fun ChatPill(m: MakerModel) {
    val look = LocalLook.current
    val shape = RoundedCornerShape(minOf(look.corner, 18f).dp)
    Row(
        // An answer waiting for the user: gold light runs round it until they open it.
        Modifier.goldShine(AgentChat.unread, minOf(look.corner, 18f).dp).clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = maxOf(0.8f, look.cardOpacity)))
            .border(1.dp, if (AgentChat.unread) Color(0xFFE8B84A) else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClickLabel = "Open the chat") { AgentChat.show() }.padding(start = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (AgentChat.running) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Color(0xFFE8B84A))
        else Icon(Icons.Outlined.Forum, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            when { AgentChat.running -> "${agentName(m.agent)} is answering"; AgentChat.unread -> "New answer from ${agentName(m.agent)}"; else -> "Chat with ${agentName(m.agent)}" },
            style = MaterialTheme.typography.labelMedium, color = if (AgentChat.unread) Color(0xFFE8B84A) else MaterialTheme.colorScheme.onSurface,
        )
        IconButton(onClick = { AgentChat.tucked = true }, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.Close, "Hide this", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** The conversation and its box, for the page and the window. */
@Composable
private fun ChatBody(m: MakerModel, modifier: Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { AgentChat.load(context) }
    val clipboard = LocalClipboardManager.current
    var contextEditor by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(m.draft("chat-context")) }
    if (contextEditor) AlertDialog(onDismissRequest = { contextEditor = false }, title = { Text("Pinned requirements") }, text = { OutlinedTextField(pinned, { pinned = it.take(4000) }, label = { Text("Keep these requirements in every turn") }, minLines = 4) }, confirmButton = { TextButton(onClick = { m.saveDraft("chat-context", pinned); contextEditor = false }) { Text("Save") } })
    var text by rememberSaveable { mutableStateOf(m.draft("chat")) }
    LaunchedEffect(text) { m.saveDraft("chat", text) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val last = AgentChat.messages.lastOrNull()?.text?.length ?: 0
    val follow by remember { derivedStateOf { !list.canScrollForward } }
    LaunchedEffect(AgentChat.messages.size, last) { if (follow && AgentChat.messages.isNotEmpty()) list.scrollToItem(AgentChat.messages.lastIndex, Int.MAX_VALUE / 2) }
    val agent = runCatching { Agent.valueOf(m.agent.uppercase()) }.getOrDefault(Agent.CLAUDE)
    val send = {
        if (!m.agentOk(m.agent)) m.connect(m.agent)
        else if (text.isNotBlank() && !AgentChat.running) { if (AgentChat.send(context, agent, text)) text = "" }
    }
    Column(modifier) {
        TextButton(onClick = { contextEditor = true }) { Text(if (pinned.isBlank()) "Pin requirements" else "Edit pinned requirements") }
        if (AgentChat.messages.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Forum, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(8.dp))
            Text("Ask ${agentName(m.agent)} anything", style = MaterialTheme.typography.titleMedium)
            Muted("Ideas, questions, help with an app. The conversation stays here until you start a new one.")
        } else LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(AgentChat.messages) { i, msg ->
                if (msg.mine) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp), modifier = Modifier.widthIn(max = 320.dp)) {
                        SelectionContainer { Text(msg.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium) }
                    }
                } else Column(Modifier.fillMaxWidth()) {
                    Text(agentName(msg.agent), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    if (msg.text.isEmpty() && AgentChat.running && i == AgentChat.messages.lastIndex) LinearProgressIndicator(Modifier.width(48.dp).padding(vertical = 8.dp))
                    else {
                        SelectionContainer { ReplyBody(msg.text, mono = false) }
                        if (!(AgentChat.running && i == AgentChat.messages.lastIndex)) IconButton(onClick = { clipboard.setText(AnnotatedString(msg.text)) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Outlined.ContentCopy, "Copy the answer", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (!follow && AgentChat.messages.isNotEmpty()) TextButton(onClick = { scope.launch { list.scrollToItem(AgentChat.messages.lastIndex, Int.MAX_VALUE / 2) } }) { Text("Jump to latest") }
        AgentQuestionCard()
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, maxLines = 5, shape = RoundedCornerShape(24.dp),
                placeholder = { Text("Message ${agentName(m.agent)}") }, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            if (AgentChat.running) FilledTonalIconButton(onClick = AgentChat::stop, enabled = !AgentChat.stopping, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Stop, "Stop the answer") }
            else FilledIconButton(onClick = send, enabled = text.isNotBlank(), modifier = Modifier.size(48.dp)) { Icon(Icons.Default.ArrowUpward, "Send") }
        }
    }
}
