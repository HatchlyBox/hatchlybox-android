package com.hatchlybox.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * What the person agrees to before Hatchlybox downloads anything: what it downloads, what leaves the phone, and
 * Google's Android SDK licence (Hatchlybox never accepts a licence for them). Shown once per [VERSION]; the texts it
 * links (privacy policy, open-source licences) are in assets/legal.
 */
object Legal {
    /** Raised when what the person agrees to changes: everyone sees the welcome again. */
    const val VERSION = 2
    const val SDK_LICENSE_URL = "https://developer.android.com/studio/terms"
    const val GEMMA_TERMS_URL = "https://ai.google.dev/gemma/terms"
    const val GEMMA_POLICY_URL = "https://ai.google.dev/gemma/prohibited_use_policy"

    private fun prefs(c: Context) = c.getSharedPreferences("legal", 0)
    var accepted by mutableStateOf<Boolean?>(null)
        private set

    fun accepted(c: Context): Boolean = accepted ?: (prefs(c).getInt("accepted", 0) >= VERSION).also { accepted = it }

    fun accept(c: Context) {
        prefs(c).edit().putInt("accepted", VERSION).putLong("at", System.currentTimeMillis()).apply()
        accepted = true
    }

    /** Asking for Google's Gemma terms ([GemmaTerms] shows them) before the local model downloads. */
    var askingGemma by mutableStateOf(false)
    /** Downloads the local model, once its terms are accepted (else asks first): true when it started. */
    fun getModel(c: Context): Boolean {
        if (!prefs(c).getBoolean("gemma", false)) { askingGemma = true; return false }
        Assist.download(c); return true
    }

    fun text(c: Context, name: String): String = runCatching { c.assets.open("legal/$name").bufferedReader().readText() }.getOrDefault("")
    fun licenseFiles(c: Context): List<String> = runCatching { c.assets.list("legal/licenses")?.sorted().orEmpty() }.getOrDefault(emptyList())

    fun open(c: Context, url: String) { startOutside(c, Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/**
 * The first screen: nothing downloads until the person continues. The mark, three short points and one button; the
 * full details fold out under "More details", and continuing is accepting Google's SDK licence (said under the button,
 * with the texts one tap away). Someone updating from before [Legal] sees it once too, worded for coming back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Welcome(onAccept: () -> Unit) {
    val context = LocalContext.current
    val back = remember { Linux(context).hasBasics }
    var more by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<String?>(null) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        CenteredColumn(Modifier.fillMaxSize().systemBarsPadding(), maxWidth = 560) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(48.dp))
                    Box(Modifier.size(96.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFF101828)), contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.ic_fg), null, Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(if (back) "Welcome back" else "Hatchlybox", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text("Say it. Hatch it.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text(if (back) "Hatchlybox now says plainly what it downloads and what it sends. One tap and you're back to your creations."
                        else "Describe an app, a game or a widget, and an AI helper makes it right here.",
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(28.dp))
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Point(Icons.Outlined.Lock, "Private by design", "No account, no analytics. Your creations stay on your devices.")
                        Point(Icons.Outlined.CloudDownload, "Free tools, in the background", "About 1 GB, set up while you look around.")
                        Point(Icons.Outlined.AutoAwesome, "Your AI, your account", "Requests go only to the AI helper you sign in to.")
                    }
                    TextButton(onClick = { more = !more }, modifier = Modifier.padding(top = 8.dp)) {
                        Text(if (more) "Fewer details" else "More details")
                        Icon(if (more) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                    }
                    AnimatedVisibility(more) {
                        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Detail("What it downloads", "Each from its own project: Alpine Linux, Node.js, the AI helpers (Claude Code, Codex, OpenCode), Java, Google's Android SDK, and Godot for games.")
                            Detail("What leaves the phone", "When an AI helper works, your request and the creation's files go to that helper's company (for example Anthropic for Claude, OpenAI for Codex), under your own account and their terms. Sync goes straight to your own devices, encrypted. GitHub and Google Play only when you connect them.")
                            Detail("Nothing is sent to us", "There's no Hatchlybox server that sees your work or how you use the app.")
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(onClick = { Legal.accept(context); onAccept() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(16.dp)) {
                        Text(if (back) "Accept and continue" else "Accept and start", style = MaterialTheme.typography.titleMedium)
                    }
                    Text("By continuing you accept Google's Android SDK License Agreement, which building Android apps needs, and Hatchlybox's privacy policy.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp))
                    FlowRow(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                        SmallLink("Google's licence") { Legal.open(context, Legal.SDK_LICENSE_URL) }
                        SmallLink("Privacy") { reading = "PRIVACY.md" }
                        SmallLink("Open-source licences") { reading = "THIRD-PARTY.md" }
                    }
                }
            }
        }
    }
    reading?.let { LegalText(it) { reading = null } }
}

@Composable
private fun Point(icon: ImageVector, title: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Detail(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SmallLink(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(label, style = MaterialTheme.typography.labelMedium) }
}

/** Google's Gemma terms, before the local model (FunctionGemma) downloads. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GemmaTerms() {
    if (!Legal.askingGemma) return
    val context = LocalContext.current
    AlertDialog(onDismissRequest = { Legal.askingGemma = false },
        title = { Text("Google's Gemma terms") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The local model is FunctionGemma, made by Google (${Assist.MODEL_MB} MB, from Hugging Face). Using it means accepting Google's Gemma Terms of Use, including its Prohibited Use Policy.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { Legal.open(context, Legal.GEMMA_TERMS_URL) }) { Text("Read the terms") }
                    TextButton(onClick = { Legal.open(context, Legal.GEMMA_POLICY_URL) }) { Text("Prohibited uses") }
                }
            }
        },
        confirmButton = { Button(onClick = {
            Legal.askingGemma = false
            context.getSharedPreferences("legal", 0).edit().putBoolean("gemma", true).apply()
            Assist.download(context)
        }) { Text("Accept and get it") } },
        dismissButton = { TextButton(onClick = { Legal.askingGemma = false }) { Text("Not now") } })
}

/**
 * One of the texts in assets/legal, full screen: PRIVACY.md, THIRD-PARTY.md (with the licences' full texts under
 * it) or a licence from legal/licenses.
 */
@Composable
fun LegalText(name: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var inner by remember { mutableStateOf<String?>(null) }
    val text = remember(name) { Legal.text(context, name) }
    val plain = name.startsWith("licenses/")
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(when (name) { "PRIVACY.md" -> "Privacy policy"; "THIRD-PARTY.md" -> "Open-source licences"; else -> name.removePrefix("licenses/").removeSuffix(".txt") },
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    TextButton(onClick = onClose) { Text("Close") }
                }
                HorizontalDivider()
                SelectionContainer(Modifier.weight(1f)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (text.isBlank()) Text("This text is missing from this build.")
                        if (plain) Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                        else Markdownish(text)
                        if (name == "THIRD-PARTY.md") {
                            Text("Full licence texts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                            Legal.licenseFiles(context).forEach { f ->
                                TextButton(onClick = { inner = "licenses/$f" }) { Text(f.removeSuffix(".txt")) }
                            }
                        }
                    }
                }
            }
        }
    }
    inner?.let { LegalText(it) { inner = null } }
}

/** Enough Markdown for the legal texts: headings, bullets, tables as lines, paragraphs. */
@Composable
private fun Markdownish(text: String) {
    text.lines().forEach { raw ->
        val l = raw.trimEnd()
        when {
            l.isBlank() -> Spacer(Modifier.height(4.dp))
            l.startsWith("# ") -> Text(l.removePrefix("# "), style = MaterialTheme.typography.headlineSmall)
            l.startsWith("## ") -> Text(l.removePrefix("## "), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            l.startsWith("### ") -> Text(l.removePrefix("### "), style = MaterialTheme.typography.titleSmall)
            l.startsWith("|") -> if (!l.matches(Regex("^\\|[-| :]+\\|$")))
                Text(l.trim('|').split("|").joinToString(" · ") { it.trim() }, style = MaterialTheme.typography.bodySmall)
            l.startsWith("- ") -> Text("•  " + l.removePrefix("- ").replace("**", "").replace("`", ""), style = MaterialTheme.typography.bodyMedium)
            else -> Text(l.replace("**", "").replace("`", ""), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
