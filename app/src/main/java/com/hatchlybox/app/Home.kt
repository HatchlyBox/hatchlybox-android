package com.hatchlybox.nightly

import android.text.format.DateUtils
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val Ideas = listOf(
    "A habit tracker with streaks",
    "A tip calculator that splits the bill",
    "A pomodoro focus timer",
    "Flashcards for learning words",
    "A shopping list I can share as text",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(model: AppModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Web apps") },
                actions = {
                    IconButton(onClick = { model.screen = Page.Settings }) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { inner ->
        val env = model.env
        if (env != Env.Ready) {
            CenteredColumn(Modifier.padding(inner)) { SetupWindow(model, env) }
            return@Scaffold
        }
        CenteredColumn(Modifier.padding(inner).consumeWindowInsets(inner).imePadding(), maxWidth = 1000) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                modifier = Modifier.navigationBarsPadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { BuildBox(model) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.padding(top = 20.dp, start = 4.dp), verticalAlignment = Alignment.Bottom) {
                        Text("Your apps", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                        if (model.projects.isNotEmpty()) Text(
                            "${model.projects.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (model.projects.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyLibrary() }
                items(model.projects, key = { it.id }) { p -> AppCard(model, p) }
            }
        }
    }
}

@Composable
private fun BuildBox(model: AppModel) {
    var idea by rememberSaveable { mutableStateOf("") }
    val agent = model.selected
    val status = model.status[agent] ?: Status.UNKNOWN
    val canBuild = idea.isNotBlank() && status != Status.MISSING && status != Status.DISCONNECTED
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "What do you want to build?",
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() },
        )
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp)) {
                TextField(
                    value = idea,
                    onValueChange = { idea = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp),
                    placeholder = { Text("Describe your app: what it does, what's on the screen, anything it should remember…") },
                    minLines = 3,
                    maxLines = 8,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ModelPicker(model)
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { model.build(idea); idea = "" },
                        enabled = canBuild,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Build")
                    }
                }
            }
        }
        when (status) {
            Status.DISCONNECTED -> Notice("${agent.label} isn't connected. Sign in to build with it.", "Connect") { model.connect(agent) }
            Status.MISSING -> Notice("${agent.label} isn't installed. Installing the tools again usually fixes it.", "Install") { model.install() }
            else -> {}
        }
        if (idea.isBlank()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Ideas.forEach { s -> SuggestionChip(onClick = { idea = s }, label = { Text(s, maxLines = 1) }) }
        }
    }
}

// Which model builds the next app. Each app keeps the model it was started with.
@Composable
private fun ModelPicker(model: AppModel) {
    var open by remember { mutableStateOf(false) }
    val agent = model.selected
    Box {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Model: ${agent.label}. Change model" },
        ) {
            StatusDot(model.status[agent] ?: Status.UNKNOWN)
            Spacer(Modifier.width(8.dp))
            Text(agent.label)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Agent.entries.forEach { a ->
                val s = model.status[a] ?: Status.UNKNOWN
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(a.label)
                            Text(statusText(s, a.account, true), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { StatusDot(s) },
                    onClick = { model.choose(a); open = false },
                )
            }
        }
    }
}

@Composable
private fun Notice(text: String, action: String, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun EmptyLibrary() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Widgets, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            Text("No apps yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Every app you build lands here. Open it, ask for changes, or pick up where the model left off.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

// A tile for the app: a coloured letter, its name, and what it's doing now.
@Composable
fun AppIcon(project: Project, size: Int = 48) {
    val palette = listOf(
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer,
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer,
    )
    val (bg, fg) = palette[(project.id.hashCode() and 0x7fffffff) % palette.size]
    Surface(color = bg, shape = RoundedCornerShape((size * 0.3f).dp), modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                project.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•",
                color = fg, fontWeight = FontWeight.Bold,
                style = if (size >= 48) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            )
        }
    }
}

fun projectState(project: Project): String = when {
    project.chat.running -> if (project.hasApp) "Updating…" else "Building…"
    project.chat.entries.lastOrNull()?.let { it.kind == Kind.INFO && it.text != "Stopped" } == true -> "Needs attention"
    project.hasApp -> "Ready"
    else -> "No app yet"
}

@Composable
private fun AppCard(model: AppModel, project: Project) {
    val state = projectState(project)
    ElevatedCard(
        onClick = { model.screen = Page.App(project.id) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                AppIcon(project)
                Spacer(Modifier.weight(1f))
                if (project.hasApp && !project.chat.running) FilledTonalIconButton(onClick = { model.screen = Page.Run(project.id) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Open ${project.name}")
                }
            }
            Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    project.chat.running -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                    state == "Needs attention" -> Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                    state == "Ready" -> Surface(color = Color(0xFF2E9E5B), shape = CircleShape, modifier = Modifier.size(8.dp)) {}
                }
                Text(
                    "$state · ${project.agent.label}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state == "Needs attention") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                DateUtils.getRelativeTimeSpanString(project.created).toString(),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
