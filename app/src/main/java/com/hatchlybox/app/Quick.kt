package com.hatchlybox.nightly

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.Locale

// Quick actions in the create box: typed words that do something on the phone right away ("open maps",
// "call Ana", "text Ana see you soon", "whatsapp Ana", "timer 5 min"...). They only ever show as small
// suggestions next to the box, with the rest of a match shown faded after the cursor; nothing runs until
// one is pressed (or Enter, when the whole text is a command with a match), so a free prompt is never in the way.

/** One suggestion. complete: what the box would read for it (for the faded rest). icon: drawn at a size in dp. */
internal class Quick(val key: String, val label: String, val hint: String, val complete: String, val icon: @Composable (Int) -> Unit,
                     val target: String? = null, val gold: Boolean = true, val go: () -> Unit)

/** A contact with a number or address. */
internal class Person(val name: String, val value: String)

/** What the typed text asks for: the command word, the rest, and whether it needs the phone's contacts. */
internal class Command(val verb: String, val kind: String, val rest: String) {
    val contacts get() = kind == "call" || kind == "text" || kind == "whatsapp" || kind == "email"
}

private val verbs = linkedMapOf(
    "abre" to "open", "abrir" to "open", "abrir app" to "open", "abrir aplicativo" to "open",
    "ligar" to "call", "llamar" to "call", "mensagem" to "text", "mensaje" to "text", "buscar" to "search", "pesquisar" to "search",
    "temporizador" to "timer", "alarme" to "alarm", "alarma" to "alarm",
    "open" to "open", "launch" to "open", "start" to "open", "run" to "open", "go to" to "open",
    "call" to "call", "ring" to "call", "phone" to "call", "dial" to "call",
    "text" to "text", "sms" to "text", "message" to "text", "msg" to "text",
    "whatsapp" to "whatsapp", "wa" to "whatsapp",
    "email" to "email", "mail" to "email",
    "search" to "search", "google" to "search", "look up" to "search",
    "youtube" to "youtube",
    "navigate to" to "map", "directions to" to "map", "take me to" to "map", "navigate" to "map", "map" to "map", "maps" to "map", "where is" to "map",
    "timer" to "timer", "alarm" to "alarm", "wake me at" to "alarm", "wake me up at" to "alarm",
)

/** The command at the start of the text, if it starts with one (the word alone, or followed by a space). */
internal fun commandOf(text: String): Command? {
    val t = text.trimStart()
    val low = t.lowercase(Locale.getDefault())
    // Longest first, so "wake me up at" wins over "wake me at" and "maps" over "map".
    for (v in verbs.keys.sortedByDescending { it.length }) {
        if (low == v || low.startsWith("$v ")) return Command(t.take(v.length), verbs.getValue(v), t.drop(v.length).trimStart())
    }
    return null
}

/** Phone settings that "open" finds too. */
private val settingsPages = listOf(
    "Wi-Fi" to Settings.ACTION_WIFI_SETTINGS, "Bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
    "Settings" to Settings.ACTION_SETTINGS, "Battery" to Intent.ACTION_POWER_USAGE_SUMMARY,
    "Display" to Settings.ACTION_DISPLAY_SETTINGS, "Sound" to Settings.ACTION_SOUND_SETTINGS,
    "Location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Apps" to Settings.ACTION_APPLICATION_SETTINGS,
    "Mobile data" to Settings.ACTION_DATA_ROAMING_SETTINGS, "Airplane mode" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
)

internal fun hasContacts(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

/**
 * Contacts whose name matches the start of the query (any word of the name), or that the query starts with
 * (then the rest is the message): numbers for "phone", addresses for "email". Starred ones when the query is empty.
 * Blocking: call off the main thread.
 */
internal fun findPeople(context: Context, query: String, kind: String): List<Person> {
    if (!hasContacts(context)) return emptyList()
    val email = kind == "email"
    val uri = if (email) ContactsContract.CommonDataKinds.Email.CONTENT_URI else ContactsContract.CommonDataKinds.Phone.CONTENT_URI
    val name = ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
    val value = if (email) ContactsContract.CommonDataKinds.Email.ADDRESS else ContactsContract.CommonDataKinds.Phone.NUMBER
    val words = query.trim().split(' ').filter { it.isNotEmpty() }.map { it.replace("%", "").replace("_", "") }
    val (where, args) = when {
        words.isEmpty() -> "${ContactsContract.Contacts.STARRED} = 1" to null
        words.size == 1 -> "$name LIKE ?" to arrayOf("%${words[0]}%")
        else -> words.joinToString(" AND ") { "$name LIKE ?" } to words.map { "%$it%" }.toTypedArray()
    }
    val found = ArrayList<Person>()
    runCatching {
        context.contentResolver.query(uri, arrayOf(name, value, ContactsContract.Data.IS_SUPER_PRIMARY), where, args, "${ContactsContract.Data.IS_SUPER_PRIMARY} DESC, $name ASC")?.use { c ->
            while (c.moveToNext() && found.size < 200) {
                val n = c.getString(0) ?: continue; val v = c.getString(1) ?: continue
                found += Person(n, v)
            }
        }
    }
    val q = query.trim().lowercase(Locale.getDefault())
    fun score(p: Person): Int {
        val n = p.name.lowercase(Locale.getDefault())
        return when {
            q.isEmpty() -> 1
            q == n || q.startsWith("$n ") -> 4
            n.startsWith(q) -> 3
            n.split(' ').any { it.startsWith(q) } -> 2
            q.contains(' ') && n.split(' ').any { w -> q.startsWith(w) } -> 1
            else -> 0
        }
    }
    return found.distinctBy { it.name to it.value }.map { it to score(it) }.filter { it.second > 0 }.sortedByDescending { it.second }.take(8).map { it.first }
}

/** The message after a contact's name, when the text names the whole contact and goes on ("Ana see you soon"). */
private fun messageAfter(rest: String, p: Person): String =
    if (rest.lowercase(Locale.getDefault()).startsWith(p.name.lowercase(Locale.getDefault()) + " ")) rest.drop(p.name.length).trim() else ""

/** Starts an outside app; false when nothing on the phone can take it. */
internal fun startOutside(context: Context, intent: Intent): Boolean =
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)

private fun vector(v: ImageVector, tint: Color? = null): @Composable (Int) -> Unit = { s ->
    Icon(v, null, Modifier.size((s * 0.62f).dp), tint = tint ?: MaterialTheme.colorScheme.primary)
}

private fun initial(name: String): @Composable (Int) -> Unit = { s ->
    Box(Modifier.size(s.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/** A whole duration in seconds from "5 min", "90s", "1h 30m", "10" (minutes), or 0. */
internal fun durationOf(text: String): Int {
    val t = text.trim().lowercase(Locale.ROOT).replace(',', '.')
        .replace(Regex("""\bhoras?\b"""), "hours").replace(Regex("""\bminutos?\b"""), "minutes").replace(Regex("""\bsegundos?\b"""), "seconds")
    if (t.matches(Regex("""\d+(?:\.\d+)?"""))) return (t.toDouble() * 60).takeIf { it.isFinite() && it in 1.0..604800.0 }?.toInt() ?: 0
    val token = Regex("""(\d+(?:\.\d+)?)\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)""")
    var end = 0; var seconds = 0.0
    for (match in token.findAll(t)) {
        if (t.substring(end, match.range.first).isNotBlank()) return 0
        val unit = match.groupValues[2]
        seconds += match.groupValues[1].toDouble() * when { unit.startsWith("h") -> 3600; unit.startsWith("m") -> 60; else -> 1 }
        end = match.range.last + 1
    }
    return if (end == t.length && seconds.isFinite() && seconds in 1.0..604800.0) seconds.toInt() else 0
}

/** An hour and minute from "7", "7:30", "7.30", "7am", "7:30 pm", "19:05", or null. */
internal fun timeOf(text: String): Pair<Int, Int>? {
    val r = Regex("""(\d{1,2})(?:[:.h](\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?""").matchEntire(text.trim().lowercase(Locale.ROOT)) ?: return null
    var h = r.groupValues[1].toInt(); val minute = r.groupValues[2].toIntOrNull() ?: 0
    val period = r.groupValues[3]
    if (period.isNotBlank() && h !in 1..12) return null
    if (period.startsWith("p") && h < 12) h += 12
    if (period.startsWith("a") && h == 12) h = 0
    return if (h in 0..23 && minute in 0..59) h to minute else null
}

/**
 * The suggestions for what's typed. apps: the library's items ("open", and the text alone when it's a short
 * start of an app's name). people: contacts found for the command (looked up by the caller, off the main thread).
 */
internal fun quickActions(context: Context, m: MakerModel, text: String, apps: List<LibItem>, people: List<Person>, withApps: Boolean = true): List<Quick> {
    val t = text.trim()
    if (t.isEmpty() || t.contains('\n')) return emptyList()
    val cmd = commandOf(t)
    val out = ArrayList<Quick>()
    fun appMatches(q: String, max: Int): List<LibItem> {
        val apps = Hands.distinctApps(apps)
        val low = q.lowercase(Locale.getDefault())
        if (low.isEmpty()) return m.recent.mapNotNull { k -> apps.firstOrNull { it.key == k } }.take(max)
        return (apps.filter { it.label.lowercase(Locale.getDefault()).startsWith(low) }.sortedByDescending { m.uses[it.key] ?: 0 } +
            apps.filter { a -> val l = a.label.lowercase(Locale.getDefault()); !l.startsWith(low) && l.split(' ', '-', '.').any { it.startsWith(low) } }
                .sortedByDescending { m.uses[it.key] ?: 0 }).distinctBy { it.key }.take(max)
    }
    fun openApp(a: LibItem, verb: String) = Quick("app:" + a.key, a.label, "Open", "$verb ${a.label}", a.icon, target = a.key) { m.used(a.key); a.open() }
    // Calls and messages go through the hands: straight from here, after the owner's check.
    fun hand(name: String, vararg args: Pair<String, Any?>): () -> Unit = { Hands.byName(name)?.let { Act.perform(context, m, Choice(it, mapOf(*args)), apps) } }
    fun setting(q: String) = settingsPages.filter { (n, _) -> q.isNotEmpty() && n.lowercase(Locale.getDefault()).startsWith(q.lowercase(Locale.getDefault())) }
    if (cmd == null) {
        // The text alone: only a short start of an app's name, so a prompt never fills up with apps.
        if (!withApps || t.length < 2 || t.split(' ').size > 3) return emptyList()
        return appMatches(t, 3).map { openApp(it, "").let { q -> Quick(q.key, q.label, q.hint, it.label, q.icon, q.target, go = q.go) } }
    }
    val rest = cmd.rest
    val verb = cmd.verb
    when (cmd.kind) {
        "open" -> {
            if (withApps) appMatches(rest, 6).forEach { out += openApp(it, verb) }
            setting(rest).take(2).forEach { (n, action) ->
                out += Quick("set:$n", n, "Phone settings", "$verb $n", vector(Icons.Outlined.Settings)) { if (!startOutside(context, Intent(action))) m.notice = "This phone has no $n settings page." }
            }
        }
        "call" -> {
            val digits = rest.filter { it.isDigit() || it == '+' }
            if (digits.length >= 3 && rest.none { it.isLetter() }) out += Quick("dial:$digits", rest, "Call", "$verb $rest", vector(Icons.Outlined.Call), gold = false, go = hand("call", "contact" to digits))
            people.forEach { p -> out += Quick("call:${p.name}:${p.value}", p.name, "Call · ${p.value}", "$verb ${p.name}", initial(p.name), gold = false, go = hand("call", "contact" to p.value)) }
        }
        "text" -> people.forEach { p ->
            val body = messageAfter(rest, p)
            out += Quick("text:${p.name}:${p.value}", p.name, if (body.isNotEmpty()) "Text “${body.take(24)}”" else "Text · ${p.value}", "$verb ${p.name}", initial(p.name),
                gold = false, go = if (body.isNotEmpty()) hand("send_text", "contact" to p.value, "message" to body) else ({
                    if (!startOutside(context, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(p.value))))) m.notice = "No messages app on this phone." }))
        }
        "whatsapp" -> {
            val wa = m.phoneApps.firstOrNull { it.pkg == "com.whatsapp" } ?: m.phoneApps.firstOrNull { it.pkg == "com.whatsapp.w4b" }
            if (wa == null && m.phoneApps.isNotEmpty()) out += Quick("wa:none", "WhatsApp isn't installed", "", "", vector(Icons.Outlined.Info)) {}
            else {
                people.forEach { p ->
                    val body = messageAfter(rest, p)
                    val number = p.value.filter { it.isDigit() }
                    out += Quick("wa:${p.name}:${p.value}", p.name, if (body.isNotEmpty()) "WhatsApp “${body.take(24)}”" else "WhatsApp", "$verb ${p.name}", initial(p.name),
                        gold = false, go = if (body.isNotEmpty()) hand("whatsapp_message", "contact" to p.value, "message" to body) else ({
                            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number")).apply { wa?.let { setPackage(it.pkg) } }
                            if (!startOutside(context, i)) startOutside(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number"))) }))
                }
                if (rest.isEmpty() && wa != null) out += Quick("wa:open", "WhatsApp", "Open", "$verb", vector(Icons.AutoMirrored.Outlined.Message)) { m.launch(wa) }
            }
        }
        "email" -> people.forEach { p ->
            out += Quick("mail:${p.name}:${p.value}", p.name, "Email · ${p.value}", "$verb ${p.name}", initial(p.name)) {
                if (!startOutside(context, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(p.value))))) m.notice = "No email app on this phone."
            }
        }
        "search" -> if (rest.isNotEmpty()) out += Quick("search", "Search “${rest.take(28)}”", "Web", "$verb $rest", vector(Icons.Outlined.Search)) {
            if (!startOutside(context, Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, rest)))
                startOutside(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(rest))))
        }
        "youtube" -> out += Quick("yt", if (rest.isEmpty()) "YouTube" else "“${rest.take(28)}” on YouTube", if (rest.isEmpty()) "Open" else "Search", "$verb $rest".trim(), vector(Icons.Outlined.SmartDisplay)) {
            startOutside(context, Intent(Intent.ACTION_VIEW, Uri.parse(if (rest.isEmpty()) "https://www.youtube.com" else "https://www.youtube.com/results?search_query=" + Uri.encode(rest))))
        }
        "map" -> if (rest.isNotEmpty()) {
            val go = verb.lowercase(Locale.getDefault()).let { it.startsWith("navigate") || it.startsWith("directions") || it.startsWith("take") }
            out += Quick("map", rest.take(32), if (go) "Directions" else "Map", "$verb $rest", vector(if (go) Icons.Outlined.Directions else Icons.Outlined.Map)) {
                val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(rest)))
                if (!(go && startOutside(context, nav)) && !startOutside(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(rest)))))
                    m.notice = "No maps app on this phone."
            }
        }
        "timer" -> durationOf(rest).takeIf { it > 0 }?.let { s ->
            val words = listOfNotNull((s / 3600).takeIf { it > 0 }?.let { "$it h" }, (s % 3600 / 60).takeIf { it > 0 }?.let { "$it min" }, (s % 60).takeIf { it > 0 }?.let { "$it s" }).joinToString(" ")
            out += Quick("timer", "Timer $words", "Start", "$verb $rest", vector(Icons.Outlined.Timer)) {
                if (!startOutside(context, Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s).putExtra(AlarmClock.EXTRA_SKIP_UI, false))) m.notice = "No clock app here takes timers."
            }
        }
        "alarm" -> timeOf(rest)?.let { (h, min) ->
            out += Quick("alarm", "Alarm %d:%02d".format(h, min), "Set", "$verb $rest", vector(Icons.Outlined.Alarm)) {
                if (!startOutside(context, Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, min))) m.notice = "No clock app here takes alarms."
            }
        }
    }
    return out
}

/** The suggestions as one slim row of small pills in the style's corners and border. */
@Composable
internal fun QuickRow(quick: List<Quick>, onPick: (Quick) -> Unit, modifier: Modifier = Modifier) {
    val look = LocalLook.current
    val shape = RoundedCornerShape(minOf(look.corner, 18f).dp)
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
        items(quick.size, key = { quick[it].key }) { i ->
            val q = quick[i]
            Row(
                Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = maxOf(0.8f, look.cardOpacity)))
                    .border(1.dp, lookBorder(look).let { if (it.alpha < 0.2f) MaterialTheme.colorScheme.outlineVariant else it }, shape)
                    .heightIn(min = 48.dp).clickable(onClickLabel = "${q.label}: ${q.hint}") { onPick(q) }.padding(start = 6.dp, end = 11.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { q.icon(22) }
                Column {
                    Text(q.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
                    if (q.hint.isNotBlank()) Text(q.hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
                }
            }
        }
    }
}
