package com.hatchlybox.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.File

// ---------- One app: its conversation with the model ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(model: AppModel, project: Project) {
    BackHandler { model.screen = Page.Home }
    val chat = project.chat
    val agent = project.agent
    val status = model.status[agent] ?: Status.UNKNOWN
    var menu by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { model.screen = Page.Home }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to your apps") }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(project, size = 36)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${projectState(project)} · ${agent.label}",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            )
                        }
                    }
                },
                actions = {
                    if (project.hasApp) FilledTonalButton(onClick = { model.screen = Page.Run(project.id) }, enabled = !chat.running) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Open")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; renaming = true })
                            DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { menu = false; deleting = true })
                        }
                    }
                },
            )
        },
    ) { inner ->
        CenteredColumn(Modifier.padding(inner).consumeWindowInsets(inner).imePadding()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { ChatList(model, project) }
                if (status == Status.DISCONNECTED) ConnectBanner(model, agent)
                Composer(model, project, enabled = status != Status.MISSING && model.env == Env.Ready)
            }
        }
    }
    if (renaming) {
        var name by rememberSaveable { mutableStateOf(project.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename app") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(onClick = { model.rename(project, name); renaming = false }, enabled = name.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete ${project.name}?") },
        text = { Text("This deletes the app, its files and its conversation. It can't be undone.") },
        confirmButton = { TextButton(onClick = { deleting = false; model.delete(project) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ChatList(model: AppModel, project: Project) {
    val chat = project.chat
    val list = rememberLazyListState(initialFirstVisibleItemIndex = chat.entries.size)
    val scope = rememberCoroutineScope()
    // Follow new output only while the reader is at the bottom; scrolling up stops it, and the arrow resumes it.
    var follow by remember(chat) { mutableStateOf(true) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }.collect { (moving, more) -> if (moving) follow = !more }
    }
    val ready = project.hasApp && !chat.running
    val last = chat.entries.lastOrNull()
    val count = chat.entries.size + if (chat.running || ready) 1 else 0
    LaunchedEffect(count, last?.text?.length) {
        if (follow && count > 0) { list.scrollToItem(count - 1); list.scrollBy(100_000f) }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(chat.entries) { _, e ->
                when (e.kind) {
                    Kind.YOU -> PromptBubble(e.text)
                    Kind.OUT -> ReplyCard(project.agent, e.text)
                    Kind.INFO -> InfoLine(model, project.agent, e.text)
                }
            }
            if (chat.running) item { Working(project) }
            else if (ready) item { ReadyCard(model, project) }
        }
        AnimatedVisibility(
            visible = !follow && list.canScrollForward,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            SmallFloatingActionButton(onClick = {
                follow = true
                scope.launch { list.animateScrollToItem(maxOf(0, count - 1)); list.scrollBy(100_000f) }
            }) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Jump to the latest") }
        }
    }
}

@Composable
private fun ReadyCard(model: AppModel, project: Project) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppIcon(project, size = 40)
            Column(Modifier.weight(1f)) {
                Text("${project.name} is ready", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("Try it, then ask below for any changes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Button(onClick = { model.screen = Page.Run(project.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open") }
        }
    }
}

@Composable
private fun PromptBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
            modifier = Modifier.widthIn(max = 560.dp).padding(start = 48.dp),
        ) {
            SelectionContainer {
                Text(text, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun ReplyCard(agent: Agent, text: String) {
    val clipboard = LocalClipboardManager.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(agent.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy reply", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SelectionContainer(Modifier.padding(end = 10.dp)) {
                // Claude's replies are Markdown; the other CLIs print plain terminal text.
                ReplyBody(text, mono = agent != Agent.CLAUDE)
            }
        }
    }
}

@Composable
private fun InfoLine(model: AppModel, agent: Agent, text: String) {
    val signIn = text.contains("isn't connected")
    val error = signIn || text.startsWith("Exited")
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (error) Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
        Text(
            if (signIn) "${agent.label} isn't signed in" else text,
            style = MaterialTheme.typography.labelMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (signIn) TextButton(onClick = { model.connect(agent) }) { Text("Connect") }
    }
}

@Composable
private fun Working(project: Project) {
    val chat = project.chat
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val secs = ((now - chat.startedAt) / 1000).coerceAtLeast(0)
    val doing = if (project.hasApp || chat.entries.count { it.kind == Kind.YOU } > 1) "is working" else "is building your app"
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(start = 4.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            if (chat.stopping) "Stopping…" else "${project.agent.label} $doing · ${if (secs < 60) "${secs}s" else "${secs / 60}m ${secs % 60}s"}",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectBanner(model: AppModel, agent: Agent) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${agent.label} isn't connected", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { model.connect(agent) }) { Text("Connect") }
        }
    }
}

@Composable
private fun Composer(model: AppModel, project: Project, enabled: Boolean) {
    val chat = project.chat
    var text by rememberSaveable(project.id) { mutableStateOf("") }
    val canSend = enabled && !chat.running && text.isNotBlank()
    val send = { if (canSend) { model.send(project, text); text = "" } }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (project.hasApp) "Ask for changes…" else "Message ${project.agent.label}") },
                enabled = enabled,
                maxLines = 6,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            Box(Modifier.padding(bottom = 4.dp)) {
                if (chat.running) {
                    FilledIconButton(
                        onClick = { model.stop(project) }, enabled = !chat.stopping, modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    ) { Icon(Icons.Default.Stop, contentDescription = "Stop") }
                } else {
                    FilledIconButton(onClick = send, enabled = canSend, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

// ---------- Running the app ----------

// The app's files are served from a made-up https origin, one per app, so each gets its own
// storage and ES modules and fetch() of its own files work. Other links open in the browser.
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RunScreen(model: AppModel, project: Project) {
    val folder = remember(project.id) { model.appFolder(project).canonicalFile }
    WebRunner(folder, project.id, project.name, { AppIcon(project, size = 32) }) { model.screen = Page.App(project.id) }
}

/** A web app's folder in a WebView, served under its own private host (nothing else can read it). */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebRunner(folder: File, id: String, name: String, icon: @Composable () -> Unit, close: () -> Unit) {
    val host = "${id.lowercase().replace(Regex("[^a-z0-9-]"), "-")}.app.invalid"
    var web by remember { mutableStateOf<WebView?>(null) }
    BackHandler { web?.takeIf { it.canGoBack() }?.goBack() ?: close() }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Default.Close, contentDescription = "Close $name") } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        icon()
                        Spacer(Modifier.width(12.dp))
                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = { IconButton(onClick = { web?.reload() }) { Icon(Icons.Default.Refresh, contentDescription = "Reload") } },
            )
        },
    ) { inner ->
        AndroidView(
            modifier = Modifier.padding(inner).consumeWindowInsets(inner).imePadding().fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            if (request.url.host != host) return null
                            return serve(folder, request.url.path ?: "/")
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (request.url.host == host) return false
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                            return true
                        }
                    }
                    loadUrl("https://$host/index.html")
                    web = this
                }
            },
        )
    }
    DisposableEffect(id) { onDispose { web?.destroy(); web = null } }
}

private fun serve(folder: File, path: String): WebResourceResponse = try {
    serveFile(folder, path)
} catch (_: Exception) { WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0))) }

private fun serveFile(folder: File, path: String): WebResourceResponse {
    val rel = Uri.decode(path).trimStart('/').ifEmpty { "index.html" }.let { if (it.endsWith("/")) it + "index.html" else it }
    val file = File(folder, rel).canonicalFile
    if (!file.path.startsWith(folder.path + File.separator) || !file.isFile) {
        return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
    val ext = file.extension.lowercase()
    val mime = when (ext) {
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "html", "htm" -> "text/html"
        "json", "webmanifest" -> "application/json"
        "svg" -> "image/svg+xml"
        "wasm" -> "application/wasm"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
    val text = mime.startsWith("text/") || mime == "application/json" || mime == "image/svg+xml"
    return WebResourceResponse(mime, if (text) "utf-8" else null, 200, "OK", mapOf("Cache-Control" to "no-cache"), file.inputStream())
}
