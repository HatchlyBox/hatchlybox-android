package com.hatchlybox.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Locale

// Hatchlybox's hands: what it can do on this phone when asked in words. Each action says what it is (for the
// local model), the words that call for it (so the model only sees the few that fit), its details, and how
// careful to be: open (runs straight away), confirm (a tap), secure (a tap and the owner's fingerprint, face or
// screen lock, as chosen in Settings). Agents can add more: a folder in /root/actions (Custom actions).

enum class Level { OPEN, CONFIRM, SECURE }

class Param(val name: String, val type: String, val description: String, val enum: List<String> = emptyList())

/** What the action did, in words for the box, and (optionally) where the gold thread goes. */
class Done(val say: String = "", val ok: Boolean = true, val image: String? = null)

class Hand(
    val name: String, val title: String, val description: String, val words: List<String>, val params: List<Param>,
    val level: Level, val icon: ImageVector, val custom: File? = null,
    val run: suspend (HandCall) -> Done,
)

/** One action about to run: its details (as the model or the rules filled them) and what it can reach. */
class HandCall(val context: Context, val m: MakerModel, val args: Map<String, Any?>, val items: List<LibItem>) {
    fun str(k: String) = (args[k] as? String)?.trim().orEmpty()
    fun int(k: String) = when (val v = args[k]) { is Number -> v.toInt(); is String -> v.trim().toIntOrNull(); else -> null }
    fun bool(k: String) = when (val v = args[k]) { is Boolean -> v; is String -> v.equals("true", true); else -> null }
    fun start(i: Intent) = startOutside(context, i)
}

object Hands {
    /** The actions agents made, read from /root/actions. */
    val custom = androidx.compose.runtime.mutableStateListOf<Hand>()

    fun all(): List<Hand> = builtIn + AgentScreen.hands + custom

    /** The actions whose words appear in the text (the model chooses among only these, and "none"). */
    fun candidates(text: String): List<Hand> {
        val ws = Regex("[\\p{L}\\p{N}']+").findAll(text.lowercase(Locale.getDefault())).map { it.value }.toSet()
        return all().filter { h -> h.words.any { w -> if (' ' in w) text.lowercase(Locale.getDefault()).contains(w) else w in ws } }
    }

    fun byName(n: String) = all().firstOrNull { it.name == n }

    /** A name as people and agents say it: "Hello-World!", "the hello world app" and "Hello World" are the same. */
    private fun plain(s: String) = s.lowercase(Locale.getDefault()).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        .removePrefix("the ").removeSuffix(" app").removeSuffix(" game").trim()

    /** Each app once: a creation installed here is also among the phone's apps, under the same name. */
    fun distinctApps(items: List<LibItem>): List<LibItem> {
        val ours = items.filter { it.type != "phone" }.mapNotNull { it.pkg }.toSet()
        return items.filter { it.type != "phone" || it.pkg !in ours }
    }

    /** The names it goes by: its label, and a creation's slug ("hello-world"). */
    private fun names(i: LibItem) = listOfNotNull(plain(i.label),
        i.key.substringAfter(':').takeIf { i.type != "phone" && i.type != "site" && i.key.substringBefore(':') in setOf("apk", "made") }?.let(::plain)).distinct()

    /** How many letters apart two names are (a typo or two: "hellow world"). */
    private fun apart(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /** The apps the text could mean, best first: the same name, then names that start with it, contain it, or are a typo away. */
    private fun appCandidates(items: List<LibItem>, name: String): List<LibItem> {
        val q = plain(name)
        if (q.isEmpty()) return emptyList()
        val apps = distinctApps(items)
        val tiers = listOf<(String) -> Boolean>(
            { it == q }, { it.startsWith(q) }, { q.length >= 4 && it.contains(q) },
            { q.length >= 4 && apart(it, q) <= maxOf(1, q.length / 6) },
        )
        for (t in tiers) apps.filter { i -> names(i).any(t) }.takeIf { it.isNotEmpty() }?.let { return it }
        return emptyList()
    }

    /** The app the text names: among the library's items (apps made here and the phone's). */
    fun appNamed(items: List<LibItem>, name: String): LibItem? = appCandidates(items, name).let { all ->
        // Same name twice, but only one opens here (a creation not installed from here, and an APK installed by hand): that one.
        all.singleOrNull() ?: all.filter(::runs).let { r ->
            // Several entries that all start the same package (two creations of one app, an app with two launcher icons): it's one app.
            r.singleOrNull() ?: r.takeIf { it.isNotEmpty() && it.map { i -> i.pkg }.distinct().size == 1 }?.minByOrNull { if (it.type == "phone") 1 else 0 }
        }
    }

    /** It starts an app on this phone when opened (not a page in Hatchlybox). */
    private fun runs(i: LibItem) = i.type == "phone" || (i.type == "apk" && i.pkg != null)

    /** Why no app was found, with what it could have meant, so whoever asked can say it again. */
    fun noApp(items: List<LibItem>, name: String): Done {
        val several = appCandidates(items, name)
        if (several.size > 1) return Done("Which one: " + several.take(5).joinToString { "\"${it.label}\"" + (it.pkg?.let { p -> " ($p)" } ?: "") } + "? Say its full name.", false)
        val words = plain(name).split(' ').filter { it.length >= 3 }
        val near = distinctApps(items).filter { i -> words.any { w -> plain(i.label).split(' ').any { it.startsWith(w) || apart(it, w) <= 1 } } }
        return Done("No app called ${name.trim()} here." + if (near.isEmpty()) "" else " Close names: " + near.take(5).joinToString { "\"${it.label}\"" } + ".", false)
    }

    private fun person(c: HandCall, kind: String = "phone"): Person? {
        val who = c.str("contact")
        val digits = who.filter { it.isDigit() || it == '+' }
        if (digits.length >= 3 && who.none { it.isLetter() }) return Person(who, digits)
        val matches = findPeople(c.context, who, kind)
        return matches.singleOrNull() ?: matches.filter { it.name.equals(who, true) }.singleOrNull()
    }

    private suspend fun needContacts(c: HandCall): Boolean =
        hasContacts(c.context) || Front.ask(c.context, Manifest.permission.READ_CONTACTS)

    private val builtIn: List<Hand> = listOf(
        Hand("open_app", "Open an app", "Open an app on the phone by its name", listOf("open", "launch", "start", "run", "go to", "abre", "abrir"),
            listOf(Param("app", "string", "The app's name, as said")), Level.OPEN, Icons.Outlined.Apps) { c ->
            val item = appNamed(c.items, c.str("app")) ?: return@Hand noApp(c.items, c.str("app"))
            val before = c.m.notice
            Reach.open(c.context, c.m, item)
            // Started means Hatchlybox left the front; if it's still there, say so (and why) instead of a plain done.
            if (runs(item) && withTimeoutOrNull(4000) { while (Gold.front) delay(100); true } == null)
                return@Hand Done("${item.label} didn't come up" + (c.m.notice.takeIf { it.isNotBlank() && it != before }?.let { ": $it" } ?: ": Android didn't start it.") +
                    " Don't tap Hatchlybox's screens for it; tell the user.", false)
            // An Android creation that isn't on the phone yet opens its page: whoever asked should know it didn't start.
            if (item.type == "apk" && item.pkg == null && item.install != null)
                Done("${item.label} isn't installed on this phone yet, so its page is open: ${item.install.first} is there. Android asks the user to confirm the install.", false)
            else Done()
        },
        Hand("close_app", "Close an app", "Close (stop) an app that is running", listOf("close", "shut", "quit", "kill", "stop", "exit", "fecha", "fechar"),
            listOf(Param("app", "string", "The app's name, as said")), Level.CONFIRM, Icons.Outlined.Close) { c ->
            val item = appNamed(c.items, c.str("app")) ?: return@Hand noApp(c.items, c.str("app"))
            val pkg = item.key.removePrefix("phone:").substringBefore('/').takeIf { item.key.startsWith("phone:") }
                ?: c.m.apps.firstOrNull { "apk:" + it.text("slug") == item.key }?.text("pkg") ?: return@Hand Done("Only apps can be closed.", false)
            closeApp(c, pkg, item.label)
        },
        Hand("call", "Call", "Phone call a contact or a number", listOf("call", "ring", "phone", "dial", "liga", "ligar"),
            listOf(Param("contact", "string", "Who to call: a name or a number")), Level.SECURE, Icons.Outlined.Call) { c ->
            if (c.str("contact").none { it.isDigit() } && !needContacts(c)) return@Hand Done("Allow contacts to call people by name.", false)
            val p = person(c) ?: return@Hand Done("No contact called ${c.str("contact")} with a number.", false)
            val direct = Front.ask(c.context, Manifest.permission.CALL_PHONE)
            c.start(Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(p.value))))
            Done(if (direct) "Calling ${p.name}." else "Dialling ${p.name}: press call.")
        },
        Hand("send_text", "Text", "Send a text message (SMS) to a contact", listOf("text", "sms", "message", "msg", "tell", "mensagem", "sms"),
            listOf(Param("contact", "string", "Who to text: a name or a number"), Param("message", "string", "What to say, in their words")), Level.SECURE, Icons.Outlined.Sms) { c ->
            if (c.str("contact").none { it.isDigit() } && !needContacts(c)) return@Hand Done("Allow contacts to text people by name.", false)
            val p = person(c) ?: return@Hand Done("No contact called ${c.str("contact")} with a number.", false)
            val body = c.str("message")
            if (body.isNotEmpty() && Front.ask(c.context, Manifest.permission.SEND_SMS)) {
                val sms = if (Build.VERSION.SDK_INT >= 31) c.context.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
                runCatching { sms.sendMultipartTextMessage(p.value, null, sms.divideMessage(body), null, null) }
                    .onSuccess { return@Hand Done("SMS submitted for ${p.name}; delivery is unverified: “$body”") }
            }
            c.start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(p.value))).putExtra("sms_body", body))
            Done("Ready to send to ${p.name}: press send.")
        },
        Hand("whatsapp_message", "WhatsApp", "Send a WhatsApp message to a contact", listOf("whatsapp", "wa", "zap", "tell"),
            listOf(Param("contact", "string", "Who to message"), Param("message", "string", "What to say, in their words")), Level.SECURE, Icons.AutoMirrored.Outlined.Message) { c ->
            if (c.str("contact").any { it.isLetter() } && !needContacts(c)) return@Hand Done("Allow contacts to message people by name.", false)
            val p = person(c) ?: return@Hand Done("No contact called ${c.str("contact")} with a number.", false)
            val wa = c.m.phoneApps.firstOrNull { it.pkg == "com.whatsapp" } ?: c.m.phoneApps.firstOrNull { it.pkg == "com.whatsapp.w4b" }
            val body = c.str("message")
            val link = "https://wa.me/" + p.value.filter { it.isDigit() } + if (body.isNotEmpty()) "?text=" + Uri.encode(body) else ""
            if (!c.start(Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply { wa?.let { setPackage(it.pkg) } })) return@Hand Done("WhatsApp didn't open.", false)
            val hands = HandsService.instance
            if (body.isNotEmpty() && hands != null) {
                val pkg = wa?.pkg ?: "com.whatsapp"
                if (hands.pressFirst(listOf("$pkg:id/send"), ButtonWords.send, 8000)) return@Hand Done("Pressed Send in WhatsApp for ${p.name}. Delivery is unverified: “$body”")
            }
            Done(if (body.isEmpty()) "WhatsApp chat with ${p.name}." else "Ready in WhatsApp: press send" + if (hands == null) " (switch on Hatchlybox's actions in Settings to send by itself)." else ".")
        },
        Hand("email", "Email", "Write an email to a contact", listOf("email", "mail", "e-mail"),
            listOf(Param("contact", "string", "Who to write to"), Param("subject", "string", "The subject"), Param("message", "string", "What to say")), Level.CONFIRM, Icons.Outlined.Email) { c ->
            val contact = c.str("contact")
            val to = if ('@' in contact) contact else {
                if (!needContacts(c)) return@Hand Done("Allow contacts or enter an email address.", false)
                findPeople(c.context, contact, "email").singleOrNull()?.value ?: return@Hand Done("Choose a specific email address; this name is missing or ambiguous.", false)
            }
            val ok = c.start(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(to))).putExtra(Intent.EXTRA_SUBJECT, c.str("subject")).putExtra(Intent.EXTRA_TEXT, c.str("message")))
            Done(if (ok) "Email ready: check it and send." else "No email app here.", ok)
        },
        Hand("set_timer", "Timer", "Start a countdown timer", listOf("timer", "countdown", "temporizador"),
            listOf(Param("seconds", "integer", "Its length in seconds"), Param("label", "string", "What it's for, or empty")), Level.OPEN, Icons.Outlined.Timer) { c ->
            val s = c.int("seconds")?.takeIf { it > 0 } ?: return@Hand Done("How long?", false)
            val ok = c.start(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .apply { c.str("label").takeIf { it.isNotEmpty() }?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } })
            Done(if (ok) "Timer: ${words(s)}." else "No clock app takes timers here.", ok)
        },
        Hand("set_alarm", "Alarm", "Set an alarm clock for a time of day", listOf("alarm", "wake", "despertador", "alarme"),
            listOf(Param("time", "string", "The time, as said (7, 7:30, 6am)"), Param("label", "string", "What it's for, or empty")), Level.OPEN, Icons.Outlined.Alarm) { c ->
            val (h, min) = timeOf(c.str("time")) ?: return@Hand Done("What time?", false)
            val ok = c.start(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, min).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .apply { c.str("label").takeIf { it.isNotEmpty() }?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } })
            Done(if (ok) "Alarm at %d:%02d.".format(h, min) else "No clock app takes alarms here.", ok)
        },
        Hand("remind_me", "Reminder", "Remind the user of something later, at a time or after a while", listOf("remind", "reminder", "lembra", "lembrete", "don't let me forget"),
            listOf(Param("what", "string", "What to be reminded of"), Param("when", "string", "When, in the user's words (in 10 minutes, at 6pm, tomorrow at 9)")), Level.OPEN, Icons.Outlined.NotificationsActive) { c ->
            val at = whenOf(c.str("when")) ?: return@Hand Done("When should I remind you?", false)
            val cal = Reminders.toCalendar(c.context) && Front.ask(c.context, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            val what = c.str("what").ifEmpty { "Reminder" }
            val where = Reminders.set(c.context, at, what, cal)
            // Said only once it's really there: read back from where it went.
            if (Reminders.between(c.context, at - 60_000, at + 60_000).none { it.what == what })
                return@Hand Done("Couldn't set that reminder: it isn't in ${if (where == "Hatchlybox") "Hatchlybox's list" else where} after saving it.", false)
            val shows = where != "Hatchlybox" || Build.VERSION.SDK_INT < 33 || Front.ask(c.context, Manifest.permission.POST_NOTIFICATIONS)
            Done("I'll remind you ${spoken(at)}: ${c.str("what")}." + (if (where != "Hatchlybox") " It's in your calendar ($where)." else "") +
                if (shows) "" else " Allow Hatchlybox's notifications to see it when it's time.")
        },
        Hand("list_reminders", "Your reminders", "Say which reminders and calendar events there are in a time (today, tomorrow, this week, a day)",
            listOf("reminders", "my reminders", "lembretes", "reminded", "agenda", "what's on", "schedule today", "my day"),
            listOf(Param("when", "string", "Which time, in the user's words: today, tomorrow, this week, friday… (empty: the next 30 days)")), Level.OPEN, Icons.Outlined.NotificationsActive) { c ->
            // The phone's calendar apps keep theirs in Android's calendar store: read it (once allowed) for the time asked.
            val read = PhoneCalendar.canRead(c.context) || Front.ask(c.context, Manifest.permission.READ_CALENDAR)
            val asked = c.str("when")
            val (from, to) = rangeOf(asked)
            val list = Reminders.between(c.context, from, to)
            val which = asked.takeIf { it.isNotBlank() && it != "all" }?.let { " $it" } ?: " in the next 30 days"
            val notes = listOfNotNull(
                "Allow calendar access to see your calendar apps' reminders too.".takeIf { !read },
                "Samsung Reminder keeps its own list, which other apps can't read.".takeIf { c.m.phoneApps.any { it.pkg == "com.samsung.android.app.reminder" } },
            ).joinToString(" ").let { if (it.isEmpty()) "" else "\n$it" }
            if (list.isEmpty()) Done("Nothing$which." + notes)
            else Done("Reminders and events$which:\n" + list.joinToString("\n") { e ->
                "• " + (if (e.allDay) spoken(e.at).substringBefore(" at") + ", all day" else spoken(e.at)) + ": ${e.what}" + (if (e.where != "Hatchlybox") " · ${e.where}" else "")
            } + notes)
        },
        Hand("cancel_reminder", "Cancel a reminder", "Cancel (delete) a reminder that is set", listOf("cancel", "delete", "remove", "forget"),
            listOf(Param("what", "string", "Which reminder: words from it")), Level.CONFIRM, Icons.Outlined.NotificationsOff) { c ->
            val q = c.str("what").lowercase(Locale.getDefault())
            val now = System.currentTimeMillis()
            val r = Reminders.between(c.context, now, now + 365L * 86_400_000).mapNotNull { it.mine }
                .firstOrNull { q.isNotEmpty() && (it.what.lowercase(Locale.getDefault()).contains(q) || q.contains(it.what.lowercase(Locale.getDefault()))) }
                ?: return@Hand Done("No reminder Hatchlybox set like “${c.str("what")}”.", false)
            Reminders.cancel(c.context, r)
            Done("Cancelled: ${r.what} (${spoken(r.at)}).")
        },
        Hand("add_event", "Calendar", "Add an event to the calendar", listOf("calendar", "event", "schedule", "meeting", "appointment", "agenda", "book"),
            listOf(Param("title", "string", "What it is"), Param("when", "string", "Its day and time, in the user's words")), Level.CONFIRM, Icons.Outlined.Event) { c ->
            val at = whenOf(c.str("when"))
            val i = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, c.str("title"))
            if (at != null) i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, at).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, at + 3_600_000)
            val ok = c.start(i)
            Done(if (ok) "Check it and save it in your calendar." else "No calendar app here.", ok)
        },
        Hand("flashlight", "Flashlight", "Turn the flashlight (torch) on or off", listOf("flashlight", "torch", "lanterna", "flash", "light", "lights"),
            listOf(Param("on", "boolean", "true to turn it on, false to turn it off")), Level.OPEN, Icons.Outlined.FlashlightOn) { c ->
            val cm = c.context.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: return@Hand Done("This phone has no flashlight.", false)
            val on = c.bool("on") ?: true
            runCatching { cm.setTorchMode(id, on) }.fold({ Done(if (on) "Flashlight on." else "Flashlight off.") }, { Done("The flashlight is busy.", false) })
        },
        Hand("volume", "Volume", "Change the sound volume: up, down, mute, unmute or max", listOf("volume", "louder", "quieter", "mute", "unmute", "sound", "som"),
            listOf(Param("command", "string", "What to do", listOf("up", "down", "mute", "unmute", "max"))), Level.OPEN, Icons.Outlined.VolumeUp) { c ->
            val am = c.context.getSystemService(AudioManager::class.java)
            val s = AudioManager.STREAM_MUSIC
            when (c.str("command")) {
                "down" -> am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "mute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                "unmute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                "max" -> am.setStreamVolume(s, am.getStreamMaxVolume(s), AudioManager.FLAG_SHOW_UI)
                else -> am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            }
            Done()
        },
        Hand("media", "Music control", "Control the music or video that's playing: play, pause, next track, previous track",
            listOf("pause", "resume", "skip", "next", "previous", "back", "song", "track", "music", "stop"),
            listOf(Param("command", "string", "What to do: next also means skip", listOf("play", "pause", "next", "previous"))), Level.OPEN, Icons.Outlined.PlayCircle) { c ->
            val key = when (c.str("command")) { "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE; "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; else -> KeyEvent.KEYCODE_MEDIA_PLAY }
            val am = c.context.getSystemService(AudioManager::class.java)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            Done()
        },
        Hand("play_music", "Play music", "Play a song, an artist, an album or a kind of music", listOf("play", "listen", "put on", "toca", "ouvir"),
            listOf(Param("query", "string", "What to play")), Level.OPEN, Icons.Outlined.MusicNote) { c ->
            val q = c.str("query")
            val ok = c.start(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*").putExtra(android.app.SearchManager.QUERY, q))
                || c.start(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(q))))
            Done(if (ok) "Playing $q." else "No music app here.", ok)
        },
        Hand("navigate", "Directions", "Directions or a map to a place", listOf("navigate", "directions", "map", "maps", "route", "take me", "how do i get", "where is"),
            listOf(Param("place", "string", "Where to")), Level.OPEN, Icons.Outlined.Directions) { c ->
            val q = c.str("place")
            val ok = c.start(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(q)))) || c.start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))))
            Done(if (ok) "" else "No maps app here.", ok)
        },
        Hand("search_web", "Search", "Search the web for something", listOf("search", "google", "look up", "find out", "pesquisa"),
            listOf(Param("query", "string", "What to search for")), Level.OPEN, Icons.Outlined.Search) { c ->
            val q = c.str("query")
            c.start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q)) || c.start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
            Done()
        },
        Hand("settings_panel", "Phone settings", "Show a phone setting: wifi, bluetooth, mobile data, battery, display, sound, location, airplane mode",
            listOf("wifi", "wi-fi", "bluetooth", "data", "battery", "brightness", "display", "location", "airplane", "settings", "hotspot", "nfc"),
            listOf(Param("which", "string", "Which setting", listOf("wifi", "bluetooth", "internet", "battery", "display", "sound", "location", "airplane", "hotspot", "nfc", "all"))), Level.OPEN, Icons.Outlined.Settings) { c ->
            val action = when (c.str("which")) {
                "wifi" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
                "internet" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS
                "nfc" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_NFC else Settings.ACTION_NFC_SETTINGS
                "sound" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS
                "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS; "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY; "display" -> Settings.ACTION_DISPLAY_SETTINGS
                "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS; "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS; "hotspot" -> Settings.ACTION_WIRELESS_SETTINGS
                else -> Settings.ACTION_SETTINGS
            }
            Done(ok = c.start(Intent(action)))
        },
        Hand("battery", "Battery", "Say how much battery is left", listOf("battery", "charge", "bateria"), emptyList(), Level.OPEN, Icons.Outlined.BatteryFull) { c ->
            val bm = c.context.getSystemService(BatteryManager::class.java)
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            Done("Battery: $pct%" + if (bm.isCharging) ", charging." else ".")
        },
        Hand("screenshot", "Screenshot", "Take a screenshot", listOf("screenshot", "screen shot", "capture", "print screen"), emptyList(), Level.OPEN, Icons.Outlined.Screenshot) { c ->
            val h = HandsService.instance ?: return@Hand Done("Switch on Hatchlybox's actions in Settings for screenshots.", false)
            delay(300)
            if (Build.VERSION.SDK_INT >= 28) h.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            Done()
        },
        Hand("lock_phone", "Lock", "Lock the phone (turn the screen off)", listOf("lock", "bloqueia", "screen off"), emptyList(), Level.OPEN, Icons.Outlined.Lock) { c ->
            val h = HandsService.instance ?: return@Hand Done("Switch on Hatchlybox's actions in Settings to lock the phone.", false)
            if (Build.VERSION.SDK_INT >= 28) h.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            Done()
        },
        Hand("do_not_disturb", "Do not disturb", "Turn do not disturb (silence) on or off", listOf("disturb", "silence", "silent", "dnd", "quiet", "focus"),
            listOf(Param("on", "boolean", "true to turn it on")), Level.CONFIRM, Icons.Outlined.DoNotDisturbOn) { c ->
            val nm = c.context.getSystemService(NotificationManager::class.java)
            if (!nm.isNotificationPolicyAccessGranted) {
                c.start(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                return@Hand Done("Allow Hatchlybox to change Do not disturb, then ask again.", false)
            }
            val on = c.bool("on") ?: true
            nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
            Done(if (on) "Do not disturb is on." else "Do not disturb is off.")
        },
        Hand("take_photo", "Camera", "Open the camera to take a photo or video", listOf("photo", "picture", "selfie", "camera", "foto", "video"), emptyList(), Level.OPEN, Icons.Outlined.PhotoCamera) { c ->
            Done(ok = c.start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)))
        },
        Hand("copy_text", "Copy", "Copy some text to the clipboard", listOf("copy", "clipboard", "copia"),
            listOf(Param("text", "string", "The text to copy")), Level.OPEN, Icons.Outlined.ContentCopy) { c ->
            c.context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Hatchlybox", c.str("text")))
            Done("Copied.")
        },
        Hand("note", "Note", "Write down a note to keep (a thought, a list, something to remember)", listOf("note", "write down", "jot", "remember that", "nota", "anota"),
            listOf(Param("text", "string", "The note")), Level.OPEN, Icons.Outlined.EditNote) { c ->
            val f = File(c.context.filesDir, "notes.md")
            f.appendText("- " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(java.util.Date()) + "  " + c.str("text") + "\n")
            Done("Noted: ${c.str("text")}")
        },
        Hand("change_theme", "Theme", "Change Hatchlybox's look to another theme", listOf("theme", "look", "colours", "colors", "style", "tema"),
            listOf(Param("theme", "string", "The theme's name, as said")), Level.OPEN, Icons.Outlined.Palette) { c ->
            val q = c.str("theme").lowercase(Locale.getDefault())
            val t = Themes.firstOrNull { it.name.lowercase(Locale.getDefault()) == q } ?: Themes.firstOrNull { q.isNotEmpty() && it.name.lowercase(Locale.getDefault()).contains(q) }
                ?: return@Hand Done("No theme called ${c.str("theme")}. Themes: ${Themes.joinToString(", ") { it.name }}.", false)
            Look.choosePreset(c.context, t); Done("Theme: ${t.name}.")
        },
        Hand("uninstall_app", "Uninstall", "Uninstall (remove) an app from the phone", listOf("uninstall", "remove", "delete", "desinstala"),
            listOf(Param("app", "string", "The app's name")), Level.SECURE, Icons.Outlined.DeleteForever) { c ->
            val item = appNamed(c.items, c.str("app")) ?: return@Hand noApp(c.items, c.str("app"))
            val go = item.uninstall ?: return@Hand Done("${item.label} can't be uninstalled.", false)
            go(); Done()
        },
        Hand("new_action", "Teach a new action", "Teach Hatchlybox a new thing it can do on the phone, which none of the others does",
            listOf("teach", "learn", "new action", "automate", "every time", "whenever"), listOf(Param("what", "string", "What it should learn to do")), Level.CONFIRM, Icons.Outlined.School) { c ->
            CustomActions.build(c.context, c.m, c.str("what")); Done("An AI helper is making that action. It shows up here when it's ready.")
        },
    )

    /** Closes an app: Android's own Force stop, pressed by the hands; else back home and let Android stop it. */
    private suspend fun closeApp(c: HandCall, pkg: String, label: String): Done {
        val hands = HandsService.instance
        if (hands != null) {
            c.start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
            if (hands.pressFirst(listOf("com.android.settings:id/force_stop_button", "com.android.settings:id/button3"), ButtonWords.forceStop, 5000)) {
                delay(300)
                hands.pressFirst(listOf("android:id/button1"), ButtonWords.ok, 3000)
                delay(250)
                hands.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                return Done("$label is closed.")
            }
            hands.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        }
        val am = c.context.getSystemService(android.app.ActivityManager::class.java)
        runCatching { am.killBackgroundProcesses(pkg) }
        return Done(if (hands == null) "$label is in the background now. Switch on Hatchlybox's actions in Settings to close apps fully." else "$label is in the background.")
    }
}

/** A length in words: 90 → "1 min 30 s". */
internal fun words(s: Int) = listOfNotNull((s / 3600).takeIf { it > 0 }?.let { "$it h" }, (s % 3600 / 60).takeIf { it > 0 }?.let { "$it min" }, (s % 60).takeIf { it > 0 }?.let { "$it s" }).joinToString(" ")

/** When, from the user's words: "in 10 minutes", "at 6pm", "tomorrow at 9", "friday 3pm", "tonight", "noon". Epoch ms, or null. */
internal fun whenOf(text: String, now: Calendar = Calendar.getInstance()): Long? {
    val t = text.lowercase(Locale.getDefault())
    if (Regex("""\bin\s+\d""").containsMatchIn(t) || Regex("""^\s*\d+\s*(h|hours?|m|mins?|minutes?|s|secs?|seconds?)\b""").containsMatchIn(t)) {
        val s = durationOf(t.substringAfter("in "))
        if (s > 0) return now.timeInMillis + s * 1000L
    }
    val c = now.clone() as Calendar
    var dayGiven = false
    when {
        "tomorrow" in t || "amanhã" in t -> { c.add(Calendar.DAY_OF_YEAR, 1); dayGiven = true }
        "today" in t || "hoje" in t -> dayGiven = true
    }
    val days = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
    days.indexOfFirst { it in t }.takeIf { it >= 0 }?.let { d ->
        var add = (d + 1 - c.get(Calendar.DAY_OF_WEEK) + 7) % 7
        if (add == 0) add = 7
        c.add(Calendar.DAY_OF_YEAR, add); dayGiven = true
    }
    val time = when {
        "noon" in t || "midday" in t -> 12 to 0
        "midnight" in t -> 0 to 0
        "tonight" in t -> timeOf(t.substringAfter("tonight"))?.let { (h, m) -> (if (h < 12) h + 12 else h) to m } ?: (20 to 0)
        "morning" in t -> timeOf(t) ?: (9 to 0)
        "afternoon" in t -> timeOf(t)?.let { (h, m) -> (if (h < 12) h + 12 else h) to m } ?: (15 to 0)
        "evening" in t -> timeOf(t)?.let { (h, m) -> (if (h < 12) h + 12 else h) to m } ?: (19 to 0)
        else -> Regex("""(?:at\s+|às\s+|^|\s)(\d{1,2}(?:[:.h]\d{2})?\s*(?:am|pm|a\.m\.|p\.m\.)?)""").findAll(t).mapNotNull { timeOf(it.groupValues[1]) }.lastOrNull()
    } ?: if (dayGiven) 9 to 0 else return null
    c.set(Calendar.HOUR_OF_DAY, time.first); c.set(Calendar.MINUTE, time.second); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    // A time already past today, with no day said: tomorrow.
    if (!dayGiven && c.timeInMillis <= now.timeInMillis) c.add(Calendar.DAY_OF_YEAR, 1)
    return c.timeInMillis
}

/** Days from today to that moment: 0 today, 1 tomorrow. */
internal fun dayOffset(at: Long): Int {
    fun start(t: Long) = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    return Math.round((start(at) - start(System.currentTimeMillis())) / 86_400_000.0).toInt()
}

internal fun spoken(at: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = at }
    val day = when (dayOffset(at)) {
        0 -> "today"; 1 -> "tomorrow"; else -> java.text.SimpleDateFormat("EEEE d MMM", Locale.getDefault()).format(c.time)
    }
    return "$day at %d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}

/**
 * Reminders. When the phone has a calendar Hatchlybox can write to (Samsung Calendar, Google Calendar…), a
 * reminder goes there as an event with an alert at its time, so it shows in that app and the calendar app
 * notifies; reminders.json keeps which events Hatchlybox made (to cancel them). Without a calendar (or its
 * permission, or with Settings → Reminders → Save to the calendar off) Hatchlybox keeps the reminder itself
 * and notifies from Android's alarm: exact when Android allows it, else within a few minutes.
 * Listing reads both: the phone's calendars for the time asked, and Hatchlybox's own.
 */
object Reminders {
    private const val CHANNEL = "reminders"
    class Reminder(val id: Int, val at: Long, val what: String, val eventId: Long? = null)
    /** One thing in a time range: a reminder or calendar event; [mine] when Hatchlybox made it (it can be cancelled here). */
    class Entry(val at: Long, val what: String, val where: String, val mine: Reminder?, val allDay: Boolean = false)
    /** Changes whenever the list does, for screens showing it. */
    var version by androidx.compose.runtime.mutableIntStateOf(0)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("hands", Context.MODE_PRIVATE)
    /** Settings → Reminders: put them in the phone's calendar when there is one. */
    fun toCalendar(c: Context) = prefs(c).getBoolean("remindersToCalendar", true)
    fun setToCalendar(c: Context, on: Boolean) { prefs(c).edit().putBoolean("remindersToCalendar", on).apply(); version++ }

    private fun file(c: Context) = File(c.applicationContext.filesDir, "reminders.json")
    @Synchronized private fun load(c: Context): List<Reminder> = runCatching {
        val a = JSONArray(file(c).readText())
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Reminder(it.optInt("id"), it.optLong("at"), it.optString("what"), it.optLong("event", -1).takeIf { e -> e >= 0 }) }
    }.getOrDefault(emptyList())
    @Synchronized private fun save(c: Context, list: List<Reminder>) {
        val a = JSONArray(list.sortedBy { it.at }.map { JSONObject().put("id", it.id).put("at", it.at).put("what", it.what).apply { it.eventId?.let { e -> put("event", e) } } })
        File(file(c).path + ".tmp").apply { writeText(a.toString(1)); renameTo(file(c)) }
        version++
    }

    /** Sets one; returns where it went: the calendar's name, or "Hatchlybox". */
    fun set(c: Context, at: Long, what: String, calendar: Boolean): String {
        var id = (at / 1000 % Int.MAX_VALUE).toInt()
        val list = load(c).filter { it.at > System.currentTimeMillis() - 86_400_000 }   // gone the day after
        while (list.any { it.id == id }) id++
        val cal = if (calendar) PhoneCalendar.writable(c) else null
        val event = cal?.let { PhoneCalendar.add(c, it.first, at, what) }
        save(c, list + Reminder(id, at, what, event))
        if (event == null) arm(c, Reminder(id, at, what))
        return if (event != null) cal.second else "Hatchlybox"
    }

    /** Everything from [from] to [to]: the phone's calendars (with their permission) and Hatchlybox's own. */
    fun between(c: Context, from: Long, to: Long): List<Entry> {
        val local = load(c)
        val events = if (PhoneCalendar.canRead(c)) PhoneCalendar.between(c, from, to) else emptyList()
        return (local.filter { it.eventId == null && it.at in from..to }.map { Entry(it.at, it.what, "Hatchlybox", it) } +
            events.map { e -> Entry(e.begin, e.title, e.calendar, local.firstOrNull { it.eventId == e.eventId }, e.allDay) }).sortedBy { it.at }
    }

    fun cancel(c: Context, r: Reminder) {
        if (r.eventId != null) PhoneCalendar.delete(c, r.eventId)
        else c.getSystemService(AlarmManager::class.java).cancel(intent(c, r.id, ""))
        save(c, load(c).filter { it.id != r.id })
    }

    /** Sets Hatchlybox's own alarms again: after the phone restarts, or the app was updated. */
    fun rearm(c: Context) = load(c).filter { it.eventId == null && it.at > System.currentTimeMillis() }.forEach { arm(c, it) }

    private fun intent(c: Context, id: Int, what: String) = PendingIntent.getBroadcast(c, id, Intent(c, ReminderReceiver::class.java).putExtra("what", what).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun arm(c: Context, r: Reminder) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = intent(c, r.id, r.what)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
    }

    fun show(c: Context, what: String, id: Int) {
        val n = android.app.Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_stat_hatchlybox).setContentTitle("Reminder").setContentText(what)
            .setAutoCancel(true).setContentIntent(PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify(70000 + id % 10000, n) }
    }
}

/** The phone's calendars (every calendar app keeps its events in Android's calendar store). */
object PhoneCalendar {
    class Event(val eventId: Long, val title: String, val begin: Long, val calendar: String, val allDay: Boolean)

    fun canRead(c: Context) = c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun canWrite(c: Context) = c.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** The calendar reminders go in: the main one the user can write to (Google's first). Its id and name. */
    fun writable(c: Context): Pair<Long, String>? = runCatching {
        if (!canWrite(c)) return@runCatching null
        val cols = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.VISIBLE)
        c.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, cols, null, null, null)?.use { cur ->
            var best: Pair<Long, String>? = null; var score = -1
            while (cur.moveToNext()) {
                if (cur.getInt(4) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR || cur.getInt(5) == 0) continue
                val s = (if (cur.getInt(2) == 1) 4 else 0) + (if (cur.getString(3) == "com.google") 2 else 0) + (if (cur.getString(3) != CalendarContract.ACCOUNT_TYPE_LOCAL) 1 else 0)
                if (s > score) { score = s; best = cur.getLong(0) to (cur.getString(1) ?: "Calendar") }
            }
            best
        }
    }.getOrNull()

    /** An event at that time with an alert at its start: the calendar app reminds. Its id, or null. */
    fun add(c: Context, calendar: Long, at: Long, what: String): Long? = runCatching {
        val v = android.content.ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendar); put(CalendarContract.Events.TITLE, what)
            put(CalendarContract.Events.DESCRIPTION, "Reminder set with Hatchlybox")
            put(CalendarContract.Events.DTSTART, at); put(CalendarContract.Events.DTEND, at + 15 * 60_000)
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id); put(CalendarContract.Events.HAS_ALARM, 1)
        }
        val id = c.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)?.lastPathSegment?.toLongOrNull() ?: return@runCatching null
        c.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, android.content.ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, id); put(CalendarContract.Reminders.MINUTES, 0); put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        })
        id
    }.getOrNull()

    fun delete(c: Context, eventId: Long) = runCatching {
        c.contentResolver.delete(android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null)
    }

    /** What's on in every calendar from [from] to [to] (repeating ones too), soonest first. */
    fun between(c: Context, from: Long, to: Long): List<Event> = runCatching {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { android.content.ContentUris.appendId(it, from); android.content.ContentUris.appendId(it, to) }.build()
        val cols = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.VISIBLE)
        c.contentResolver.query(uri, cols, null, null, CalendarContract.Instances.BEGIN)?.use { cur ->
            buildList { while (cur.moveToNext()) if (cur.getInt(5) != 0) add(Event(cur.getLong(0), cur.getString(1) ?: "(no title)", cur.getLong(2), cur.getString(3) ?: "Calendar", cur.getInt(4) == 1)) }
        }.orEmpty()
    }.getOrDefault(emptyList())
}

/** A time range from the user's words: "today", "tomorrow", "this week", "next week", "friday", "this month"; else the next 30 days. */
internal fun rangeOf(text: String, now: Calendar = Calendar.getInstance()): Pair<Long, Long> {
    val t = text.lowercase(Locale.getDefault())
    fun dayStart(add: Int) = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, add); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    fun days(start: Int, n: Int) = dayStart(start) to dayStart(start + n) - 1
    val weekday = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday").indexOfFirst { it in t }
    return when {
        "tomorrow" in t || "amanhã" in t -> days(1, 1)
        "today" in t || "tonight" in t || "hoje" in t -> days(0, 1)
        "next week" in t -> days(7, 7)
        "week" in t || "semana" in t -> days(0, 7)
        "month" in t || "mês" in t -> days(0, 31)
        weekday >= 0 -> { val add = (weekday + 1 - now.get(Calendar.DAY_OF_WEEK) + 7) % 7; days(add, 1) }
        else -> whenOf(t, now)?.let { at -> val d = dayOffset(at); days(d, 1) } ?: days(0, 30)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.show(context, intent.getStringExtra("what") ?: "Reminder", intent.getIntExtra("id", 0))
    }
}

/** After the phone restarts or Hatchlybox is updated, Android has forgotten the alarms: set them again. */
class ReminderBoot : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { Reminders.rearm(context) }
}

/**
 * Actions agents made: /root/actions/<name>/action.json ({name, title, description, words, params, level}) and
 * an executable `run` beside it. It runs in Hatchlybox's Linux with the details as JSON in $ARGS, and answers in
 * lines of JSON: {"say": "..."}, {"open": "https://..."}, {"intent": {"action", "data", "package", "extras"}}.
 */
object CustomActions {
    fun load(context: Context) {
        val app = context.applicationContext
        val dir = Linux(app).hostFile("/root/actions")
        val found = dir.listFiles().orEmpty().mapNotNull { d ->
            runCatching {
                val j = JSONObject(File(d, "action.json").readText())
                val name = j.getString("name").takeIf { it.matches(Regex("[a-z][a-z0-9_]{1,40}")) } ?: return@runCatching null
                val params = j.optJSONArray("params")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { p ->
                    Param(p.optString("name"), p.optString("type", "string"), p.optString("description"), p.optJSONArray("enum")?.let { e -> (0 until e.length()).map { e.optString(it) } }.orEmpty())
                } }.orEmpty()
                val words = j.optJSONArray("words")?.let { a -> (0 until a.length()).map { a.optString(it).lowercase(Locale.getDefault()) } }.orEmpty()
                // Never less careful than a tap: an agent wrote it.
                val level = if (j.optString("level") == "secure") Level.SECURE else Level.CONFIRM
                Hand("custom_$name", j.optString("title", name), j.optString("description"), words, params, level, Icons.Outlined.AutoAwesome, d) { c -> runCustom(c, d) }
            }.getOrNull()
        }
        custom.clear(); custom.addAll(found)
        PhoneBridge.writeList(app)
    }
    private val custom get() = Hands.custom

    private suspend fun runCustom(c: HandCall, d: File): Done = withContext(Dispatchers.IO) {
        val args = JSONObject(c.args.mapValues { it.value ?: JSONObject.NULL }).toString()
        val p = Linux(c.context.applicationContext).start("cd ${quote("/root/actions/" + d.name)} && exec ./run", mapOf("ARGS" to args), cwd = "/root/actions/${d.name}")
        p.outputStream.close()
        var say = ""
        p.inputStream.bufferedReader().forEachLine { line ->
            val j = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            j.optString("say").takeIf { it.isNotBlank() }?.let { say = it }
            j.optString("open").takeIf { it.startsWith("http") || it.startsWith("geo:") || it.startsWith("tel:") }?.let { startOutside(c.context, Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
            j.optJSONObject("intent")?.let { i ->
                val intent = Intent(i.optString("action", Intent.ACTION_VIEW))
                i.optString("data").takeIf { it.isNotBlank() }?.let { intent.data = Uri.parse(it) }
                i.optString("package").takeIf { it.isNotBlank() }?.let { intent.setPackage(it) }
                i.optJSONObject("extras")?.let { e -> e.keys().forEach { k -> intent.putExtra(k, e.optString(k)) } }
                startOutside(c.context, intent)
            }
        }
        val rc = p.waitFor()
        Done(say.ifBlank { if (rc == 0) "" else "That action didn't work (code $rc)." }, rc == 0)
    }

    /** An AI helper writes a new action for what was asked, in /root/actions, then it's loaded. */
    fun build(context: Context, m: MakerModel, what: String) {
        val agent = runCatching { Agent.valueOf(m.agent.uppercase()) }.getOrDefault(Agent.CLAUDE)
        val prompt = """
            Make a new action for Hatchlybox, the phone launcher you run inside (Alpine Linux in proot on Android, as root). The user asked: "$what".
            Create /root/actions/<name>/ (name: lower case letters, digits and _), with:
            - action.json: {"name": "<name>", "title": "Short title", "description": "What it does, one sentence, for a small model choosing actions",
              "words": ["the words a request for it would use", ...], "params": [{"name": "...", "type": "string|integer|boolean", "description": "..."}],
              "level": "confirm" (or "secure" if it sends, pays, deletes or shares anything)}
            - run: an executable (bash, python or node; chmod +x) that does it. Its details come as JSON in ${'$'}ARGS. It answers with lines of JSON on stdout:
              {"say": "a short result for the user"}, {"open": "https://... or geo: or tel: link"}, {"intent": {"action": "android.intent.action.VIEW", "data": "...", "package": "...", "extras": {"k": "v"}}}.
            It can use the internet, local files and Linux tools (install small ones if needed). Never put keys or passwords in the files; ask for none.
            Test run yourself with sample ARGS before you finish, and keep it fast (under 10 seconds). Then run `phone reload` and try it with `phone do custom_<name> '<details JSON>'`.
        """.trimIndent()
        AgentChat.send(context, agent, prompt, display = "Teach Hatchlybox a new action: $what")
        AgentChat.show()
        // When the helper is done, the new action is there.
        Setup.scope.launch(Dispatchers.Main) {
            while (AgentChat.running) delay(1000)
            load(context)
        }
    }
}

/** Runs an action the way every action runs: the owner's check for the sensitive ones, the gold thread, then the action. */
object Act {
    fun describe(h: Hand, a: Map<String, Any?>): String {
        fun s(k: String) = (a[k] as? String)?.trim().orEmpty()
        return when (h.name) {
            "send_text", "whatsapp_message" -> "${h.title} ${s("contact")}" + (s("message").takeIf { it.isNotEmpty() }?.let { ": “$it”" } ?: "")
            "call" -> "Call ${s("contact")}"
            "open_app" -> "Open ${s("app")}"; "close_app" -> "Close ${s("app")}"; "uninstall_app" -> "Uninstall ${s("app")}"
            "set_timer" -> "Timer " + ((a["seconds"] as? Number)?.toInt()?.let(::words) ?: "")
            "set_alarm" -> "Alarm ${s("time")}"; "flashlight" -> "Flashlight " + if (a["on"] == false) "off" else "on"
            "do_not_disturb" -> "Do not disturb " + if (a["on"] == false) "off" else "on"
            "remind_me" -> "Remind me ${s("when")}: ${s("what")}"
            "list_reminders" -> "Your reminders" + (s("when").takeIf { it.isNotEmpty() && it != "all" }?.let { " $it" } ?: "")
            "cancel_reminder" -> "Cancel the reminder: ${s("what")}"
            "screen_look" -> "Look at the screen"; "screen_tap" -> (if (a["long"] == true) "Long-press " else "Tap ") + s("target")
            "screen_aim" -> "Aim at ${s("target")}"; "screen_scroll" -> "Scroll ${s("direction").ifEmpty { "down" }}"; "screen_key" -> "Press ${s("key")}"
            "screen_type" -> "Type “${s("text")}”" + (s("into").takeIf { it.isNotEmpty() }?.let { " into $it" } ?: "") + if (a["enter"] == true) " ⏎" else ""
            else -> (listOf(h.title) + a.values.mapNotNull { (it as? String)?.trim()?.takeIf { v -> v.isNotEmpty() } ?: (it as? Number)?.toString() }).joinToString(" · ")
        }
    }

    /** Where its gold thread goes: the app's icon, when it's about an app that's on screen. */
    fun target(h: Hand, a: Map<String, Any?>, items: List<LibItem>): String? =
        if (h.name in setOf("open_app", "close_app", "uninstall_app")) Hands.appNamed(items, (a["app"] as? String).orEmpty())?.key else null

    fun perform(context: Context, m: MakerModel, choice: Choice, items: List<LibItem>, from: String? = null) {
        Setup.scope.launch(Dispatchers.Main) {
            val id = TaskHistory.begin(context, describe(choice.hand, choice.args), "action")
            if (id.isBlank()) { m.notice = "Could not save this action. Free storage and retry."; return@launch }
            try {
                val effective = if (choice.hand.level != Level.OPEN) ActionReview.ask(choice) else choice
                if (effective == null) { TaskHistory.finish(id, "cancelled", "Cancelled before execution"); return@launch }
                val h = effective.hand
                if (h.level == Level.SECURE && !Guard.verify(describe(h, effective.args))) {
                    m.notice = "Not done: it wasn't confirmed."
                    ActionLog.add(context, describe(h, effective.args), "you", "Not confirmed", false)
                    TaskHistory.finish(id, "cancelled", "Owner check was not confirmed"); return@launch
                }
                val result = run(context, m, h, effective.args, items, from, confirmed = h.level != Level.OPEN)
                TaskHistory.finish(id, if (result.ok) "action-result" else "needs-attention", result.say.ifBlank { "Handed off. Verify the result in the target app." })
            } catch (e: Exception) { TaskHistory.finish(id, "failed", e.message.orEmpty()); m.notice = "Action stopped: ${e.message}" }
        }
    }

    /** Ones that only show or tell something, or open what was asked for: they run at once, with no Undo. */
    private val instant = AgentScreen.names + setOf("open_app", "list_reminders", "battery", "navigate", "search_web", "settings_panel", "take_photo", "screenshot")

    /** An action waiting out its few seconds, with Undo and Do it now (Messages shows them). */
    class Waiting(val what: String, val icon: ImageVector, val until: Long, val ms: Long, val answer: kotlinx.coroutines.CompletableDeferred<Boolean>)
    val waiting = androidx.compose.runtime.mutableStateListOf<Waiting>()
    const val UNDO_MS = 5000L

    /** Waits [UNDO_MS] for an Undo; true to go ahead. */
    private suspend fun wait(what: String, icon: ImageVector): Boolean {
        val w = Waiting(what, icon, System.currentTimeMillis() + UNDO_MS, UNDO_MS, kotlinx.coroutines.CompletableDeferred())
        waiting.add(w)
        try { return kotlinx.coroutines.withTimeoutOrNull(UNDO_MS) { w.answer.await() } ?: true } finally { waiting.remove(w) }
    }

    /**
     * Runs an action with the gold thread telling what happens: to the app's icon first when it's about one on
     * screen; otherwise a light circles the box while it works, then goes to the answer and lights it up (or up
     * and out, when it happened in another app and has nothing to say).
     */
    suspend fun run(context: Context, m: MakerModel, h: Hand, args: Map<String, Any?>, items: List<LibItem>, from: String? = null, by: String = "you", confirmed: Boolean = false): Done {
        val what = describe(h, args)
        // A few seconds to change one's mind: the light circles the box meanwhile.
        if (!confirmed && h.name !in instant && !Gold.around(from) { wait(what, h.icon) }) {
            m.notice = "Undone: $what"
            ActionLog.add(context, what, by, "Undone", false)
            return Done("Not done: the user pressed Undo on \"$what\". Don't do it again unless they ask.", false)
        }
        // Opening an app draws its own way there (Reach); an agent's screen actions have their own light.
        val own = h.name == "open_app" || h.name in AgentScreen.names
        val to = if (own) null else target(h, args, items)
        if (to != null) Gold.fly(from, to)
        val call = suspend { runCatching { h.run(HandCall(context, m, args, items)) }.getOrElse { Done("That didn't work: ${it.message}", false) } }
        val d = if (own) call() else Gold.around(from) { call() }
        if (d.say.isNotBlank() && h.name !in AgentScreen.names) m.notice = d.say
        // Looking at the screen is reading, not doing: the log keeps what was done.
        if (h.name != "screen_look" && h.name != "screen_aim") ActionLog.add(context, what, by, d.say.ifBlank { if (d.ok) "Done" else "Didn't work" }.lineSequence().first(), d.ok)
        if (to == null && !own) { if (d.say.isNotBlank()) Gold.toAnswer(from) else Gold.fly(from, null) }
        return d
    }
}

/** Every action run from the create box or for an agent, kept for Settings → Action log (the last 300). */
object ActionLog {
    class Entry(val at: Long, val what: String, val by: String, val result: String, val ok: Boolean)
    var version by androidx.compose.runtime.mutableIntStateOf(0)
    private fun file(c: Context) = File(c.applicationContext.filesDir, "actions-log.json")

    @Synchronized fun all(c: Context): List<Entry> = runCatching {
        val a = JSONArray(file(c).readText())
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Entry(it.optLong("at"), it.optString("what"), it.optString("by"), it.optString("result"), it.optBoolean("ok")) }
    }.getOrDefault(emptyList())

    @Synchronized fun add(c: Context, what: String, by: String, result: String, ok: Boolean) {
        runCatching {
            val list = (listOf(Entry(System.currentTimeMillis(), what, by, result, ok)) + all(c)).take(300)
            val a = JSONArray(list.map { JSONObject().put("at", it.at).put("what", it.what).put("by", it.by).put("result", it.result).put("ok", it.ok) })
            File(file(c).path + ".tmp").apply { writeText(a.toString()); renameTo(file(c)) }
        }
        version++
    }

    @Synchronized fun clear(c: Context) { file(c).delete(); version++ }
}
