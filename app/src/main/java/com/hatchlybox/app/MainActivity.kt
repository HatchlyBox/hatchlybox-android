package com.hatchlybox.nightly

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** Home button presses while Hatchlybox is the home screen: each one returns to the main screen. */
object HomeSignal { var presses by mutableIntStateOf(0) }

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(android.content.Intent.CATEGORY_HOME)) HomeSignal.presses++
        Sites.handle(this, intent)
        DeviceSync.link(intent)
        openChat(intent)
        openTask(intent)
    }
    /** The task notification: a tap shows the task console. */
    private fun openTask(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra("openTask", false) != true) return
        intent.removeExtra("openTask")
        if (AgentChat.running || !MakerRuntime.running && AgentChat.messages.isNotEmpty()) { AgentChat.load(this); AgentChat.show() } else MakerRuntime.opens++
    }
    /** The AI helper's answer notification: Open chat brings the chat up to add to the request or carry on. */
    private fun openChat(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra("openChat", false) != true) return
        intent.removeExtra("openChat")
        AgentChat.load(this); AgentChat.show()
        getSystemService(android.app.NotificationManager::class.java).cancel(AgentScreen.ANSWER_ID)
    }
    override fun onResume() { super.onResume(); Gold.front = true }
    override fun onPause() { Gold.front = false; super.onPause() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Crash.install(this)
        // Actions: permissions and the owner's check need this screen; agents' actions and the local model are read once.
        Front.attach(this); Guard.load(this); Assist.check(this); CustomActions.load(this); PhoneBridge.install(this); Reminders.rearm(this)
        // The app-wide state holders are created here, on the main thread before any screen: one first touched by a
        // background thread while the first screen is composing makes Compose stop ("state created after the snapshot").
        MakerRuntime.running; HomeSignal.presses; Setup.installing; Shells.count; AgentChat.running; TermInput.focused; DeviceSync.state
        Look.load(this)
        Sites.load(this)
        if (savedInstanceState == null) { Sites.handle(this, intent); DeviceSync.link(intent); openChat(intent); openTask(intent) }
        enableEdgeToEdge()
        val crash = Crash.last(this)
        setContent {
            AppTheme {
                // The phone's wallpaper behind the home screen, when that's the chosen background.
                val surface = MaterialTheme.colorScheme.surface
                LaunchedEffect(Look.background, surface) {
                    if (Look.background == "wallpaper") {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(surface.toArgb()))
                    }
                }
                // Light or dark can be forced in Settings: the system bars' icons follow the look, not the system.
                val dark = isDarkLook()
                LaunchedEffect(dark) {
                    val style = androidx.activity.SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                    // No grey scrim behind the three-button bar: the home background runs under it.
                    if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
                }
                var report by rememberSaveable { mutableStateOf(crash) }
                report?.let { CrashScreen(it) { Crash.clear(this@MainActivity); report = null }; return@AppTheme }
                var legacy by rememberSaveable { mutableStateOf(false) }
                // A web app opened from the main page: backing out of it returns there, not to the web library.
                var fromMaker by rememberSaveable { mutableStateOf(false) }
                val model: AppModel = viewModel()
                if (!legacy) {
                    // The main screen starts on the second frame, once the window is fully up (as it did after the start screen).
                    var shown by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; shown = true }
                    if (!shown) { Surface(Modifier.fillMaxSize(), color = if (Look.background == "wallpaper") Color.Transparent else MaterialTheme.colorScheme.surface) {}; return@AppTheme }
                    // The first start: what Hatchlybox downloads and sends, and Google's SDK licence, before anything happens.
                    if (!Legal.accepted(this@MainActivity)) { Welcome { }; return@AppTheme }
                    val maker: MakerModel = viewModel()
                    GemmaTerms()
                    LaunchedEffect(HomeSignal.presses) { if (HomeSignal.presses > 0) maker.goHome() }
                    // A web app from the browser, waiting for its name and icon to be confirmed.
                    SiteDialog(maker)
                    // Keep the phone awake while the tools install.
                    LaunchedEffect(maker.installing) {
                        if (maker.installing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    // Pictures for `widget check`, drawn out of sight at the size the widget has (or would have) at home.
                    WidgetChecks(maker.widgetEngine, maker.linux) { w, size ->
                        val placed = maker.launcher.pages.flatten().firstOrNull { it.kind == "custom" && it.ref == w.id }
                        when {
                            size.isBlank() && placed != null -> (placed.w to placed.h) to "its size on the home screen"
                            else -> maker.launcher.defaultSize("custom", size.ifBlank { w.size }) to "the ${size.ifBlank { w.size }} size"
                        }
                    }
                    MakerScreen(
                        maker, web = model, onLegacy = { fromMaker = false; model.screen = Page.Home; legacy = true },
                        openWeb = { p -> fromMaker = true; model.screen = Page.App(p.id); legacy = true },
                        createWeb = { idea ->
                            val agent = runCatching { Agent.valueOf(maker.agent.uppercase()) }.getOrDefault(Agent.CLAUDE)
                            if (model.ensureReady()) { model.choose(agent); model.build(idea); fromMaker = true; legacy = true; true } else false
                        },
                    )
                    return@AppTheme
                }
                BackHandler { legacy = false }
                LaunchedEffect(HomeSignal.presses) { if (HomeSignal.presses > 0) { fromMaker = false; legacy = false } }
                LaunchedEffect(model.screen, fromMaker) { if (fromMaker && model.screen == Page.Home) { fromMaker = false; legacy = false } }
                // Keep the phone awake while Linux and the tools install.
                LaunchedEffect(model.env) {
                    if (model.env == Env.Installing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    val console = model.console
                    if (console != null) ConsoleScreen(model, console)
                    else when (val s = model.screen) {
                        Page.Home -> HomeScreen(model)
                        Page.Settings -> SettingsScreen(model)
                        is Page.App -> model.project(s.id)?.let { ProjectScreen(model, it) } ?: LaunchedEffect(s) { model.screen = Page.Home }
                        is Page.Run -> model.project(s.id)?.let { RunScreen(model, it) } ?: LaunchedEffect(s) { model.screen = Page.Home }
                    }
                }
            }
        }
    }
}

private val Seed = Color(0xFF4858AB)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isDarkLook()
    val style = Look.current
    val colors = schemeFor(Look.palette, dark)
    val t = androidx.compose.material3.Typography()
    val f = style.family
    fun androidx.compose.ui.text.TextStyle.fam() = if (f != null) copy(fontFamily = f) else this
    val type = t.copy(
        displayLarge = t.displayLarge.fam(), displayMedium = t.displayMedium.fam(),
        displaySmall = t.displaySmall.copy(fontFamily = f ?: androidx.compose.ui.text.font.FontFamily.SansSerif, fontWeight = androidx.compose.ui.text.font.FontWeight.Light),
        headlineLarge = t.headlineLarge.fam(), headlineMedium = t.headlineMedium.fam(), headlineSmall = t.headlineSmall.fam(),
        titleLarge = t.titleLarge.fam(), titleMedium = t.titleMedium.fam(), titleSmall = t.titleSmall.fam(),
        bodyLarge = t.bodyLarge.fam(), bodyMedium = t.bodyMedium.fam(), bodySmall = t.bodySmall.fam(),
        labelLarge = t.labelLarge.fam(), labelMedium = t.labelMedium.copy(fontFeatureSettings = "tnum").fam(), labelSmall = t.labelSmall.fam(),
    )
    // The style's corners for every card, sheet and field (Old school: square).
    val c = style.corner
    val shapes = androidx.compose.material3.Shapes(
        extraSmall = RoundedCornerShape((c * 0.2f).dp), small = RoundedCornerShape((c * 0.45f).dp), medium = RoundedCornerShape((c * 0.62f).dp),
        large = RoundedCornerShape((c * 0.9f).dp), extraLarge = RoundedCornerShape(c.dp),
    )
    MaterialTheme(colorScheme = colors, typography = type, shapes = shapes) {
        androidx.compose.runtime.CompositionLocalProvider(LocalLook provides Look.shown, content = content)
    }
}

@Composable
fun CenteredColumn(modifier: Modifier = Modifier, maxWidth: Int = 760, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = maxWidth.dp).fillMaxSize()) { content() }
    }
}

fun statusText(status: Status, account: String, ready: Boolean) = when (status) {
    Status.CONNECTED -> "Connected"
    Status.DISCONNECTED -> "Not connected · $account"
    Status.MISSING -> "Not installed"
    Status.CHECKING -> "Checking…"
    Status.UNKNOWN -> if (ready) "Status unknown" else "Waiting for setup"
}

@Composable
fun StatusDot(status: Status) {
    val color = when (status) {
        Status.CONNECTED -> Color(0xFF2E9E5B)
        Status.DISCONNECTED, Status.MISSING -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Box(Modifier.size(8.dp).background(color, CircleShape))
}

// ---------- First-run setup ----------

private val SetupSteps = listOf(
    "Downloading Alpine Linux" to "Download Linux",
    "Installing Node.js" to "Install Node.js and tools",
    "Installing Codex" to "Install Codex",
    "Installing Claude Code" to "Install Claude Code",
    "Installing OpenCode" to "Install OpenCode",
)

@Composable
fun SetupWindow(model: AppModel, env: Env) {
    val log = model.setupLog
    val failed = env is Env.Failed
    // The step in progress is the last one whose marker has shown up in the log.
    val current = SetupSteps.indexOfLast { (marker, _) -> log.any { it.contains(marker) } }.coerceAtLeast(0)
    var details by rememberSaveable { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(log.size, details) { if (details && log.isNotEmpty()) list.scrollToItem(log.size - 1) }
    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (failed) "Setup didn't finish" else "Setting up Hatchlybox", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (failed) (env as Env.Failed).message
                else "This runs once and downloads about 300 MB. Keep the app open; the screen stays on until it's done.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!failed) LinearProgressIndicator(Modifier.fillMaxWidth())
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                SetupSteps.forEachIndexed { i, (_, title) ->
                    val state = when {
                        i < current -> 2
                        i == current && env == Env.Installing -> 1
                        i == current && failed -> 3
                        else -> 0
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                            when (state) {
                                2 -> Icon(Icons.Default.CheckCircle, contentDescription = "Done", tint = MaterialTheme.colorScheme.primary)
                                1 -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                3 -> Icon(Icons.Outlined.ErrorOutline, contentDescription = "Failed", tint = MaterialTheme.colorScheme.error)
                                else -> Icon(Icons.Outlined.RadioButtonUnchecked, contentDescription = "Not started", tint = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                        Text(title, style = MaterialTheme.typography.bodyLarge, color = if (state == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (failed) Button(onClick = model::install) { Text("Try again") }
            if (env == Env.Missing) Button(onClick = model::install) { Text("Start setup") }
            TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Show details") }
        }
        if (details) Terminal(Modifier.weight(1f)) {
            SelectionContainer {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                    itemsIndexed(log) { _, line -> MonoText(line, if (line.startsWith("!")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
                }
            }
        }
    }
}

@Composable
fun Terminal(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, content = content)
}

// ---------- Settings ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(model: AppModel) {
    BackHandler { model.screen = Page.Home }
    var confirmReset by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.refreshStatus() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { model.screen = Page.Home }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = model::refreshStatus, enabled = model.env == Env.Ready) {
                        Icon(Icons.Default.Refresh, contentDescription = "Check connections")
                    }
                },
            )
        },
    ) { inner ->
        CenteredColumn(Modifier.padding(inner)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Models")
                Agent.entries.forEach { AgentCard(model, it) }
                Spacer(Modifier.size(8.dp))
                SectionTitle("Linux environment")
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            when (val env = model.env) {
                                Env.Ready -> "Alpine Linux is ready"
                                Env.Installing -> "Installing…"
                                Env.Missing -> "Not installed"
                                is Env.Failed -> "Setup failed: ${env.message}"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Update tools installs the latest Claude Code, Codex and OpenCode. Reset deletes everything, including the apps you built, and starts over.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { model.install(); model.screen = Page.Home }, enabled = model.env != Env.Installing && !MakerRuntime.running) { Text("Update tools") }
                            OutlinedButton(onClick = { confirmReset = true }, enabled = model.env != Env.Installing && !MakerRuntime.running) { Text("Reset") }
                        }
                    }
                }
            }
        }
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset Linux?") },
        text = { Text("This deletes the Linux environment, your sign-ins and the files of every app you built, then installs everything again. The conversations stay in your library.") },
        confirmButton = { TextButton(onClick = { confirmReset = false; model.resetEnvironment(); model.screen = Page.Home }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp))
}

@Composable
private fun AgentCard(model: AppModel, agent: Agent) {
    val status = model.status[agent] ?: Status.UNKNOWN
    val ready = model.env == Env.Ready
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusDot(status)
            Column(Modifier.weight(1f)) {
                Text(agent.label, style = MaterialTheme.typography.titleMedium)
                Text(statusText(status, agent.account, ready), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (status) {
                Status.CONNECTED -> OutlinedButton(onClick = { model.disconnect(agent) }) { Text("Disconnect") }
                Status.CHECKING -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else -> Button(onClick = { model.connect(agent) }, enabled = ready && status != Status.MISSING) { Text("Connect") }
            }
        }
    }
}

// ---------- Sign-in console ----------

// The CLI's own sign-in screen: its output, links to open, and keys to answer its prompts.
// It's a full screen in the activity, not a dialog, so the keyboard shrinks it instead of covering the bottom.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsoleScreen(model: AppModel, console: Console) {
    BackHandler(onBack = model::closeConsole)
    val clipboard = LocalClipboardManager.current
    var input by rememberSaveable { mutableStateOf("") }
    val scroll = rememberScrollState()
    // Follow new output only while the reader is at the bottom; scrolling up stops it, and the arrow resumes it.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress to scroll.canScrollForward }.collect { (moving, more) -> if (moving) follow = !more }
    }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.maxValue }.collect { max ->
            // Never fight the reader's finger: a scroll while they drag would be refused and end this effect.
            if (follow && !scroll.isScrollInProgress) {
                try { scroll.scrollTo(max) } catch (e: CancellationException) { coroutineContext.ensureActive() }
            }
        }
    }
    val scope = rememberCoroutineScope()
    val submit = { console.send(input + "\r"); input = "" }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(console.title) },
                navigationIcon = { IconButton(onClick = model::closeConsole) { Icon(Icons.Default.Close, contentDescription = "Close") } },
            )
        },
    ) { inner ->
        CenteredColumn(Modifier.padding(inner).consumeWindowInsets(inner).imePadding()) {
            Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (console.done) "Finished. Check the status in Settings."
                    else "Finish signing in in your browser, then paste the code it gives you below.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Terminal(Modifier.weight(1f).heightIn(min = 120.dp)) {
                    Box {
                        SelectionContainer {
                            Box(Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp)) {
                                MonoText(console.text.ifEmpty { "Starting ${console.agent.label}…" })
                            }
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            visible = scroll.canScrollForward && !follow,
                            enter = fadeIn(), exit = fadeOut(),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                        ) {
                            SmallFloatingActionButton(onClick = { follow = true; scope.launch { scroll.animateScrollTo(scroll.maxValue) } }) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Scroll to the end")
                            }
                        }
                    }
                }
                if (console.urls.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        console.urls.forEach { url ->
                            val host = runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: "link"
                            AssistChip(
                                onClick = { model.openUrl(url) },
                                label = { Text("Open $host") },
                                leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            )
                        }
                    }
                }
                if (console.done) {
                    Button(onClick = model::closeConsole, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Done") }
                } else {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KeyButton("↑", "Up") { console.send("\u001B[A") }
                        KeyButton("↓", "Down") { console.send("\u001B[B") }
                        KeyButton("Enter", "Enter") { console.send("\r") }
                        KeyButton("Esc", "Escape") { console.send("\u001B") }
                        KeyButton("Paste", "Paste clipboard") { clipboard.getText()?.text?.let { input += it.trim() } }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("Type or paste a code") },
                            shape = RoundedCornerShape(24.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { submit() }),
                        )
                        FilledIconButton(onClick = submit, modifier = Modifier.size(52.dp)) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send to ${console.agent.label}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyButton(label: String, description: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) { Text(label) }
}
