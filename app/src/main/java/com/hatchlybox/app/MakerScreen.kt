@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.hatchlybox.nightly

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.CancellationException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.animation.togetherWith
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.json.*

// Hatchlybox follows Hatchlybox: a home page to create an app and find yours, a page per app with its
// status and main action on top, improving it as the main thing, and its details, settings and Google
// Play folded below; settings for the whole box on their own page.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MakerScreen(
    model: MakerModel = viewModel(), web: AppModel, onLegacy: () -> Unit,
    openWeb: (Project) -> Unit, createWeb: (String) -> Boolean,
) {
    val context = LocalContext.current
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.markNotificationsAsked(); model.checkPermissions() }
    // Permissions are checked at start and every time Hatchlybox comes back (from the system settings, say).
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { model.checkPermissions(); model.checkAgents(); model.checkHome() } }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    /** Asks for one permission: the system prompt the first time, its settings page after that. */
    val allow: (String) -> Unit = { which ->
        runCatching {
            when {
                which == "installs" -> context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                Build.VERSION.SDK_INT >= 33 && !model.notificationsAsked -> notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else -> context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        }.onFailure { context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
    }
    LaunchedEffect(MakerRuntime.running, MakerRuntime.code) {
        val result = MakerRuntime.code
        if (!MakerRuntime.running && MakerRuntime.title.startsWith("Connect ")) { model.connectEnded(); return@LaunchedEffect }
        if (!MakerRuntime.running && MakerRuntime.title.startsWith("Sign out ")) { model.checkAgents() }
        if (!MakerRuntime.running && model.themeTask) { model.themeEnded(result); return@LaunchedEffect }
        if (!MakerRuntime.running && result != null) {
            // A failure says why in its own words: the task's last line of output, when it has one.
            val why = MakerRuntime.output.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.take(160)
            model.notice = if (result == 0) "${MakerRuntime.title} finished"
                else "${MakerRuntime.title} stopped" + (why?.let { ": $it" } ?: " with code $result") + ". The task console has the details."
        }
    }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val ask: (String, () -> Unit) -> Unit = { text, action -> confirm = text to action }
    // Becoming the home screen: Android's own "default home app" question (Android 10+), else its settings page.
    val scope = rememberCoroutineScope()
    val homeRole = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.checkHome()
        // Declined: the home entry goes again, so the Home button doesn't keep asking which launcher to use.
        if (!model.isHome) model.enableHomeAlias(false)
    }
    val requestHome: () -> Unit = {
        model.markLauncherAsked()
        if (model.enableHomeAlias(true)) scope.launch {
            delay(400) // Android takes a moment to see the newly enabled home entry.
            val roles = if (Build.VERSION.SDK_INT >= 29) context.getSystemService(android.app.role.RoleManager::class.java) else null
            val asked = roles != null && runCatching {
                if (roles.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) && !roles.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) {
                    homeRole.launch(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME)); true
                } else false
            }.getOrDefault(false)
            if (!asked) { model.checkHome(); if (!model.isHome) model.chooseHome() }
        }
    }
    // Messages step aside by themselves after a while, like a snackbar.
    LaunchedEffect(model.notice) { if (model.notice.isNotBlank()) { delay(8000); model.notice = "" } }
    // A tap on the task's notification: its console, with the agent's output.
    LaunchedEffect(MakerRuntime.opens) { if (MakerRuntime.opens > 0) { MakerRuntime.opens = 0; model.library = false; model.showConsole = true } }
    val back: () -> Unit = {
        when {
            model.showConsole -> model.showConsole = false
            model.library -> model.library = false
            model.page == "job" && model.app != null -> model.page = "app"
            else -> model.page = "apps"
        }
    }
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = model.showConsole || model.library || model.page != "apps") { events ->
        try { events.collect { backProgress = it.progress }; back(); backProgress = 0f }
        catch (_: CancellationException) { backProgress = 0f }
    }
    BackHandler(enabled = model.page == "apps" && !model.library && !model.showConsole && (model.homePage != 0 || model.isHome)) { model.homePage = 0 }
    LaunchedEffect(model.page, model.selected) { model.saveNavigation() }
    LaunchedEffect(model.agentsInstalled) { model.checkAgents() }
    // While an agent connects, its sign-in page opens by itself (once per link).
    var opened by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(model.links, model.connectingAgent) {
        val link = model.links.lastOrNull()
        if (model.connectingAgent.isNotBlank() && MakerRuntime.running && link != null && link != opened) { opened = link; model.external(link) }
    }
    // A creation's web version, full screen over everything (Back or ✕ closes it).
    model.webRun?.let { slug ->
        val label = model.creations.firstOrNull { it.text("slug") == slug }?.text("label", slug) ?: slug
        androidx.compose.ui.window.Dialog(onDismissRequest = { model.webRun = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            WebRunner(remember(slug) { model.webFolder(slug).canonicalFile }, "made-$slug", label, {}) { model.webRun = null }
        }
    }
    MaterialTheme {
        // The home screen and the library draw their own top: no bar there, the page goes edge to edge.
        val onHome = model.page == "apps" && !model.showConsole
        Scaffold(
            // The home screen paints its own background (the theme's glow, or the wallpaper shows through).
            containerColor = if (onHome && Look.background != "plain" && (Look.background != "image" || Look.image != null)) Color.Transparent else MaterialTheme.colorScheme.background,
            modifier = when {
                onHome && Look.background == "theme" -> Modifier.artBackdrop(MaterialTheme.colorScheme, Look.artNow, Look.palette.tones(isDarkLook()))
                onHome && Look.background == "wallpaper" -> Modifier.wallpaperScrim()
                onHome && Look.background == "image" && Look.image != null -> Modifier.photoBackdrop(Look.image!!)
                else -> Modifier
            },
            topBar = {
                if (!onHome) TopAppBar(
                    title = {
                        Text(
                            when {
                                model.showConsole -> MakerRuntime.title.ifBlank { "Task" }
                                model.page == "app" -> model.app?.text("label", model.selected) ?: "App"
                                model.page == "settings" -> "Settings"
                                model.page == "chat" -> "Chat · ${agentName(model.agent)}"
                                model.page == "job" -> "Progress"
                                model.library -> "Library"
                                else -> ""
                            }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = { if (model.page != "apps" || model.showConsole || model.library) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = {
                        if (model.page == "chat" && !model.showConsole && AgentChat.messages.isNotEmpty()) TextButton(onClick = { AgentChat.clear(context) }, enabled = !AgentChat.running) { Text("New chat") }
                        if (MakerRuntime.running && !model.showConsole) IconButton(onClick = { model.showConsole = true }) { Icon(Icons.Outlined.Terminal, "Task console") }
                        if (model.page == "apps" && !model.showConsole && !model.library) IconButton(onClick = { model.page = "settings" }) { Icon(Icons.Outlined.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, scrolledContainerColor = MaterialTheme.colorScheme.surface),
                )
            },
        ) { inner ->
            // The home screen stays composed (unplaced: not drawn, not touchable) while another page is open, so its
            // widgets don't rebuild and blink when you come back. It keeps the padding it had when shown, so nothing
            // in it is resized meanwhile.
            var homePad by remember { mutableStateOf(inner) }
            if (onHome) homePad = inner
            CenteredColumn(
                Modifier.graphicsLayer { alpha = 1f - backProgress * 0.12f; translationX = backProgress * 24.dp.toPx() }
                    .layout { measurable, constraints -> val p = measurable.measure(constraints); layout(p.width, p.height) { if (onHome) p.place(0, 0) } }
                    .padding(homePad).consumeWindowInsets(homePad).imePadding(), maxWidth = 760,
            ) { LauncherHome(model, web, openWeb, createWeb, allow) }
            if (!onHome) CenteredColumn(Modifier.graphicsLayer { alpha = 1f - backProgress * 0.12f; translationX = backProgress * 24.dp.toPx() }.padding(inner).consumeWindowInsets(inner).imePadding(), maxWidth = 760) {
                if (model.showConsole) MakerConsole(model)
                else if (model.page == "chat") ChatScreen(model)
                else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Messages(model)
                    when (model.page) {
                        "job" -> MakerProgress(model, ask)
                        "settings" -> MakerSettings(model, ask, allow, requestHome)
                        "app" -> model.app?.let { MakerApp(model, it, ask, createWeb) }
                            ?: model.creation?.let { CreationPage(model, it) }
                            ?: Text("This app is no longer in your library.")
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        // The first start asks once: Hatchlybox as the phone's home screen?
        if (!model.launcherAsked && !model.isHome && model.page == "apps") AlertDialog(
            onDismissRequest = { model.markLauncherAsked() },
            icon = { Icon(Icons.Outlined.Home, null) },
            title = { Text("Make Hatchlybox your home screen?") },
            text = { Text("The Home button brings you here: your apps, a clock, a terminal, widgets you can place anywhere, and a box to make new apps. The tools keep installing in the background either way. You can change this in Settings.") },
            confirmButton = { TextButton(onClick = requestHome) { Text("Set as home") } },
            dismissButton = { TextButton(onClick = { model.markLauncherAsked() }) { Text("Not now") } },
        )
        confirm?.let { (text, action) ->
            AlertDialog(
                onDismissRequest = { confirm = null }, title = { Text("Are you sure?") }, text = { Text(text) },
                confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text("Confirm") } },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
            )
        }
    }
}

// ---------- building blocks ----------

/** A finished task's message and the links it printed, on top of any page. */
/** An action about to run: what it is, the seconds left as a shrinking line, Undo and Do it now. */
@Composable
private fun WaitingAction(w: Act.Waiting) {
    var left by remember(w) { mutableFloatStateOf(1f) }
    LaunchedEffect(w) { while (true) { left = ((w.until - System.currentTimeMillis()).toFloat() / w.ms).coerceIn(0f, 1f); if (left <= 0f) break; delay(50) } }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(w.icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
                Text(w.what, modifier = Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                TextButton(onClick = { w.answer.complete(false) }) { Text("Undo") }
                TextButton(onClick = { w.answer.complete(true) }) { Text("Do it now") }
            }
            LinearProgressIndicator(progress = { left }, modifier = Modifier.fillMaxWidth().height(2.dp), drawStopIndicator = {})
        }
    }
}

@Composable
internal fun Messages(model: MakerModel) {
    Act.waiting.forEach { key(it) { WaitingAction(it) } }
    if (model.notice.isNotBlank()) Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().goldTarget(Gold.ANSWER, 12.dp)) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(model.notice, modifier = Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = { model.notice = "" }) { Icon(Icons.Default.Close, "Dismiss message") }
        }
    }
    if (model.links.isNotEmpty()) Panel("Links from your task") {
        model.links.forEach { url -> TextButton(onClick = { model.external(url) }) { Text(url, maxLines = 3) } }
        TextButton(onClick = { model.links = emptyList() }) { Text("Dismiss links") }
    }
}

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** A group of settings, as a small coloured title. */
@Composable
private fun SettingsHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 8.dp).semantics { heading() })
}

/** A section that opens on tap: an icon, its title and a one-line summary, then its content. */
@Composable
private fun Fold(title: String, summary: String, icon: ImageVector, key: String = title, open: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val view = LocalView.current
    var expanded by rememberSaveable(key) { mutableStateOf(open) }
    ElevatedCard(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded; view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "Close $title" else "Open $title")
        }
        if (expanded) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun Actions(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}
@Composable
private fun Act(text: String, enabled: Boolean = true, action: () -> Unit) { OutlinedButton(onClick = action, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(text) } }
@Composable
private fun Field(label: String, value: String, changed: (String) -> Unit, lines: Int = 1) {
    OutlinedTextField(value = value, onValueChange = changed, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), minLines = lines, maxLines = if (lines > 1) 8 else 1, singleLine = lines == 1, shape = MaterialTheme.shapes.medium)
}
@Composable
internal fun Muted(text: String) { Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable
private fun Fact(key: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
@Composable
private fun Agents(model: MakerModel) {
    Actions { listOf("claude", "codex", "opencode").forEach { a -> FilterChip(selected = model.agent == a, onClick = { model.chooseAgent(a) }, label = { Text(agentName(a)) }) } }
}

@Composable
private fun toneColor(tone: String): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (tone) {
        "red" -> MaterialTheme.colorScheme.error
        "yellow" -> if (dark) Color(0xFFFFC857) else Color(0xFF9A6700)
        "green" -> if (dark) Color(0xFF6DD58C) else Color(0xFF1E7B44)
        "cyan" -> if (dark) Color(0xFF5ED4E6) else Color(0xFF00687A)
        "accent" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
private fun Color.luminance() = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** The status word in a soft pill of its colour. */
@Composable
private fun StatusPill(s: AppStatus) {
    val c = toneColor(s.tone)
    Surface(color = c.copy(alpha = 0.14f), shape = CircleShape) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (s.key == "working") CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = c)
            else Box(Modifier.size(8.dp).background(c, CircleShape))
            Text(s.word, style = MaterialTheme.typography.labelMedium, color = c, maxLines = 1)
        }
    }
}

@Composable
private fun AppIcon(m: MakerModel, a: JSONObject, size: Int) {
    val bitmap = remember(a.text("icon"), a.text("pkg"), m.apps) { m.icon(a) }
    val shape = RoundedCornerShape((size / 4.5).dp)
    if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.size(size.dp).clip(shape))
    else Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = shape, modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) { Text(a.text("label", a.text("slug", "A")).take(1).uppercase(), style = if (size > 48) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer) }
    }
}

/** One thing that can be done with an app, by its name in the status's actions. */
@Composable
private fun AppAction(m: MakerModel, a: JSONObject, s: AppStatus, id: String, primary: Boolean, ask: (String, () -> Unit) -> Unit) {
    val slug = a.text("slug")
    val idle = !m.busy && !MakerRuntime.running && !m.installing && !m.godotInstalling
    val act: Triple<String, Boolean, () -> Unit> = when (id) {
        "open" -> Triple("Open", true, { m.launchApp(a) })
        "install" -> Triple(if (s.key == "update") "Update" else "Install", a.has("apk"), { m.artifact(a) })
        "confirm" -> Triple("It's installed", idle, { m.action("Mark as installed", listOf("installed", slug)) })
        "download" -> Triple("Share APK", a.has("apk"), { m.artifact(a, share = true) })
        "retry" -> Triple("Try again", idle && m.ready, { m.action("Retry ${a.text("label", slug)}", listOf("retry", slug, m.agent)); m.page = "job" })
        "rebuild" -> Triple("Rebuild", idle, { m.action("Build ${a.text("label", slug)}", listOf("build", slug)); m.page = "job" })
        "fix" -> Triple("Fix the crash", idle && m.ready, { m.action("Fix ${a.text("label", slug)}", listOf("change", slug, m.agent, "The app closes straight after it starts. Find the cause (hatchlybox peek shows the crash) and fix it.")); m.page = "job" })
        "log" -> Triple("See progress", true, { m.selected = slug; m.page = "job" })
        "stop" -> Triple("Stop", m.busy, { ask("Stop the current task? Its log and the work so far stay.") { m.cancel() } })
        "review" -> Triple("Check the change", true, { m.openApp(a) })
        "improve" -> Triple("Improve", true, { m.openApp(a) })
        "publish" -> Triple("Google Play", true, { m.openApp(a, play = true) })
        else -> return
    }
    val (label, enabled, run) = act
    if (primary) Button(onClick = run, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
    else OutlinedButton(onClick = run, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
}

/** The box's placeholder: example ideas, one after another, with a soft fade and rise. */
@Composable
internal fun ExamplePrompt(web: Boolean, style: androidx.compose.ui.text.TextStyle = LocalTextStyle.current) {
    val ideas = if (web) listOf(
        "A landing page for my bakery", "A quiz about world capitals", "A wedding RSVP page", "A tiny synth you play with your fingers",
        "A reading list with notes", "A countdown to my holiday",
    ) else listOf(
        "A habit tracker that feels calm", "A recipe box that scales servings", "A pixel-art game about a lighthouse keeper",
        "A budget planner for a shared flat", "A plant-watering reminder with photos", "A metronome with practice stats",
    )
    var i by remember(web) { mutableIntStateOf(0) }
    LaunchedEffect(web) { while (true) { delay(3200); i = (i + 1) % ideas.size } }
    androidx.compose.animation.AnimatedContent(
        targetState = ideas[i % ideas.size], label = "idea",
        transitionSpec = {
            (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(500)) + androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(500)) { it / 3 }) togetherWith
                (androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(350)) + androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(350)) { -it / 3 })
        },
    ) { text -> Text(text, style = style, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

private fun permissionName(p: String) = if (p == "installs") "app installs" else "notifications"

/** One slim line at the top while a permission is off: what's missing, Allow, and a close. Tap the text for why. */
@Composable
internal fun PermissionNotice(m: MakerModel, allow: (String) -> Unit, modifier: Modifier) {
    val missing = m.missingPermissions
    var why by remember { mutableStateOf(false) }
    AnimatedVisibility(missing.isNotEmpty() && m.permissionsDismissed != missing.joinToString(","), modifier) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp), modifier = Modifier.widthIn(max = 460.dp).animateContentSize()) {
            Column(Modifier.padding(start = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 40.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
                    Spacer(Modifier.width(10.dp))
                    Text("Allow ${missing.joinToString(" and ") { permissionName(it) }}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).clickable { why = !why }.padding(vertical = 8.dp))
                    TextButton(onClick = { allow(missing.first()) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Allow", style = MaterialTheme.typography.labelMedium) }
                    IconButton(onClick = m::dismissPermissions, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Close, "Not now", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (why) Text(
                    "Some services might not work: " + missing.joinToString("; ") { if (it == "installs") "installing the apps you build" else "build alerts and background tasks" } + ".",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 14.dp, bottom = 10.dp),
                )
            }
        }
    }
}

/** One agent in Settings: its state in words with a coloured dot, and the one thing to do about it. */
@Composable
private fun AgentStatusRow(m: MakerModel, a: String, idle: Boolean, ask: (String, () -> Unit) -> Unit) {
    val state = m.agentState[a]
    val connecting = state == "connecting" || m.connectingAgent == a && MakerRuntime.running
    val (word, tone) = when {
        !m.agentsInstalled -> "Installs with the tools" to "grey"
        connecting -> "Connecting…" to "accent"
        state == "connected" -> (if (a == "opencode") "Connected to your provider" else "Signed in") to "green"
        state == "free" -> "Ready · free models, no account needed" to "green"
        state == "disconnected" -> "Not signed in" to "red"
        state == "missing" -> "Not installed" to "yellow"
        else -> "Checking…" to "grey"
    }
    val c = toneColor(tone)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 52.dp)) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(agentName(a), style = MaterialTheme.typography.bodyLarge)
                if (m.agent == a) Text("default", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (connecting || state == null && m.agentsInstalled) CircularProgressIndicator(Modifier.size(9.dp), strokeWidth = 1.5.dp, color = c)
                else Box(Modifier.size(8.dp).background(c, CircleShape))
                Text(word, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Signing in needs only the AI helpers, so it works while the Android tools still install.
        val can = m.agentsInstalled && !m.agentsBusy
        when {
            !m.agentsInstalled || connecting || state == null -> {}
            state == "missing" -> TextButton(onClick = { m.connect(a) }, enabled = can) { Text("Install") }
            state == "free" -> TextButton(onClick = { m.connect(a) }, enabled = can) { Text("Add provider") }
            state == "connected" -> TextButton(onClick = { ask("Sign ${agentName(a)} out inside Hatchlybox?") { m.logout(a) } }, enabled = can) { Text("Sign out") }
            else -> TextButton(onClick = { m.connect(a) }, enabled = can) { Text("Sign in") }
        }
    }
}

/** Under each agent in Settings: the model it runs with, picked from the ones it offers, or typed. */
@Composable
private fun AgentModelPicker(m: MakerModel, a: String) {
    var open by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var typing by remember { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    val current = m.agentModel(a)
    LaunchedEffect(open) { if (open && choices == null && m.agentsInstalled) choices = m.modelChoices(a) }
    Box(Modifier.padding(start = 2.dp, bottom = 4.dp)) {
        TextButton(onClick = { open = true }, enabled = m.agentsInstalled, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("Model: " + (choices?.firstOrNull { it.first == current }?.second ?: current.ifEmpty { "default" }), style = MaterialTheme.typography.labelMedium)
            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Default (${agentName(a)} chooses)") }, onClick = { m.chooseModel(a, ""); open = false },
                trailingIcon = { if (current.isEmpty()) Icon(Icons.Default.Check, "Selected", Modifier.size(18.dp)) })
            val list = choices
            if (list == null) DropdownMenuItem(text = { Text("Getting the models…", color = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick = {},
                leadingIcon = { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp) })
            else if (list.isEmpty()) DropdownMenuItem(text = { Text(if (a == "codex") "Sign in to Codex to see its models" else "No models found", color = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick = {})
            list.orEmpty().forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { m.chooseModel(a, id); open = false },
                    trailingIcon = { if (id == current) Icon(Icons.Default.Check, "Selected", Modifier.size(18.dp)) })
            }
            DropdownMenuItem(text = { Text("Type a model name…") }, onClick = { typed = current; typing = true; open = false })
        }
    }
    if (typing) AlertDialog(
        onDismissRequest = { typing = false },
        title = { Text("${agentName(a)} model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Muted(when (a) { "claude" -> "Like opus, sonnet or a full name such as claude-opus-5-5."; "codex" -> "The model's name, as Codex lists it."; else -> "provider/model, as opencode models lists it." })
                OutlinedTextField(typed, { typed = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { m.chooseModel(a, typed); typing = false }) { Text("Use it") } },
        dismissButton = { TextButton(onClick = { typing = false }) { Text("Cancel") } },
    )
}

/** Settings → Create box: which kinds its picker offers, and which of its features are on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateBoxSettings(m: MakerModel) {
    val off = listOf("actions", "quick", "ghost", "examples", "agent", "gold").count { !m.boxOn(it) }
    Fold("Create box", "${CreateModes.size - m.boxModesOff.size} kinds · " + if (off == 0) "everything on" else "$off features off", Icons.Outlined.Edit) {
        Text("What it can make", style = MaterialTheme.typography.bodyLarge)
        Muted("The kinds in the chip at the box's start. Auto stays: it works out the kind by itself, and you can always pick one there.")
        Muted("Auto makes a web app (it runs everywhere, and goes on the phone as an app with one tap) unless the idea needs the phone itself, like widgets, reminders, the camera or sensors (an Android app), is a real game (a Godot game, for every system), or names a system.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CreateModes.forEach { c ->
                FilterChip(selected = c.key !in m.boxModesOff, onClick = { m.boxMode(c.key, c.key in m.boxModesOff) }, enabled = c.key != "auto",
                    label = { Text(c.label) }, leadingIcon = { Icon(c.icon, null, Modifier.size(16.dp)) })
            }
        }
        HorizontalDivider()
        listOf(
            Triple("actions", "Phone actions", "Requests like \"remind me at 6\" or \"lock my phone\" become actions (with the local model). Off: they go to an agent like any idea."),
            Triple("quick", "Suggestions while typing", "Apps, people, timers and phone settings that match what you type, a tap away."),
            Triple("ghost", "Finish my words", "The rest of the best suggestion, faded after what you typed."),
            Triple("examples", "Example ideas", "An example of what to make while the box is empty."),
            Triple("agent", "Agent picker", "Which agent builds, just above the box while you type."),
            Triple("gold", "Gold thread", "A light that circles the box while an action works, then goes to its answer."),
        ).forEach { (key, title, why) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { m.setBox(key, !m.boxOn(key)) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyLarge); Muted(why) }
                Switch(checked = m.boxOn(key), onCheckedChange = { m.setBox(key, it) })
            }
        }
    }
}

/**
 * Settings → Reminders: the next week from the phone's calendars and Hatchlybox's own, the ones Hatchlybox set
 * with a way to cancel them, and whether new ones go in the phone's calendar.
 */
@Composable
private fun RemindersSettings() {
    val context = LocalContext.current
    val v = Reminders.version
    var asked by remember { mutableIntStateOf(0) }
    val canRead = remember(v, asked) { PhoneCalendar.canRead(context) }
    val list by produceState(emptyList<Reminders.Entry>(), v, asked) {
        val now = System.currentTimeMillis()
        value = withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Reminders.between(context, now - 3_600_000, now + 7 * 86_400_000L) }.getOrDefault(emptyList()) }
    }
    Fold("Reminders", if (list.isEmpty()) "Nothing this week" else "${list.size} this week", Icons.Outlined.NotificationsActive) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Save to the phone's calendar", style = MaterialTheme.typography.bodyLarge)
                Muted("New reminders go in your calendar app, which notifies you. Off: Hatchlybox keeps and notifies them.")
            }
            Switch(checked = Reminders.toCalendar(context), onCheckedChange = { Reminders.setToCalendar(context, it) })
        }
        if (!canRead) Row(verticalAlignment = Alignment.CenterVertically) {
            Muted("Allow the calendar to see its events and reminders here.")
            TextButton(onClick = { Setup.scope.launch(kotlinx.coroutines.Dispatchers.Main) { Front.ask(context, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR); asked++ } }) { Text("Allow") }
        }
        if (list.isEmpty()) Muted("Type \"remind me at 6 to call mum\" in the create box. Ask \"what are my reminders today?\" to hear them.")
        list.forEach { e ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.what, style = MaterialTheme.typography.bodyLarge)
                    Muted((if (e.allDay) spoken(e.at).substringBefore(" at ") + ", all day" else spoken(e.at)) + " · " + e.where + if (e.at <= System.currentTimeMillis()) " · done" else "")
                }
                e.mine?.let { r -> IconButton(onClick = { Setup.scope.launch(kotlinx.coroutines.Dispatchers.IO) { Reminders.cancel(context, r) } }) { Icon(Icons.Default.Close, "Cancel this reminder") } }
            }
        }
    }
}

/** Settings → Action log: every action run from the create box or for an agent, newest first. */
@Composable
private fun ActionLogSettings() {
    val context = LocalContext.current
    val v = ActionLog.version
    val list = remember(v) { ActionLog.all(context) }
    var shown by remember { mutableIntStateOf(30) }
    val time = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
    Fold("Action log", if (list.isEmpty()) "Nothing yet" else "${list.size} actions", Icons.Outlined.History) {
        if (list.isEmpty()) Muted("Each action done from the create box or by an agent shows here: what, who asked, and how it went.")
        list.take(shown).forEach { e ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (e.ok) Icons.Default.CheckCircle else Icons.Outlined.Block, if (e.ok) "Done" else "Not done", Modifier.size(18.dp),
                    tint = if (e.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.what, style = MaterialTheme.typography.bodyLarge)
                    Muted(time.format(java.util.Date(e.at)) + " · " + e.by + " · " + e.result)
                }
            }
        }
        Row {
            if (list.size > shown) TextButton(onClick = { shown += 50 }) { Text("Show more") }
            if (list.isNotEmpty()) TextButton(onClick = { ActionLog.clear(context) }) { Text("Clear the log") }
        }
    }
}

/** A subtle red underline under an agent that isn't connected. */
@Composable
private fun Modifier.notConnected(on: Boolean): Modifier {
    if (!on) return this
    val c = MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
    return drawBehind { val y = size.height + 2.dp.toPx(); drawLine(c, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1.5.dp.toPx()) }
}

/** Connect, Connecting, Connected: one quiet pill. The sign-in page opens by itself; a code can be pasted back. */
@Composable
internal fun AgentNotice(m: MakerModel, a: String) {
    val state = m.agentState[a]
    val connecting = state == "connecting" || m.connectingAgent == a && MakerRuntime.running
    val connected = m.justConnected == a
    val name = agentName(a)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp), modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 44.dp)) {
                when {
                    connected -> { Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)) }
                    connecting -> { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp); Spacer(Modifier.width(10.dp)) }
                    else -> { Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error.copy(alpha = 0.7f))); Spacer(Modifier.width(12.dp)) }
                }
                Text(
                    when { connected -> "$name connected"; connecting -> "Connecting $name"; state == "missing" -> "$name is getting ready"; else -> "Connect $name to start" },
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                when {
                    connected -> {}
                    connecting -> TextButton(onClick = { MakerRuntime.stop() }) { Text("Cancel") }
                    else -> FilledTonalButton(onClick = { m.connect(a) }, contentPadding = PaddingValues(horizontal = 16.dp)) { Text("Connect") }
                }
            }
            if (connecting && a != "opencode") {
                if (m.links.isNotEmpty()) TextButton(onClick = { m.external(m.links.last()) }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Open the sign-in page") }
                if (a == "claude") {
                    var code by rememberSaveable { mutableStateOf("") }
                    OutlinedTextField(
                        value = code, onValueChange = { code = it }, singleLine = true, shape = RoundedCornerShape(20.dp),
                        placeholder = { Text("Paste the code from the sign-in page") },
                        trailingIcon = { IconButton(onClick = { m.sendCode(code); code = "" }, enabled = code.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Send the code") } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { m.sendCode(code); code = "" }),
                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 6.dp),
                    )
                }
            }
            if (connecting && a == "opencode") TextButton(onClick = { m.showConsole = true }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Choose a provider") }
        }
    }
}

/** "with Claude Code", a tap away from the other agents. */
@Composable
internal fun AgentPicker(m: MakerModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text((if (m.createMode == "web") "web app with " else "with ") + agentName(m.agent), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.notConnected(!m.agentOk(m.agent)))
            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf("claude", "codex", "opencode").forEach { a ->
                DropdownMenuItem(
                    text = { Text(agentName(a)) }, onClick = { m.chooseAgent(a); open = false },
                    trailingIcon = {
                        when {
                            m.agentState[a] == "connecting" -> Text("Connecting", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            !m.agentOk(a) -> Text("Connect", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            m.agent == a -> Icon(Icons.Default.Check, "Selected", Modifier.size(18.dp))
                        }
                    },
                )
            }
        }
    }
}

/** One quiet line under the box: a saved idea waiting for the tools, or the task that's running. */
@Composable
internal fun HomeStatus(m: MakerModel) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        m.pendingIdea.isNotBlank() -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Starts when the tools are ready: ${m.pendingIdea}", style = MaterialTheme.typography.bodySmall, color = muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            TextButton(onClick = m::dropPending) { Text("Cancel", style = MaterialTheme.typography.labelMedium) }
        }
        m.busy -> TextButton(onClick = { m.page = "job" }) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Spacer(Modifier.width(8.dp))
            Text(m.job.text("msg", "Working…"), style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Setup and updates run in the background: a small line at the top while they do, a card only on failure. */
@Composable
internal fun SetupNotice(m: MakerModel, modifier: Modifier) {
    val sdkRunning = MakerRuntime.running && MakerRuntime.title == "Set up Android build tools"
    if (m.failure.isNotBlank() && !m.installing) {
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Setup didn't finish", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(m.failure, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Actions {
                    Button(onClick = { m.install(auto = true, why = "Setting up Hatchlybox") }) { Text("Try again") }
                    TextButton(onClick = { m.page = "settings" }) { Text("Details") }
                }
            }
        }
        return
    }
    if (!(m.installing || m.checking.isNotBlank() || sdkRunning || !m.ready || m.godotInstalling)) return
    val title = when {
        m.ready && m.godotInstalling && !m.installing -> "Installing Godot for games"
        // The first install, in the order things become usable: the AI helpers, then the Android build tools.
        m.firstSetup -> m.checking.ifBlank { if (m.agentsInstalled) "AI helpers ready · installing the Android build tools" else "Installing the AI helpers" }
        m.installing || m.checking.isNotBlank() -> m.checking.ifBlank { "Updating the tools" }
        sdkRunning || m.setup.text("state") == "running" -> "Setting up the Android SDK"
        else -> "Getting the build tools ready"
    }
    Surface(
        onClick = { if (sdkRunning) m.showConsole = true else m.page = "settings" },
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = CircleShape, modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------- the library: web apps, Android apps, other systems and the phone's apps in one place ----------

/**
 * One app in the library, whatever it is. `type`: web, site, apk, port, phone. Its long-press menu:
 * install (its word, Install or Update, and the action), info (the app's page, or Android's for a phone app),
 * uninstall, home (the kind and ref of its home-screen icon) and remove.
 */
class LibItem(val key: String, val type: String, val label: String, val sort: Long, val word: String?, val open: () -> Unit,
                      val export: (() -> Unit)? = null, val info: (() -> Unit)? = null, val remove: (() -> Unit)? = null,
                      val install: Pair<String, () -> Unit>? = null, val uninstall: (() -> Unit)? = null,
                      val home: Pair<String, String>? = null, val systems: List<String> = emptyList(), val icon: @Composable (Int) -> Unit)

/** A system's colour, for the dots on a creation's icon and its lines on its page. */
@Composable
internal fun systemColor(s: String) = when (s) {
    "android" -> typeColor("apk"); "web" -> typeColor("web"); "linux" -> Color(0xFFF59E0B); "omarchy" -> Color(0xFFA855F7)
    "windows" -> Color(0xFF3B82F6); else -> MaterialTheme.colorScheme.outline
}
/** In "Other systems": made for a system besides Android and the web. */
private fun LibItem.inOther() = systems.any { it != "android" && it != "web" } || type == "port" && systems.isEmpty()

private val typeOrder = listOf("web", "site", "apk", "port", "phone")
private fun typeName(t: String) = when (t) { "web" -> "Web apps"; "site" -> "From the browser"; "apk" -> "Android apps"; "port" -> "Other systems"; else -> "Phone apps" }
@Composable
internal fun typeColor(t: String) = when (t) {
    "web" -> Color(0xFF14B8A6); "site" -> Color(0xFF3B82F6); "apk" -> Color(0xFF22C55E); "port" -> Color(0xFFF59E0B); else -> MaterialTheme.colorScheme.outline
}

/** Everything, web apps first, then Android apps, other systems and the phone's own apps. */
@Composable
internal fun libraryItems(m: MakerModel, web: AppModel, openWeb: (Project) -> Unit): List<LibItem> {
    val webs = web.projects.map { p ->
        // Made: it opens (even while a change is being made). Not yet: its page.
        LibItem("web:" + p.id, "web", p.name, p.created / 1000, if (p.chat.running) (if (p.hasApp) "Updating" else "Building") else null,
            { m.library = false; openWeb(p); if (p.hasApp) web.screen = Page.Run(p.id) },
            info = { m.library = false; openWeb(p) }, home = "app" to "web:" + p.id, systems = listOf("web")) { size -> AppIcon(p, size) }
    }.sortedByDescending { it.sort }
    val context = androidx.compose.ui.platform.LocalContext.current
    val sites = Sites.list.map { s ->
        LibItem("site:" + s.id, "site", s.name, s.created, null, {
            m.library = false
            if (!Sites.open(context, s)) m.notice = "Couldn't open ${s.name}. Is its browser still installed?"
        }, remove = { m.launcher.removeRef("site", s.id); Sites.remove(context, s.id); m.favourites.remove("site:" + s.id) }, home = "site" to s.id) { size -> SiteIcon(Sites.icon(context, s.id), s.name, size.dp) }
    }.sortedByDescending { it.sort }
    // Your creations, each once with a dot per system: with an Android version it acts for this phone;
    // made only for other systems, it opens its page (it doesn't run here) and exports.
    val made = (m.creations.map { it.text("slug") } + m.apps.map { it.text("slug") }).distinct()
    val apks = made.mapNotNull { slug -> m.apps.firstOrNull { it.text("slug") == slug } }.map { a ->
        val systems = m.creations.firstOrNull { it.text("slug") == a.text("slug") }?.let { m.systems(it) } ?: listOf("android")
        val (word, act) = m.simple(a)
        val installed = m.isInstalled(a)
        // Installed: it opens, even when a newer build waits. Not yet: its page.
        LibItem("apk:" + a.text("slug"), "apk", a.text("label", a.text("slug")), a.optLong("created"), word.takeIf { it == "Building" || it == "Updating" },
            { if (installed) m.launchApp(a) else { m.library = false; m.openApp(a) } },
            info = { m.library = false; m.openApp(a) },
            install = if (act == "install") word to { m.artifact(a) } else null,
            uninstall = if (installed) ({ m.uninstall(a.text("pkg")) }) else null,
            home = "app" to "apk:" + a.text("slug"), systems = systems) { size -> AppIcon(m, a, size) }
    }.sortedByDescending { it.sort }
    val others = m.creations.filter { c -> m.apps.none { it.text("slug") == c.text("slug") } }.map { c ->
        val slug = c.text("slug"); val systems = m.systems(c)
        val web = m.systemWord(slug, "web") == "ready to open"
        val making = systems.any { m.systemWord(slug, it) == "being made" }
        // Not on Android yet: its web version opens right here; otherwise its page, where Install makes it for Android.
        LibItem("made:$slug", if (systems.all { it == "web" }) "web" else "port", c.text("label", slug), c.optLong("updated"), if (making) "Making" else null,
            open = if (web) ({ m.library = false; m.runWeb(slug) }) else ({ m.library = false; m.openCreation(slug) }),
            info = { m.library = false; m.openCreation(slug) },
            install = "Install" to { m.library = false; if (web) m.wrapWeb(slug) else m.makeFor(slug, "android") },
            export = systems.singleOrNull()?.takeIf { it != "web" }?.let { s -> { m.sharePort(c, s) } },
            home = "app" to "made:$slug", systems = systems) { size -> AppIcon(m, c, size) }
    }.sortedByDescending { it.sort }
    val phone = if (m.showPhoneApps) m.phoneApps.map { app ->
        LibItem("phone:${app.pkg}/${app.activity}", "phone", app.label, 0, null, { m.launch(app) }, info = { m.appInfo(app.pkg) },
            uninstall = if (app.removable) ({ m.uninstall(app.pkg) }) else null, home = "app" to "phone:${app.pkg}/${app.activity}") { size ->
            if (app.icon != null) Image(app.icon, null, Modifier.size(size.dp))
            else Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape((size / 4.5).dp), modifier = Modifier.size(size.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(app.label.take(1).uppercase(), style = MaterialTheme.typography.titleLarge) }
            }
        }
    } else emptyList()
    return webs + sites + apks + others + phone
}

/** An icon with its type: the colours softly ringed and a tiny dot at the top right. Long-press for favourites. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryCell(m: MakerModel, item: LibItem, size: Int, also: String? = null) {
    val c = typeColor(item.type)
    var menu by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box {
        Column(
            Modifier.width((size + 20).dp).goldTarget(item.key).then(if (also != null) Modifier.goldTarget(also) else Modifier).clip(RoundedCornerShape(16.dp))
                .combinedClickable(onClick = { m.used(item.key); item.open() }, onLongClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); menu = true })
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val frame = RoundedCornerShape(minOf(LocalLook.current.corner, (size + 6) / 4.2f).dp)
            Box(Modifier.size((size + 10).dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size((size + 6).dp).clip(frame).background(c.copy(alpha = 0.10f))
                        .border(1.5.dp, c.copy(alpha = 0.55f), frame),
                    contentAlignment = Alignment.Center,
                ) { item.icon(size) }
                if (item.word != null) CircularProgressIndicator(Modifier.size((size + 10).dp), strokeWidth = 2.dp, color = c)
                // A dot per system it's made for (one for the type when it isn't a creation).
                Row(Modifier.align(Alignment.TopEnd).clip(CircleShape).background(MaterialTheme.colorScheme.surface).padding(1.5.dp), horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
                    (item.systems.takeIf { it.isNotEmpty() }?.take(4)?.map { systemColor(it) } ?: listOf(c)).forEach { dot -> Box(Modifier.size(7.dp).clip(CircleShape).background(dot)) }
                }
            }
            Text((item.word ?: item.label).let { if (LocalLook.current.uppercase) it.uppercase() else it }, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            val fav = item.key in m.favourites
            DropdownMenuItem(text = { Text(if (fav) "Remove from favourites" else "Add to favourites") }, onClick = { m.toggleFavourite(item.key); menu = false },
                leadingIcon = { Icon(if (fav) Icons.Default.Star else Icons.Outlined.StarOutline, null) })
            item.install?.let { (word, go) -> DropdownMenuItem(text = { Text(word) }, leadingIcon = { Icon(if (word == "Update") Icons.Outlined.SystemUpdate else Icons.Outlined.InstallMobile, null) }, onClick = { menu = false; go() }) }
            item.home?.let { (kind, ref) -> DropdownMenuItem(text = { Text("Add to home screen") }, leadingIcon = { Icon(Icons.Outlined.AddToHomeScreen, null) }, onClick = { menu = false; m.addToHome(kind, ref, item.label) }) }
            item.export?.let { ex -> DropdownMenuItem(text = { Text("Export") }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = { menu = false; ex() }) }
            item.info?.let { go -> DropdownMenuItem(text = { Text("App info") }, leadingIcon = { Icon(Icons.Outlined.Info, null) }, onClick = { menu = false; go() }) }
            item.uninstall?.let { go -> DropdownMenuItem(text = { Text("Uninstall") }, leadingIcon = { Icon(Icons.Outlined.DeleteForever, null) }, onClick = { menu = false; go() }) }
            item.remove?.let { go -> DropdownMenuItem(text = { Text("Remove") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { menu = false; go() }) }
        }
    }
}

/**
 * The library: the create box on top (what's typed finds apps too, so it's also the search), a legend that
 * filters (All by default), and every app as a grid, drawn in the theme over its background.
 */
@Composable
internal fun Library(m: MakerModel, web: AppModel, openWeb: (Project) -> Unit, createWeb: (String) -> Boolean, grid: androidx.compose.foundation.lazy.grid.LazyGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()) {
    val items = libraryItems(m, web, openWeb)
    val look = LocalLook.current
    var filter by rememberSaveable { mutableStateOf("all") }
    var typed by rememberSaveable { mutableStateOf("") }
    // "open ma" finds Maps like "ma" does; another command (call, text...) leaves the grid as it is.
    val search = commandOf(typed).let { c -> if (c == null) typed.trim() else if (c.kind == "open") c.rest else "" }
    val shown = items.filter { (filter == "all" || it.type == filter || filter == "port" && it.inOther()) && it.label.contains(search, true) }
    // Over the theme's background (it's behind the home screen too), a veil of the theme's surface.
    val backdrop = Look.background != "plain" && (Look.background != "image" || Look.image != null)
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = if (backdrop) 0.72f else 1f), modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // The handle: drag down anywhere at the top of the list (the home screen follows the finger), tap it, or press back.
            Box(
                Modifier.fillMaxWidth().clickable(onClickLabel = "Close the library") { m.library = false }.padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant)) }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
                CreatorBox(m, web, createWeb, items, typed, { typed = it }, m.homePage.coerceAtMost(m.launcher.pages.lastIndex), m.launcher.pages.size,
                    Modifier.weight(1f), quickBelow = true, appsInQuick = false, placeholder = "Find, open, or make anything", goldKey = "send-library",
                    // One app found by its name: Enter opens it, as in any search.
                    searchHit = shown.singleOrNull()?.takeIf { search.isNotBlank() }?.let { hit -> Quick("hit", hit.label, "Open", hit.label, hit.icon) { m.used(hit.key); hit.open() } })
                var syncing by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(DeviceSync.pendingLink) { if (DeviceSync.pendingLink != null) syncing = true }
                IconButton(onClick = { syncing = true }) {
                    if (DeviceSync.running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Sync, "Sync with another device", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (syncing) SyncSheet(m) { syncing = false }
                IconButton(onClick = { m.library = false; m.page = "settings" }) { Icon(Icons.Outlined.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            // Legend and filter in one: each type's dot and name, with how many.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                val types = listOf("all") + typeOrder.filter { t -> items.any { it.type == t || t == "port" && it.inOther() } }
                items(types.size) { i ->
                    val t = types[i]
                    FilterChip(
                        selected = filter == t, onClick = { filter = t }, shape = RoundedCornerShape(minOf(look.corner, 16f).dp),
                        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = maxOf(0.5f, look.cardOpacity))),
                        border = FilterChipDefaults.filterChipBorder(true, filter == t, borderColor = lookBorder(look).let { if (it.alpha < 0.2f) MaterialTheme.colorScheme.outlineVariant else it }),
                        label = { Text(if (t == "all") "All ${items.size}" else "${typeName(t)} ${items.count { it.type == t || t == "port" && it.inOther() }}") },
                        leadingIcon = { if (t != "all") Box(Modifier.size(8.dp).clip(CircleShape).background(typeColor(t))) },
                    )
                }
            }
            if (shown.isEmpty()) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Muted(when { items.isEmpty() -> "Your apps will appear here."; typed.isNotBlank() -> "Nothing here by that name. Press ↑ to make it."; else -> "Nothing matches." })
            }
            // The most recently opened, in a row at the top, set off from the rest by a line (not while searching).
            val recent = if (search.isBlank()) m.recent.mapNotNull { k -> shown.firstOrNull { it.key == k } }.take(12) else emptyList()
            // An action is opening an app: the whole list, scrolled to it, as the user would look for it.
            LaunchedEffect(m.reveal) {
                val k = m.reveal ?: return@LaunchedEffect
                filter = "all"; typed = ""
                val i = items.indexOfFirst { it.key == k }.takeIf { it >= 0 } ?: return@LaunchedEffect
                val at = i + if (m.recent.any { r -> items.any { it.key == r } }) 3 else 0
                val seen = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.index == at }
                if (seen == null || seen.offset.y < 0 || seen.offset.y + seen.size.height > grid.layoutInfo.viewportEndOffset) grid.animateScrollToItem(at)
            }
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                state = grid, columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(84.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                if (recent.isNotEmpty()) {
                    item(key = "recent-title", span = { GridItemSpan(maxLineSpan) }) {
                        WidgetHeader("Recent", Icons.Outlined.History, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                    }
                    item(key = "recent-row", span = { GridItemSpan(maxLineSpan) }) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                            items(recent.size, key = { "r:" + recent[it].key }) { i -> LibraryCell(m, recent[i], 52) }
                        }
                    }
                    item(key = "recent-line", span = { GridItemSpan(maxLineSpan) }) {
                        HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = lookBorder(look).let { if (it.alpha < 0.2f) MaterialTheme.colorScheme.outlineVariant else it }.copy(alpha = 0.7f))
                    }
                }
                items(shown.size, key = { shown[it].key }) { i -> Box(contentAlignment = Alignment.Center) { LibraryCell(m, shown[i], 52, also = "lib:" + shown[i].key) } }
            }
        }
    }
}

@Composable
private fun MakerApp(m: MakerModel, a: JSONObject, ask: (String, () -> Unit) -> Unit, createWeb: (String) -> Boolean) {
    val slug = a.text("slug")
    val s = m.status(a)
    val idle = !m.busy && !MakerRuntime.running && !m.installing && !m.godotInstalling
    val history = a.optJSONArray("history")?.objects().orEmpty()
    var changeAgent by rememberSaveable(slug) { mutableStateOf(m.draft("helper-$slug").ifBlank { a.text("agent", m.agent) }) }
    LaunchedEffect(changeAgent) { m.saveDraft("helper-$slug", changeAgent) }
    val ports = remember(slug, MakerRuntime.running) { m.ports(a) }

    // Like a store page: icon, name, the systems it's made for, one action.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        AppIcon(m, a, 72)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(a.text("label", slug), style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf("Android") + ports.map(::portName)).forEach { t ->
                    Text(t, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape).padding(horizontal = 10.dp, vertical = 3.dp))
                }
            }
        }
    }
    SyncConflict(m, slug)
    WordButton(m, a, big = true)
    if (s.key == "working") {
        LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Muted(m.job.text("msg", "Working on it. You'll get a notification when it's done."))
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { ask("Stop the current task? Its log and the work so far stay.") { m.cancel() } }) { Text("Stop") }
        }
    }
    // The AI helper reached its plan's usage limit: say so, and offer to carry on with another one.
    val limit = m.job.optJSONObject("limit")
    if (limit != null && m.job.text("slug") == slug && m.job.text("phase") == "failed") {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(m.job.text("msg").replaceFirstChar { it.uppercase() } + ".", style = MaterialTheme.typography.bodyMedium)
                Actions {
                    listOf("claude", "codex", "opencode").filter { it != limit.text("agent") && m.agentOk(it) }.forEach { other ->
                        Button(onClick = { m.action("Continue with ${agentName(other)}", listOf("retry", slug, other)); m.page = "job" }, enabled = idle && m.ready) { Text("Continue with ${agentName(other)}") }
                    }
                }
            }
        }
    }
    if (s.key in setOf("failed", "stopped", "change-failed") && m.job.text("slug") == slug && m.buildError.isNotBlank()) {
        var show by remember { mutableStateOf(false) }
        TextButton(onClick = { show = !show }) { Text(if (show) "Hide what went wrong" else "What went wrong") }
        if (show) SelectionContainer { Text(m.buildError.takeLast(4000), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error) }
    }

    Systems(m, a, ports, android = true)

    // Screenshots, decoded off the main thread.
    val shots by produceState(emptyList<androidx.compose.ui.graphics.ImageBitmap>(), slug, a.optLong("built")) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { m.screenshots(a).mapNotNull { f -> m.thumbnail(f)?.asImageBitmap() } }
    }
    if (shots.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(shots.size) { i ->
            val b = shots[i]
            Image(b, "Screenshot ${i + 1}", contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.height(280.dp).aspectRatio((b.width.toFloat() / b.height).coerceIn(0.4f, 1.8f)).clip(RoundedCornerShape(16.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(16.dp)))
        }
    }

    // What it is.
    val (short, full) = remember(slug, a.optLong("updated")) { m.description(a) }
    if (short.isNotBlank() || full.isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (short.isNotBlank()) Text(short, style = MaterialTheme.typography.bodyLarge)
        if (full.isNotBlank() && full != short) {
            var more by rememberSaveable(slug) { mutableStateOf(false) }
            Text(full, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (more) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.animateContentSize())
            if (full.length > 180) TextButton(onClick = { more = !more }, contentPadding = PaddingValues(0.dp)) { Text(if (more) "Less" else "More") }
        }
    }

    // Improving it: the main thing to do with an app that exists.
    if (a.has("apk") || a.has("pending")) {
        var request by rememberSaveable(slug) { mutableStateOf(m.draft(slug)) }
        LaunchedEffect(request) { m.saveDraft(slug, request) }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.AutoFixHigh, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Improve it", style = MaterialTheme.typography.titleMedium)
                }
                if (a.has("pending")) PendingChange(m, a, ask)
                else {
                    Field("What should change?", request, { request = it }, 3)
                    Actions { listOf("a dark mode", "bigger text", "a new icon", "a settings page", "nicer colours", "sounds").forEach { e -> SuggestionChip(onClick = { request = if (request.isBlank()) "Add $e" else "$request, and add $e" }, label = { Text("+ $e") }) } }
                    Actions {
                        listOf("claude", "codex", "opencode").forEach { helper ->
                            FilterChip(selected = changeAgent == helper, enabled = idle, onClick = { changeAgent = helper },
                                label = { Text(agentName(helper), modifier = Modifier.notConnected(!m.agentOk(helper))) })
                        }
                    }
                    if (!m.agentOk(changeAgent)) AgentNotice(m, changeAgent)
                    else Button(onClick = { m.action("Improve ${a.text("label", slug)}", listOf("change", slug, changeAgent, request.trim())); request = ""; m.page = "job" }, enabled = idle && m.ready && request.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Make this change") }
                    Muted("You try each change first, then keep it or undo it.")
                }
            }
        }
    }

    // Everything else, folded away.
    Fold("Advanced settings", "Clone, details, Google Play", Icons.Outlined.Tune, key = "advanced-$slug", open = m.openPlay) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Clone", style = MaterialTheme.typography.bodyLarge)
                Muted("A full copy with its own name and package, to take in another direction.")
            }
            TextButton(onClick = { ask("Make a copy of ${a.text("label", slug)}? It gets its own name and package, and builds by itself.") { m.clone(a) } }, enabled = idle) { Text("Clone") }
        }
        HorizontalDivider()
        Fold("Details", "${history.size.coerceAtLeast(1) - 1} changes · ${agentName(a.text("agent", m.agent))}", Icons.Outlined.Info, key = "details-$slug") {
            val outcomes = a.optJSONObject("outcomes") ?: JSONObject()
            Fact("Package", a.text("pkg"))
            if (a.optLong("created") > 0) Fact("Made", android.text.format.DateFormat.getMediumDateFormat(LocalContext.current).format(java.util.Date(a.optLong("created") * 1000)))
            Fact("Agent", agentName(a.text("agent", m.agent)))
            Fact("Build", outcomes.text("build", "not built"))
            Fact("Install", outcomes.text("install", "not installed"))
            Fact("Startup", outcomes.text("runtime", "not checked"))
            Fact("GitHub", outcomes.text("github", "local only"))
            history.firstOrNull()?.let { h ->
                Text("Your idea", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(h.text("prompt").removePrefix("Build this Android app in the current directory: ").lineSequence().first(), style = MaterialTheme.typography.bodyMedium)
            }
            if (history.size > 1) {
                Text("Changes asked for", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                history.drop(1).asReversed().take(15).forEach { h ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (h.optBoolean("discarded")) "×" else "✓", color = if (h.optBoolean("discarded")) MaterialTheme.colorScheme.outline else toneColor("green"))
                        Text(h.text("prompt").removePrefix("Change this Android app (read AGENTS.md first): ").lineSequence().first(), style = MaterialTheme.typography.bodyMedium, color = if (h.optBoolean("discarded")) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            val checks = a.optJSONArray("checks")?.objects().orEmpty()
            if (checks.isNotEmpty()) {
                Text("Feature checks", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                checks.forEach { ch ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(ch.text("title"), style = MaterialTheme.typography.bodyMedium)
                        Muted("${ch.text("status", "pending")}${ch.text("note").takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}")
                        Actions { listOf("pass", "fail", "skip").forEach { st -> FilterChip(selected = ch.text("status") == st, enabled = idle, onClick = { m.action("Record feature check", listOf("checks", slug, ch.text("id"), st, "Recorded in Hatchlybox")) }, label = { Text(st) }) } }
                    }
                }
            }
        }

        Fold("Settings", "Rebuild, share, back up, GitHub, delete", Icons.Outlined.Tune, key = "settings-$slug") {
            Actions {
                Act("Rebuild", idle) { m.action("Build ${a.text("label", slug)}", listOf("build", slug)); m.page = "job" }
                Act("Share APK", a.has("apk")) { m.artifact(a, share = true) }
                Act("Check startup over ADB", idle && a.has("apk")) { m.action("Verify $slug", listOf("verify", slug), true) }
                Act("Encrypted backup", idle) { m.action("Back up $slug", listOf("backup", slug), true) }
                Act("Review code changes", idle && a.has("pending")) { m.diff(a) }
            }
            Muted("Backups hold the code, its history, the builds and the upload key; not the app's own data. Share them from Settings → Backups.")
            Text("GitHub", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(a.optJSONObject("github")?.text("repo", "Only on this phone") ?: "Only on this phone", style = MaterialTheme.typography.bodyMedium)
            Actions {
                Act("Create private repository", idle && m.github.text("state") == "connected") { m.action("Publish code privately", listOf("github", "publish", slug, "private"), true) }
                Act("Check for private data", idle) { m.action("Privacy check", listOf("github", "check", slug), true) }
                Act("Make public", idle && a.has("github")) { ask("Make $slug's repository public? Its code and history will be visible to everyone. Run the privacy check first.") { m.action("Make repository public", listOf("github", "visibility", slug, "public"), true) } }
                Act("Make private", idle && a.has("github")) { m.action("Make repository private", listOf("github", "visibility", slug, "private"), true) }
            }
            HorizontalDivider()
            OutlinedButton(onClick = { ask("Delete ${a.text("label", slug)} from your library? Its code is kept in trash for Undo in Settings. The installed app stays on the phone.") { m.deleteApp(a) } }, enabled = idle, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete this app") }
        }

        val play = a.optJSONObject("play") ?: JSONObject()
        Fold("Google Play", if (s.key == "live") "Live on Google Play" else if (s.key == "testers") "With your testers" else if (play.has("prepared")) "Release prepared: ${play.text("vn")}" else "Not published yet", Icons.Outlined.Storefront, key = "play-$slug", open = m.openPlay) {
            MakerPublishApp(m, a, ask)
        }
    }
}

/** Make the same app for another system: the Android app stays as it is. */
@Composable
private fun OtherSystems(m: MakerModel, a: JSONObject, ports: List<String>) {
    val slug = a.text("slug")
    var target by rememberSaveable(slug) { mutableStateOf("web") }
    var custom by rememberSaveable(slug) { mutableStateOf("") }
    Muted("${agentName(m.agent)} makes the same app, with the same features and look, for the system you pick, from its newest version. What's made already stays as it is.")
    Actions {
        listOf("web" to "Web app", "linux" to "Linux", "omarchy" to "Omarchy plugin", "windows" to "Windows", "macos" to "macOS", "custom" to "Other…")
            .filter { (k, _) -> k == "custom" || k !in ports }.forEach { (k, label) ->
                FilterChip(selected = target == k, onClick = { target = k }, label = { Text(label) })
            }
    }
    if (target == "custom") Field("Which system? (e.g. a KDE widget, a CLI tool, a smartwatch app)", custom, { custom = it })
    if (!m.agentOk(m.agent)) AgentNotice(m, m.agent)
    else Button(
        onClick = { m.makeFor(slug, if (target == "custom") "custom:${custom.trim()}" else target) },
        enabled = !m.agentsBusy && (target != "custom" || custom.isNotBlank()), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text("Make it") }
}

/**
 * Where it runs: a line per system. Android acts here (the big button above when it's made; Install makes it
 * when it isn't), the web version opens here, the others export to the computer or device they're for. A
 * version older than another one is brought up to date with a tap; one that didn't finish, tried again.
 */
@Composable
private fun Systems(m: MakerModel, a: JSONObject, ports: List<String>, android: Boolean) {
    val slug = a.text("slug")
    val systems = m.creations.firstOrNull { it.text("slug") == slug }?.optJSONObject("systems") ?: JSONObject()
    var adding by rememberSaveable(slug) { mutableStateOf(false) }
    val free = !m.agentsBusy
    val newest = { s: String -> systems.optJSONObject(s)?.text("older").orEmpty() }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Devices, null, tint = MaterialTheme.colorScheme.primary)
                Text("Systems", style = MaterialTheme.typography.titleMedium)
            }
            if (android) {
                val older = newest("android")
                val wrapped = systems.optJSONObject("android")?.optBoolean("wrapped") == true
                SystemLine("android", m.status(a).word, when {
                    older.isNotBlank() -> "Older than the ${portName(older)} version: bring it up to date to get what's new there."
                    wrapped -> "The web version as an app: it follows the web version. A full native app adds widgets, work in the background and deeper phone features."
                    else -> "On this phone: install, update and open it with the button above."
                }) {
                    Row {
                        if (older.isNotBlank()) TextButton(onClick = { m.makeFor(slug, "android") }, enabled = free && m.ready) { Text("Bring up to date") }
                        if (wrapped) TextButton(onClick = { m.makeNative(slug) }, enabled = free && m.ready) { Text("Make native") }
                    }
                }
            } else if (m.webReady(slug)) SystemLine("android", "Not on this phone yet", "Put on phone makes it an app from the web version in a minute or two, with no AI. A full native app is made by the AI from it (a few minutes) and can do more.") {
                Row {
                    Button(onClick = { m.wrapWeb(slug) }, enabled = free) { Text("Put on phone") }
                    TextButton(onClick = { m.makeFor(slug, "android") }, enabled = free) { Text("Native") }
                }
            } else SystemLine("android", "Not on this phone yet", "Install makes the Android version from the ${portName(m.newestSystem(a))} one, then installs it (a few minutes).") {
                Button(onClick = { m.makeFor(slug, "android") }, enabled = free) { Text("Install") }
            }
            ports.forEach { p ->
                val info = systems.optJSONObject(p) ?: JSONObject()
                val word = info.text("word").ifBlank { "made" }; val older = info.text("older")
                val detail = when {
                    word == "being made" -> "${agentName(info.text("agent", m.agent))} is making it now."
                    word == "not finished" -> "It stopped before it was done: try again to carry on."
                    older.isNotBlank() -> "Older than the ${portName(older)} version: bring it up to date to get what's new there."
                    p == "web" -> "Runs right here in Hatchlybox, and in any browser once exported."
                    word == "ready to export" -> "Built. Export it to the computer or device it's for: RUN.md inside says how to install it."
                    else -> "The source, with a build script: export it and build it there (RUN.md inside says how)."
                }
                SystemLine(p, word.replaceFirstChar { it.uppercase() }, detail) {
                    Row {
                        when {
                            word == "being made" -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            word == "not finished" -> TextButton(onClick = { m.makeFor(slug, p) }, enabled = free) { Text("Try again") }
                            older.isNotBlank() -> TextButton(onClick = { m.makeFor(slug, p) }, enabled = free) { Text("Bring up to date") }
                            p == "web" -> TextButton(onClick = { m.runWeb(slug) }) { Text("Open") }
                        }
                        if (word != "being made" && word != "not finished" && (p != "web" || older.isNotBlank()))
                            TextButton(onClick = { m.sharePort(a, p) }, enabled = !MakerRuntime.running) { Text("Export") }
                    }
                }
            }
            if (!adding) TextButton(onClick = { adding = true }, contentPadding = PaddingValues(0.dp)) { Text("+ Make it for another system") }
            else OtherSystems(m, a, ports)
        }
    }
}

@Composable
private fun SystemLine(system: String, word: String, detail: String, action: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(systemColor(system)))
        Column(Modifier.weight(1f)) {
            Text("${portName(system)} · $word", style = MaterialTheme.typography.bodyLarge)
            Muted(detail)
        }
        action?.invoke()
    }
}

/**
 * A creation not on Android yet (made first for the web, Windows, Linux…): one big button that puts it on
 * this phone (opens the web version, or makes the Android version and installs it), then its systems.
 */
@Composable
private fun CreationPage(m: MakerModel, c: JSONObject) {
    val slug = c.text("slug")
    val ports = remember(slug, MakerRuntime.running, m.creations) { m.ports(c) }
    val systems = c.optJSONObject("systems") ?: JSONObject()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        AppIcon(m, c, 72)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(c.text("label", slug), style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ports.forEach { t ->
                    Text(portName(t), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape).padding(horizontal = 10.dp, vertical = 3.dp))
                }
            }
        }
    }
    SyncConflict(m, slug)
    val web = systems.optJSONObject("web")?.text("word") == "ready to open"
    if (web) {
        Button(onClick = { m.runWeb(slug) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Open", style = MaterialTheme.typography.titleMedium) }
        OutlinedButton(onClick = { m.wrapWeb(slug) }, enabled = !m.agentsBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Put on this phone as an app") }
    }
    else Button(onClick = { m.makeFor(slug, "android") }, enabled = !m.agentsBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Install", style = MaterialTheme.typography.titleMedium) }
    c.text("idea").takeIf { it.isNotBlank() }?.let { Text(it.replaceFirstChar { ch -> ch.uppercase() }, style = MaterialTheme.typography.bodyLarge) }
    Systems(m, c, ports, android = false)
}

/** The app's one word as a button: Build, Building, Updating, Update, Install, Open. */
@Composable
private fun WordButton(m: MakerModel, a: JSONObject, big: Boolean = false) {
    val slug = a.text("slug")
    val (word, act) = m.simple(a)
    val idle = !m.busy && !MakerRuntime.running && !m.installing && !m.godotInstalling
    val working = word == "Building" || word == "Updating"
    val run: () -> Unit = {
        when (act) {
            "log" -> { m.selected = slug; m.page = "job" }
            "open" -> m.launchApp(a)
            "install" -> m.artifact(a)
            else -> if (!m.agentOk(m.agent) && act != "rebuild") m.connect(m.agent) else {
                when (act) {
                    "fix" -> m.action("Fix ${a.text("label", slug)}", listOf("change", slug, m.agent, "The app closes straight after it starts. Find the cause (hatchlybox peek shows the crash) and fix it."))
                    "rebuild" -> m.action("Build ${a.text("label", slug)}", listOf("build", slug))
                    else -> m.action("Build ${a.text("label", slug)}", listOf("retry", slug, m.agent))
                }
                m.selected = slug; m.page = "job"
            }
        }
    }
    val enabled = working || act in setOf("open", "install") || idle && m.ready
    val content: @Composable RowScope.() -> Unit = {
        if (working) { CircularProgressIndicator(Modifier.size(if (big) 16.dp else 12.dp), strokeWidth = 1.5.dp); Spacer(Modifier.width(8.dp)) }
        Text(word, style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge)
    }
    if (big) {
        if (working) OutlinedButton(onClick = run, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), content = content)
        else Button(onClick = run, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), content = content)
    } else FilledTonalButton(onClick = run, enabled = enabled, contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.heightIn(min = 36.dp), content = content)
}

@Composable
private fun PendingChange(m: MakerModel, a: JSONObject, ask: (String, () -> Unit) -> Unit) {
    val slug = a.text("slug"); val pending = a.optJSONObject("pending") ?: JSONObject()
    val idle = !m.busy && !MakerRuntime.running && !m.installing && !m.godotInstalling
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("A change is waiting for you", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(pending.text("request", "Try this version before keeping it."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(if (pending.optBoolean("built")) "Install it and try it, then keep it or undo it. Undoing restores the previous code and APK; the app's data stays." else "It didn't build: try again, or undo it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Actions {
                if (pending.optBoolean("built")) Button(onClick = { ask("Keep this change to ${a.text("label", slug)}?") { m.action("Keep change", listOf("accept", slug), true) } }, enabled = idle) { Text("Keep it") }
                OutlinedButton(onClick = { ask("Undo this change and put back the previous code and APK? The app's data stays.") { m.action("Undo change", listOf("discard", slug), true) } }, enabled = idle) { Text("Undo it") }
                TextButton(onClick = { m.diff(a) }, enabled = idle) { Text("See the code") }
                pending.text("prUrl", pending.text("url")).takeIf { it.startsWith("https://") }?.let { url -> TextButton(onClick = { m.external(url) }) { Text("Pull request") } }
            }
        }
    }
}

// ---------- progress ----------

@Composable
private fun MakerProgress(m: MakerModel, ask: (String, () -> Unit) -> Unit) {
    val phase = m.job.text("phase", "idle")
    val a = m.apps.firstOrNull { it.text("slug") == m.job.text("slug") }
    Panel(a?.text("label") ?: m.job.text("slug", "No task yet")) {
        a?.let { StatusPill(m.status(it)) }
        Text(m.job.text("msg", "Describe an app to start."), style = MaterialTheme.typography.bodyMedium)
        if (m.busy || MakerRuntime.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        Muted("${m.job.text("kind", "app")} · ${agentName(m.job.text("agent", m.agent))} · $phase")
        Actions {
            if (m.busy || phase == "locked") Act("Stop") { ask("Stop the current task? Its log and the work so far stay.") { m.cancel() } }
            if (phase == "locked") Act("Unlock keys", !MakerRuntime.running) { m.action("Unlock keys", listOf("unlock"), true) }
            Act("Task console", MakerRuntime.title.isNotBlank()) { m.showConsole = true }
            a?.let { Act("Open the app's page") { m.openApp(it) } }
        }
    }
    if (m.buildError.isNotBlank()) Panel("Build error") { SelectionContainer { Text(m.buildError.takeLast(12000), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error) } }
    Panel("Log") {
        val clipboard = LocalClipboardManager.current
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium) {
            SelectionContainer { Text(m.log.lineSequence().toList().takeLast(100).joinToString("\n").ifBlank { "No output yet." }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(12.dp)) }
        }
        Act("Copy log") { clipboard.setText(AnnotatedString(m.log)) }
    }
}

// ---------- settings for the whole box ----------

@Composable
private fun MakerSettings(m: MakerModel, ask: (String, () -> Unit) -> Unit, allow: (String) -> Unit, requestHome: () -> Unit) {
    val context = LocalContext.current
    val idle = !MakerRuntime.running && !m.busy && !m.installing && !m.godotInstalling
    var port by rememberSaveable { mutableStateOf("") }
    var pairing by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var fixes by rememberSaveable { mutableStateOf(m.settings.text("fixRounds", "3")) }
    var timeout by rememberSaveable { mutableStateOf(m.settings.text("agentTimeout", "45")) }
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(m::importPlayKey) }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { m.importBackup(it) } }
    SettingsHeader("Home and look")
    Fold("Home screen", if (m.isHome) "Hatchlybox is your home screen" else if (m.launcherMode) "Not chosen as the home app yet" else "Use Hatchlybox as your launcher", Icons.Outlined.Home) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Use as home screen", style = MaterialTheme.typography.bodyLarge)
                Muted("The Home button opens Hatchlybox: your apps and widgets, the library as the app drawer, and the box to make more.")
            }
            Switch(checked = m.launcherMode, onCheckedChange = { if (it) requestHome() else m.setLauncher(false) })
        }
        if (m.launcherMode && !m.isHome) Act("Choose Hatchlybox as the home app") { requestHome() }
        if (m.isHome) Act("Change the home app") { m.chooseHome() }
        Muted("Long-press an empty spot on the home screen to add widgets and pages; long-press a widget to move or resize it.")
    }
    Fold("Look", "${Look.current.name} · ${Look.palette.name} · " + when (Look.background) { "wallpaper" -> "wallpaper"; "plain" -> "plain"; "image" -> "photo"; else -> (artName(Look.artNow).ifBlank { "drawn" }) + " background" }, Icons.Outlined.Palette) {
        LookPicker(m)
        Muted("Widgets use the theme's colours, so they always match. Agents read the theme when they make or change one.")
    }
    Fold("Library", if (m.showPhoneApps) "Includes your phone's apps" else "Your apps only", Icons.Outlined.Apps) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Phone apps", style = MaterialTheme.typography.bodyLarge)
                Muted("Every app on this phone in the library, after your own, to open like a launcher.")
            }
            Switch(checked = m.showPhoneApps, onCheckedChange = m::choosePhoneApps)
        }
    }
    CreateBoxSettings(m)
    Fold("Permissions", if (m.missingPermissions.isEmpty()) "All allowed" else "${m.missingPermissions.size} not allowed", Icons.Outlined.Shield, open = m.missingPermissions.isNotEmpty()) {
        Muted("Hatchlybox works without these, but some services might not: it checks them each time you come back.")
        listOf(
            Triple("notifications", "Notifications", "Build alerts and the background task's progress."),
            Triple("installs", "Install apps", "Installing the apps you build on this phone."),
        ).forEach { (key, title, why) ->
            val on = if (key == "installs") m.installsOn else m.notificationsOn
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(if (on) Icons.Default.CheckCircle else Icons.Outlined.ErrorOutline, if (on) "Allowed" else "Not allowed",
                    tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Muted(if (on) "Allowed. $why" else "Not allowed. $why")
                }
                if (!on) TextButton(onClick = { allow(key) }) { Text("Allow") }
            }
        }
        TextButton(onClick = m::checkPermissions) { Text("Check again") }
    }
    SettingsHeader("AI helpers and building")
    Fold("AI helpers", "New creations are made by ${agentName(m.agent)}" + if (m.settings.text("fallback", "on") == "off") "" else " · another carries on at a limit", Icons.Outlined.SmartToy) {
        Text("Default agent", style = MaterialTheme.typography.titleSmall)
        Agents(m)
        Muted("Each agent signs in with its own account, inside Hatchlybox only. The launcher's sign-ins aren't shared. OpenCode needs no account: it uses OpenCode's free models until you add a provider.")
        listOf("claude", "codex", "opencode").forEach { a -> AgentStatusRow(m, a, idle, ask); AgentModelPicker(m, a) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Carry on with another AI helper", style = MaterialTheme.typography.bodyLarge)
                Muted("When one reaches its usage limit part-way, the next signed-in one (OpenCode's free models last) carries on with the same work instead of stopping.")
            }
            Switch(checked = m.settings.text("fallback", "on") != "off", enabled = idle, onCheckedChange = { on ->
                m.action(if (on) "Carry on with another AI helper" else "Stop at a usage limit", listOf("set", "fallback", if (on) "on" else "off"))
            })
        }
        Muted("The model is used everywhere that agent runs: building apps, the chat, quick questions and its terminal widgets.")
        Actions {
            Act(if (m.checkingAgentsNow) "Checking…" else "Check again", m.agentsInstalled && !m.checkingAgentsNow) { m.checkAgents() }
            Act("Check installed agents", idle && m.toolsInstalled) { m.action("Installed agents", listOf("agents"), true) }
        }
    }
    Fold("Build preferences", "${m.settings.text("fixRounds", "3")} repair rounds · ${m.settings.text("agentTimeout", "45")} min per agent run", Icons.Outlined.Speed) {
        Field("Automatic repair rounds (0–10)", fixes, { fixes = it })
        Field("Agent time limit in minutes (10–120)", timeout, { timeout = it })
        Actions {
            Act("Save repair rounds", idle && fixes.toIntOrNull() in 0..10) { m.action("Save repair rounds", listOf("set", "fixRounds", fixes)) }
            Act("Save time limit", idle && timeout.toIntOrNull() in 10..120) { m.action("Save agent time limit", listOf("set", "agentTimeout", timeout)) }
        }
    }
    Fold("Skills", "Guides the agents follow: ${m.skills.objects().count { it.optBoolean("on", true) }} on", Icons.Outlined.Extension) {
        Muted("Each skill teaches the agents one kind of feature with tested code. Switch off the ones you never want used.")
        m.skills.objects().forEach { skill ->
            val name = skill.text("name", skill.text("id"))
            if (name.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) { Text(name, style = MaterialTheme.typography.bodyLarge); Muted(skill.text("blurb")) }
                Switch(checked = skill.optBoolean("on", true), enabled = idle, onCheckedChange = { m.action("Change skill", listOf("skill", name, if (it) "on" else "off")) })
            }
        }
        Act("Refresh skills", idle && m.toolsInstalled) { m.action("Load skills", listOf("skills"), true) }
    }
    Fold("Build tools", if (m.installing) "Installing…" else if (m.ready) "Ready to build Android apps" else "Not set up yet", Icons.Outlined.Build, open = !m.ready) {
        Muted("Hatchlybox has its own Linux, Android SDK, projects, agent sign-ins and keys. It installs them by itself in the background on the first start, checks them every time it starts and repairs anything missing.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Automatic updates", style = MaterialTheme.typography.bodyLarge)
                Muted(if (m.autoUpdate) "Updates the agents and Linux once a day, in the background." else "Off. Use Update now when you want the latest agents.")
            }
            Switch(checked = m.autoUpdate, onCheckedChange = m::chooseAutoUpdate)
        }
        if (m.installing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (m.failure.isNotBlank()) Text(m.failure, color = MaterialTheme.colorScheme.error)
        Actions {
            Button(onClick = { m.install() }, enabled = idle) { Text(if (m.toolsInstalled) "Update now" else "Set up now") }
            Act("Check tools", idle && m.toolsInstalled) { m.action("Check build tools", listOf("doctor"), true) }
            Act("Setup progress", MakerRuntime.running) { m.showConsole = true }
        }
        if (m.setupLog.isNotBlank()) {
            var show by remember { mutableStateOf(false) }
            TextButton(onClick = { show = !show }) { Text(if (show) "Hide setup log" else "Show setup log") }
            if (show) SelectionContainer { Text(m.setupLog.takeLast(12000), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        }
    }
    SettingsHeader("Phone actions")
    ActionsSettings(m)
    RemindersSettings()
    ActionLogSettings()
    SettingsHeader("Sharing and publishing")
    Fold("Phone connection", "Optional: lets agents see apps running", Icons.Outlined.PhoneAndroid) {
        Muted("Installing uses Android's installer and needs nothing here. Pairing wireless debugging also lets the agents open, screenshot and check the apps they build.")
        Act("Open developer settings") { runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }.onFailure { context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } }
        Muted("Turn on Wireless debugging. Under Pair device with pairing code, enter its port and six-digit code here. Then enter the port shown on the main Wireless debugging screen.")
        Field("Pairing port", pairing, { pairing = it }); Field("Pairing code", code, { code = it })
        Act("Pair", idle && pairing.toIntOrNull() in 1..65535 && code.matches(Regex("[0-9]{6}"))) { m.action("Pair phone", listOf("pair", pairing, code), true); code = "" }
        Field("Connection port", port, { port = it })
        Actions {
            Act("Connect", idle && port.toIntOrNull() in 1..65535) { m.action("Connect phone", listOf("connect", port), true) }
            Act("Check connection", idle && m.toolsInstalled) { m.action("Check ADB", listOf("adb"), true) }
        }
    }
    Fold("GitHub", "${m.github.text("state", "Not connected").replaceFirstChar { it.uppercase() }} · new apps: ${m.github.text("account", "only on this phone")}", Icons.Outlined.Code) {
        Muted("Choose which accounts Hatchlybox may use. Each app keeps syncing with the account that owns its repository.")
        Actions {
            Act("Add account", idle && m.toolsInstalled) { m.action("Connect GitHub", listOf("github", "add"), true) }
            Act("Refresh accounts", idle && m.toolsInstalled) { m.action("Refresh GitHub", listOf("github", "status"), true) }
            Act(if (m.github.optBoolean("auto", true)) "Keep new apps on this phone" else "Put new apps on GitHub", idle && m.github.text("state") == "connected") { m.action("New app storage", listOf("github", "auto", if (m.github.optBoolean("auto", true)) "off" else "on")) }
            Act("Sync changes", idle && m.toolsInstalled) { m.action("Sync GitHub", listOf("github", "sync"), true) }
        }
        m.github.optJSONArray("accounts")?.objects().orEmpty().forEach { a ->
            Text(a.text("login"), fontWeight = FontWeight.SemiBold)
            Actions {
                Act("Use for new apps", idle) { m.action("Select GitHub account", listOf("github", "use", a.text("login"))) }
                Act("Resume sync", idle) { m.action("Resume account", listOf("github", "resume", a.text("login"))) }
                Act("Stop using", idle) { ask("Stop syncing this account's apps? The GitHub login stays signed in.") { m.action("Stop using account", listOf("github", "logout", a.text("login"))) } }
                Act("Sign out", idle) { ask("Forget this GitHub login inside Hatchlybox? Its repositories and local projects stay.") { m.action("Sign out GitHub", listOf("github", "signout", a.text("login")), true) } }
            }
        }
    }
    Fold("Google Play account", m.play.text("state", "Not connected").replaceFirstChar { it.uppercase() }, Icons.Outlined.Key) {
        Muted(m.play.text("email"))
        Muted("Choose the service account JSON key from Google Cloud. It stays in Hatchlybox's private storage. Give it only the release and listing permissions you need in Play Console.")
        Actions {
            Act("Import service account", idle && m.toolsInstalled) { keyPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
            Act("Check Play account", idle && m.toolsInstalled) { m.action("Check Google Play", listOf("play", "status"), true) }
            Act("Disconnect", idle && m.play.text("state") == "connected") { ask("Remove the Google Play service account from Hatchlybox?") { m.action("Disconnect Play", listOf("play", "disconnect"), true) } }
            Act("Play Console") { m.external("https://play.google.com/console") }
        }
        Text("Publishing keys", style = MaterialTheme.typography.titleSmall)
        Actions {
            Act("Lock with a passphrase", idle && m.toolsInstalled) { m.action("Lock publishing keys", listOf("lock"), true) }
            Act("Change passphrase", idle && m.toolsInstalled) { m.action("Change key passphrase", listOf("lock", "passwd"), true) }
            Act("Turn lock off", idle && m.toolsInstalled) { ask("Remove passphrase protection from the publishing keys?") { m.action("Unlock stored keys", listOf("lock", "off"), true) } }
        }
        var contact by rememberSaveable { mutableStateOf("") }
        Field("Developer contact email", contact, { contact = it })
        Act("Save contact", idle && contact.contains("@")) { m.action("Save contact", listOf("play", "set", "contact", contact.trim())) }
        Actions {
            FilterChip(selected = m.play.optBoolean("personal", true), enabled = idle && m.toolsInstalled, onClick = { m.action("Account type", listOf("play", "set", "personal", "yes")) }, label = { Text("Personal account") })
            FilterChip(selected = !m.play.optBoolean("personal", true), enabled = idle && m.toolsInstalled, onClick = { m.action("Account type", listOf("play", "set", "personal", "no")) }, label = { Text("Organization") })
        }
    }
    Fold("Backups", "${m.backups().size} saved", Icons.Outlined.Backup) {
        Muted("Restore an encrypted project backup under a new name. Existing projects are never replaced.")
        Act("Restore a backup", idle && m.toolsInstalled) { restorePicker.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) }
        m.backups().forEach { file -> Act("Share ${file.name}") { m.shareBackup(file) } }
    }
    SettingsHeader("Storage")
    TasksAndStorageSettings(m)
    if (m.deletedSlug.isNotBlank()) Fold("Recently deleted", m.deletedSlug, Icons.Outlined.Delete) {
        Muted("Restore the most recently removed project. Existing projects are never replaced.")
        Act("Undo delete", idle) { m.undoDelete() }
    }
    // Which build this is: the app's version (stable or nightly) and its engine.
    val version = remember {
        val app = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
        val engine = runCatching { context.assets.open("maker/VERSION").bufferedReader().readText().trim() }.getOrDefault("")
        "Hatchlybox $app" + if (engine.isNotBlank()) " · engine $engine" else ""
    }
    var legal by remember { mutableStateOf<String?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { legal = "PRIVACY.md" }) { Text("Privacy policy") }
        TextButton(onClick = { legal = "THIRD-PARTY.md" }) { Text("Open-source licences") }
    }
    legal?.let { LegalText(it) { legal = null } }
    Muted(version + " · free software under the GNU AGPL 3.0")
}

// ---------- Google Play for one app ----------

@Composable
private fun MakerPublishApp(m: MakerModel, a: JSONObject, ask: (String, () -> Unit) -> Unit) {
    val slug = a.text("slug"); val pp = a.optJSONObject("play") ?: JSONObject()
    val idle = !m.busy && !MakerRuntime.running && !m.installing && !m.godotInstalling
    var track by rememberSaveable(slug) { mutableStateOf("internal") }
    var group by rememberSaveable(slug) { mutableStateOf(pp.text("group")) }
    var testers by rememberSaveable(slug) { mutableStateOf(pp.text("testers", "0")) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var listing by rememberSaveable { mutableStateOf("") }
    var notesEditing by rememberSaveable { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val uploadKeyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { m.restoreUploadKey(a, it) } }
    Fact("Version", "${pp.text("vn", "not prepared")}${pp.text("vc").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}")
    Fact("Play account", m.play.text("state", "not connected"))

    Text("1 · Prepare the release", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Muted("The agent writes the store listing, privacy policy and what's new, and draws the store pictures.")
    Actions {
        Button(onClick = { m.action("Prepare Play release", listOf("play", "prepare", slug, m.agent)); m.page = "job" }, enabled = idle && m.ready && a.has("apk") && !a.has("pending")) { Text(if (pp.has("prepared")) "Prepare a new version" else "Prepare release") }
        Act("Rewrite listing", idle && pp.has("prepared")) { m.action("Rewrite store listing", listOf("play", "prepare", slug, m.agent, "listing")); m.page = "job" }
        Act("Edit listing", idle && pp.has("prepared")) { listing = runCatching { m.file(a, "play/listing.json").readText() }.getOrDefault("{}"); editing = true }
        Act("Edit what's new", idle && pp.has("prepared")) { notes = runCatching { m.file(a, "play/release-notes.txt").readText() }.getOrDefault(pp.text("notes")); notesEditing = true }
        Act("Share privacy policy", pp.has("prepared")) {
            val text = runCatching { m.file(a, "play/privacy-policy.md").readText() }.getOrDefault("")
            if (text.isNotBlank()) context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share privacy policy"))
        }
    }
    ReleasePictures(m, a)

    Text("2 · Keep the upload key safe", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Actions {
        Act("Back up upload key", idle && pp.has("prepared")) { m.action("Back up upload key", listOf("play", "backup", slug), true) }
        Act("Share key backup") { m.shareUploadKey(a) }
        Act("Restore upload key", idle) { uploadKeyPicker.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) }
        Act("I saved the backup", idle && pp.has("prepared")) { ask("Have you saved the encrypted upload-key backup and kept its passphrase somewhere else?") { m.action("Record saved backup", listOf("play", "set", slug, "backedup")) } }
    }

    Text("3 · Review and upload", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Muted("The first bundle goes up by hand in Play Console; after that, Hatchlybox uploads them.")
    Actions {
        Act("Review release", idle && pp.has("prepared")) { m.action("Review exact Play release", listOf("play", "review", slug), true) }
        Act("Share .aab", pp.has("prepared")) { m.artifact(a, bundle = true) }
        Act("Check first upload", idle && pp.has("prepared") && m.play.text("state") == "connected") { m.action("Check Play package", listOf("play", "check", slug), true) }
        Act("Play Console") { m.external("https://play.google.com/console") }
    }
    Actions { listOf("internal", "closed", "production").forEach { t -> FilterChip(selected = track == t, onClick = { track = t }, label = { Text(t) }) } }
    val production = m.play.optBoolean("personal", true).not() || pp.optBoolean("production")
    Button(onClick = { ask("Send this reviewed release to the $track track on Google Play?") { m.action("Upload Play release", listOf("play", "upload", slug, track)); m.page = "job" } },
        enabled = idle && pp.has("prepared") && pp.optBoolean("linked") && m.play.text("state") == "connected" && (track != "production" || production)) { Text("Upload to $track") }

    Text("4 · Testers", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Field("Tester Google Group email", group, { group = it })
    Act("Save group", idle && group.contains("@")) { m.action("Save tester group", listOf("play", "testers", slug, group.trim())) }
    Field("Active testers", testers, { testers = it })
    Act("Save tester count", idle && (testers.toIntOrNull() ?: -1) >= 0) { m.action("Record testers", listOf("play", "set", slug, "testers", testers)) }
    Actions {
        Act("Copy opt-in link") { clipboard.setText(AnnotatedString("https://play.google.com/apps/testing/${a.text("pkg")}")) }
        Act("Invite testers") {
            val text = "Test ${a.text("label")} for 14 days. Join https://groups.google.com/g/${group.substringBefore('@')} then opt in: https://play.google.com/apps/testing/${a.text("pkg")}"
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Invite testers"))
        }
        Act("Ask the community", idle) { m.action("Ask tester community", listOf("play", "ask", slug), true) }
        Act(if (pp.optBoolean("production")) "Clear production access" else "Google granted production", idle) {
            ask("Record that Google ${if (pp.optBoolean("production")) "has not granted" else "has granted"} production access for this app?") { m.action("Record production access", listOf("play", "set", slug, "production", if (pp.optBoolean("production")) "no" else "yes")) }
        }
    }
    Muted("Your Play Console shows the testing rules for your account. The tester count here is only your note.")
    CommunityTesters(m, idle)
    if (editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text("Store listing (JSON)") }, text = { OutlinedTextField(listing, { listing = it }, modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()), label = { Text("Listing") }) }, confirmButton = { TextButton(onClick = { m.saveListing(a, listing); editing = false }) { Text("Save") } }, dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } })
    if (notesEditing) AlertDialog(onDismissRequest = { notesEditing = false }, title = { Text("What's new") }, text = { Field("Release notes", notes, { notes = it }, 4) }, confirmButton = { TextButton(onClick = { m.saveNotes(a, notes); notesEditing = false }) { Text("Save") } }, dismissButton = { TextButton(onClick = { notesEditing = false }) { Text("Cancel") } })
}

@Composable
private fun CommunityTesters(m: MakerModel, idle: Boolean) {
    var show by remember { mutableStateOf(false) }
    TextButton(onClick = { show = !show }) { Text(if (show) "Hide community testers" else "Community testers") }
    if (!show) return
    Act("Refresh community", idle && m.toolsInstalled) { m.action("Community testers", listOf("play", "community"), true) }
    m.community.text("groupUrl").takeIf { it.startsWith("https://groups.google.com/") }?.let { url -> Act("Join the tester group") { m.external(url) } }
    val others = m.community.optJSONArray("apps")?.objects().orEmpty().filter { c -> m.apps.none { it.text("pkg") == c.text("pkg") } }
    if (others.isEmpty()) Muted("No community apps loaded. Refresh to check the tester exchange.")
    others.forEach { c ->
        Text(c.text("title", c.text("pkg")), style = MaterialTheme.typography.bodyLarge)
        Actions {
            Act("Opt in") { m.external("https://play.google.com/apps/testing/${c.text("pkg")}") }
            Act("Install from Play") { m.external("https://play.google.com/store/apps/details?id=${c.text("pkg")}") }
            val on = m.testing.has(c.text("pkg"))
            Act(if (on) "Stop testing" else "I'm testing it", idle) { m.action("Record community test", listOf("play", "testing", c.text("pkg"), if (on) "off" else "on")) }
        }
    }
}

// ---------- the task console ----------

@Composable
private fun MakerConsole(m: MakerModel) {
    var input by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    var follow by rememberSaveable { mutableStateOf(true) }
    val text = MakerRuntime.output
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(text, follow) { if (follow && !scroll.isScrollInProgress) scroll.scrollTo(scroll.maxValue) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Muted(if (MakerRuntime.running) "Running. Sign-in and passphrase prompts appear below: answer them in the box." else "Finished${MakerRuntime.code?.let { " (code $it)" } ?: ""}. You can go back.")
        Surface(Modifier.weight(1f).fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium) {
            SelectionContainer { Text(text.ifBlank { "Starting…" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.verticalScroll(scroll).padding(12.dp)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = follow, onCheckedChange = { follow = it }); Text("Follow output", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy") }
        }
        val urls = remember(text) { Regex("https?://[^\\s<>\"\\u001b]+").findAll(text).map { it.value.trimEnd('.', ',', ')') }.distinct().toList().takeLast(4) }
        if (urls.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState())) { urls.forEach { url -> TextButton(onClick = { m.external(url) }) { Text("Open ${Uri.parse(url).host ?: "link"}") } } }
        if (MakerRuntime.running) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(input, { input = it }, label = { Text("Reply or passphrase") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium)
                Button(onClick = { MakerRuntime.send(input + "\r"); input = "" }) { Text("Send") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Enter" to "\r", "Tab" to "\t", "↑" to "\u001b[A", "↓" to "\u001b[B", "Ctrl+C" to "\u0003").forEach { (label, value) -> Act(label) { MakerRuntime.send(value) } }
            }
        }
        TextButton(onClick = { m.showConsole = false }) { Text("Back") }
    }
}

@Composable
private fun ReleasePictures(m: MakerModel, a: JSONObject) {
    var show by remember { mutableStateOf(false) }
    TextButton(onClick = { show = !show }) { Text(if (show) "Hide store pictures" else "See store pictures") }
    if (show) {
        val files = listOf(m.file(a, "play/icon.png"), m.file(a, "play/feature.png")) + m.file(a, "play/shots").listFiles().orEmpty().filter { it.extension == "png" }.take(8)
        files.filter { it.isFile }.forEach { file ->
            val bitmap = remember(file.path, file.lastModified()) { android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 3 }) }
            if (bitmap != null) {
                Text(file.name, style = MaterialTheme.typography.labelSmall)
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Store picture ${file.name}", modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(MaterialTheme.shapes.medium))
            }
        }
        if (files.none { it.isFile }) Muted("No store pictures yet. Prepare the release first.")
    }
}

/** Settings → Actions: the local model, how sensitive actions check it's you, the hands in other apps, and every action. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionsSettings(m: MakerModel) {
    val context = LocalContext.current
    var handsOn by remember { mutableStateOf(HandsService.enabled(context)) }
    var restricted by remember { mutableStateOf(HandsService.restricted(context)) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { handsOn = HandsService.enabled(context); restricted = HandsService.restricted(context) }
        }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    Fold("Actions", "${Hands.all().size} things it can do · " + when (Assist.state) { "ready" -> "local model ready"; "downloading" -> "getting the model"; else -> "no local model yet" },
        Icons.Outlined.AutoAwesome) {
        Muted("Type what you want in the create box (\"text Ana I'm late\", \"close YouTube\", \"remind me at 6 to call mum\"): Hatchlybox suggests the action, you tap it. Sensitive ones check it's you first.")
        // The local model.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Local model", style = MaterialTheme.typography.bodyLarge)
                Muted(when (Assist.state) {
                    "ready" -> "On this phone: understands requests in your words, offline, in about a second."
                    "downloading" -> "Getting it… ${(Assist.progress * 100).toInt()}%"
                    else -> "FunctionGemma, ${Assist.MODEL_MB} MB: needed for requests in your own words (the simple ones work without it)."
                })
                if (Assist.message.isNotBlank()) Text(Assist.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            when (Assist.state) {
                "ready" -> TextButton(onClick = { Assist.remove(context) }) { Text("Remove") }
                "downloading" -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else -> FilledTonalButton(onClick = { Legal.getModel(context) }) { Text("Get it") }
            }
        }
        HorizontalDivider()
        // The owner's check.
        Text("Before sensitive actions (calls, messages, uninstalling)", style = MaterialTheme.typography.bodyLarge)
        Guard.choices.forEach { (key, label) ->
            val can = Guard.available(context, key)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = can) { Guard.choose(context, key) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = Guard.method == key, onClick = { Guard.choose(context, key) }, enabled = can)
                Column {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = if (can) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                    if (!can) Muted("Not set up on this phone")
                }
            }
        }
        HorizontalDivider()
        // The hands in other apps.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Actions in other apps", style = MaterialTheme.typography.bodyLarge)
                Muted(if (handsOn) "On: it can press Send in WhatsApp, close apps, take screenshots and lock the phone for you."
                    else "Off. Switch on Hatchlybox actions in Android's Accessibility settings to send WhatsApp messages, close apps, take screenshots and lock the phone. It reads the screen only while your action runs.")
            }
            if (!handsOn && !restricted) FilledTonalButton(onClick = { startOutside(context, android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Switch on") }
        }
        // Android blocks it for apps installed from a file until it's allowed in the app's info.
        if (!handsOn && restricted) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Android is blocking this (\"Restricted setting\")", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                Text("Android does this for apps installed from a file. To allow it:\n" +
                    "1. Tap Try switching on, tap Hatchlybox and try to turn it on. Android says it's restricted: tap OK.\n" +
                    "2. Tap Open app info, then ⋮ in the top corner → Allow restricted settings, and confirm it's you.\n" +
                    "3. Come back and tap Try switching on again: it works now.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { startOutside(context, android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Try switching on") }
                    FilledTonalButton(onClick = {
                        startOutside(context, android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)))
                    }) { Text("Open app info") }
                }
            }
        }
        // AI helpers on the screen: each session asks first, unless switched off here.
        var screenMode by remember { mutableStateOf(AgentScreen.mode(context)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AI helpers can use the screen", style = MaterialTheme.typography.bodyLarge)
                Muted(if (screenMode == "off") "Off: agents can't read or tap the screen."
                    else "They ask first, then for 30 minutes can read the screen and tap, type and scroll in any app, with a gold cursor showing where. Never payments, passwords or permissions." +
                        if (AgentScreen.active) " In use now." else "")
            }
            Switch(checked = screenMode != "off", onCheckedChange = { screenMode = if (it) "ask" else "off"; AgentScreen.setMode(context, screenMode) })
        }
        if (AgentScreen.active) TextButton(onClick = { AgentScreen.stop() }) { Text("Stop the AI helper using the screen") }
        HorizontalDivider()
        // Every action, and teaching a new one.
        Text("What it can do", style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Hands.all().forEach { h ->
                Row(Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(h.icon, null, Modifier.size(14.dp), tint = if (h.custom != null) Color(0xFFE2B33C) else MaterialTheme.colorScheme.primary)
                    Text(h.title, style = MaterialTheme.typography.labelMedium)
                    if (h.level == Level.SECURE) Icon(Icons.Outlined.Fingerprint, "Checks it's you", Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        var teach by rememberSaveable { mutableStateOf("") }
        Field("Teach it something new", teach, { teach = it })
        Muted("Describe what it should do, like \"tell me the next bus from my stop\" or \"turn on the living room lights\". An AI helper builds the action; it shows up above.")
        Button(onClick = { CustomActions.build(context, m, teach.trim()); teach = "" }, enabled = teach.isNotBlank() && m.agentsInstalled) { Text("Teach it") }
    }
}
