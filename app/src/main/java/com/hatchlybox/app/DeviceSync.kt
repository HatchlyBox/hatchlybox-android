package com.hatchlybox.app

import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// Syncing the library with the user's other devices (the engine's hatchlybox sync). A code pairs two devices
// once (shown here with its QR code, or typed, or scanned: a hatchlybox://sync link opens this); after that they
// sync by themselves (hatchlybox sync serve, kept running here while it's on). Run by the process, not a screen.

class PairedDevice(val id: String, val name: String, val last: Long)
/** A Hatchlybox device on this Wi-Fi with its sync page open: pairing with it needs no code. */
class NearbyDevice(val id: String, val name: String, val at: String)

object DeviceSync {
    /** The code session: "" (none), starting, waiting, finding, connected, packing, sending, receiving, merging, done, failed. */
    var state by mutableStateOf("")
    var code by mutableStateOf("")
    var qr by mutableStateOf(listOf<String>())
    var device by mutableStateOf("")
    var message by mutableStateOf("")
    var progress by mutableStateOf<Float?>(null)
    var result by mutableStateOf<JSONObject?>(null)
    /** A scanned hatchlybox://sync link waiting to be used. */
    var pendingLink by mutableStateOf<String?>(null)
    val devices = mutableStateListOf<PairedDevice>()
    /** Syncing by itself with the paired devices (hatchlybox sync serve) is running. */
    var serving by mutableStateOf(false)
    val running get() = state.isNotEmpty() && state != "done" && state != "failed"
    @Volatile private var process: java.lang.Process? = null
    @Volatile private var serveProcess: java.lang.Process? = null
    @Volatile private var serveWanted = false
    var serveMessage by mutableStateOf("Sync is off")
    /** Nearby pairing: the devices visible on this Wi-Fi, a request to answer, the six digits to confirm. */
    val nearby = mutableStateListOf<NearbyDevice>()
    var asked by mutableStateOf<String?>(null)
    var digits by mutableStateOf<String?>(null)
    var digitsWith by mutableStateOf("")
    @Volatile private var answerTo: java.lang.Process? = null
    @Volatile private var visibleProcess: java.lang.Process? = null
    @Volatile private var visibleWanted = false
    @Volatile private var visibleRunning = false
    @Volatile private var looking = false
    @Volatile private var pairingNow = false
    var lastSuccess by mutableStateOf(0L)
    fun stopServing() { serveWanted = false; serveProcess?.destroy() }

    private fun prefs(c: Context) = c.getSharedPreferences("sync", Context.MODE_PRIVATE)
    fun auto(c: Context) = prefs(c).getBoolean("auto", true)

    /** A hatchlybox://sync/<code> link from the camera or a browser. */
    fun link(intent: android.content.Intent?) {
        val d = intent?.data ?: return
        if (d.scheme == "hatchlybox" && d.host == "sync") pendingLink = d.toString()
    }

    private fun wifiLock(app: Context, tag: String) = runCatching {
        (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.createMulticastLock(tag)?.apply { setReferenceCounted(false); acquire() }
    }.getOrNull()

    /** send: show a code and wait for the other device; else find the device with that code (or link). */
    fun start(context: Context, m: MakerModel, send: Boolean, typed: String = "") =
        session(context, m, if (send) "exec hatchlybox sync send" else "exec hatchlybox sync receive ${quote(typed.trim())}", if (send) "starting" else "finding")

    /** Pairs with a nearby device: it's asked first, then both confirm the same six digits. */
    fun pairWith(context: Context, m: MakerModel, d: NearbyDevice) {
        device = d.name
        session(context, m, "exec hatchlybox sync ask ${quote(d.at)} --peer ${quote(d.name)}", "asking", answers = true)
    }

    private fun session(context: Context, m: MakerModel, command: String, first: String, answers: Boolean = false) {
        if (running) return
        val app = context.applicationContext
        state = first; code = ""; qr = emptyList(); if (!answers) device = ""; message = ""; progress = null; result = null
        // Android may drop the other device's "who has this code?" broadcast unless the app asks to hear it.
        val lock = wifiLock(app, "hatchlybox-sync")
        Setup.scope.launch(Dispatchers.IO) {
            try {
                val p = Linux(app).start(command)
                process = p
                if (!answers) p.outputStream.close()
                p.inputStream.bufferedReader().forEachLine { line ->
                    val j = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                    Setup.scope.launch(Dispatchers.Main) {
                        if (j.optString("state") == "confirm") { answerTo = p; digitsWith = j.optString("device"); digits = j.optString("sas") } else update(j)
                    }
                }
                p.waitFor()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { if (running) { state = "failed"; message = "The sync stopped: ${e.message}" } }
            }
            process = null
            runCatching { lock?.release() }
            withContext(Dispatchers.Main) {
                if (running) { state = "failed"; message = message.ifBlank { "The sync stopped before it finished." } }
                m.refresh()
                // Paired now: from here on they sync by themselves.
                loadDevices(app) { keepServing(app, m) }
            }
        }
    }

    private fun update(j: JSONObject) {
        val s = j.optString("state")
        when (s) {
            "waiting" -> { code = j.optString("code"); qr = j.optJSONArray("qr")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty() }
            "connected" -> device = j.optString("device")
            "sending", "receiving" -> j.optLong("total").takeIf { it > 0 }?.let { progress = j.optLong("bytes") / it.toFloat() }
            "done" -> result = j.optJSONObject("result")
            "asking" -> device = j.optString("device", device)
            "failed" -> message = j.optString("msg")
            "note" -> return
        }
        if (s != "sending" && s != "receiving") progress = null
        state = s
    }

    fun stop() { process?.destroy(); if (running) { state = ""; code = "" } }

    /** The person's answer to a pairing request or to the six digits. */
    fun answer(yes: Boolean) {
        val p = answerTo ?: return
        if (asked != null && yes) { pairingNow = true; device = asked ?: ""; state = "pairing"; message = ""; result = null }
        asked = null; digits = null
        Setup.scope.launch(Dispatchers.IO) { runCatching { p.outputStream.write((if (yes) "yes\n" else "no\n").toByteArray()); p.outputStream.flush() } }
    }

    /**
     * While the sync sheet is open: this phone is visible to nearby Hatchlybox devices (hatchlybox sync visible, again
     * after each pairing), and the devices visible on this Wi-Fi are listed (hatchlybox sync nearby, every few seconds).
     */
    fun openNearby(context: Context, m: MakerModel) {
        val app = context.applicationContext
        visibleWanted = true
        if (!visibleRunning) {
            visibleRunning = true
            Setup.scope.launch(Dispatchers.IO) {
                val lock = wifiLock(app, "hatchlybox-visible")
                try {
                    while (visibleWanted) {
                        val p = runCatching { Linux(app).start("exec hatchlybox sync visible") }.getOrNull() ?: break
                        visibleProcess = p
                        var stopHere = false
                        // Closing the sheet stops the process under this read: that ends the reading, quietly.
                        try {
                            p.inputStream.bufferedReader().forEachLine { line ->
                                val j = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                                Setup.scope.launch(Dispatchers.Main) {
                                    when (j.optString("state")) {
                                        "asked" -> { answerTo = p; asked = j.optString("device") }
                                        "confirm" -> { answerTo = p; digitsWith = j.optString("device"); digits = j.optString("sas") }
                                        "visible", "declined", "error" -> {}
                                        "failed" -> { if (j.optString("msg").contains("another window")) stopHere = true; if (pairingNow) update(j) }
                                        else -> if (pairingNow) update(j)
                                    }
                                }
                            }
                            p.waitFor()
                        } catch (e: Exception) { p.destroy() }
                        visibleProcess = null
                        withContext(Dispatchers.Main) {
                            if (pairingNow) { pairingNow = false; if (running) { state = "failed"; message = message.ifBlank { "The pairing stopped before it finished." } } }
                            m.refresh(); loadDevices(app) { keepServing(app, m) }
                        }
                        if (stopHere) break
                        if (visibleWanted) delay(1500)
                    }
                } catch (e: Exception) { /* the sheet closed, or Linux stopped: visible again next time it opens */
                } finally { runCatching { lock?.release() }; visibleRunning = false }
            }
        }
        if (!looking) {
            looking = true
            Setup.scope.launch(Dispatchers.IO) {
                try {
                    while (visibleWanted) {
                        val (rc, out) = runCatching { Linux(app).run("hatchlybox sync nearby") }.getOrDefault(1 to "")
                        val list = if (rc == 0) runCatching { JSONArray(out.lines().last { it.startsWith("[") }) }.getOrNull() else null
                        if (list != null) withContext(Dispatchers.Main) {
                            nearby.clear()
                            for (i in 0 until list.length()) list.optJSONObject(i)?.let { nearby += NearbyDevice(it.optString("id"), it.optString("name"), it.optString("at")) }
                        }
                        delay(4000)
                    }
                } finally { looking = false }
            }
        }
    }

    fun closeNearby() { visibleWanted = false; visibleProcess?.destroy(); asked = null; digits = null }

    /** The paired devices, from the engine. */
    fun loadDevices(context: Context, then: () -> Unit = {}) {
        val app = context.applicationContext
        Setup.scope.launch(Dispatchers.IO) {
            val (rc, out) = runCatching { Linux(app).run("hatchlybox sync devices") }.getOrDefault(1 to "")
            val list = if (rc == 0) runCatching { JSONArray(out.lines().last { it.startsWith("[") }) }.getOrNull() else null
            if (list != null) withContext(Dispatchers.Main) {
                devices.clear()
                for (i in 0 until list.length()) list.optJSONObject(i)?.let { devices += PairedDevice(it.optString("id"), it.optString("name"), it.optLong("last")) }
                then()
            }
        }
    }

    /**
     * Syncing by itself: while it's on and a device is paired, hatchlybox sync serve runs (started again if it
     * stops). What it brings in is shown as a notice, and the library reloads.
     */
    fun keepServing(context: Context, m: MakerModel) {
        val app = context.applicationContext
        lastSuccess = prefs(app).getLong("lastSuccess", 0L)
        serveWanted = auto(app) && devices.isNotEmpty() && m.toolsInstalled
        if (!serveWanted) { serveProcess?.destroy(); return }
        if (serving) return
        serving = true; serveMessage = "Starting background sync"
        runCatching { app.startForegroundService(android.content.Intent(app, SyncTaskService::class.java)) }.onFailure { serving = false; serveWanted = false; serveMessage = "Cannot start background sync: ${it.message}"; return }
        Setup.scope.launch(Dispatchers.IO) {
            val lock = wifiLock(app, "hatchlybox-sync-serve")
            try { while (serveWanted) {
                try {
                    val p = Linux(app).start("exec hatchlybox sync serve")
                    serveProcess = p
                    if (!serveWanted) p.destroy()
                    withContext(Dispatchers.Main) { serveMessage = "Waiting for paired devices" }
                    p.outputStream.close()
                    p.inputStream.bufferedReader().forEachLine { line ->
                        val j = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                        if (j.optString("state") == "synced") Setup.scope.launch(Dispatchers.Main) {
                            lastSuccess = System.currentTimeMillis(); prefs(app).edit().putLong("lastSuccess", lastSuccess).apply(); serveMessage = "Synced successfully"
                            val r = j.optJSONObject("result")
                            fun n(k: String) = r?.optJSONArray(k)?.length() ?: 0
                            val words = listOfNotNull(n("added").takeIf { it > 0 }?.let { "$it new" }, n("updated").takeIf { it > 0 }?.let { "$it updated" },
                                n("conflicts").takeIf { it > 0 }?.let { "$it changed on both: open to choose" })
                            if (words.isNotEmpty()) m.notice = "From ${j.optString("device")}: ${words.joinToString(", ")}."
                            m.refresh(); loadDevices(app)
                        }
                    }
                    p.waitFor()
                } catch (e: Exception) { withContext(Dispatchers.Main) { serveMessage = "Sync stopped: ${e.message}. Retrying in 30 seconds." } }
                serveProcess?.destroy(); serveProcess = null
                if (serveWanted) delay(30_000)
            }
            } finally {
                serveProcess?.destroy(); serveProcess = null
                runCatching { lock?.release() }
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { serving = false; if (!serveWanted) serveMessage = "Sync is stopped" }
            }
        }
    }

    fun setAuto(context: Context, m: MakerModel, on: Boolean) {
        prefs(context).edit().putBoolean("auto", on).apply()
        keepServing(context, m)
    }

    /** Something changed here: the paired devices hear about it now, not in ten minutes. */
    fun now(context: Context) {
        val app = context.applicationContext
        Setup.scope.launch(Dispatchers.IO) { runCatching { Linux(app).run("hatchlybox sync now") } }
    }

    fun forget(context: Context, m: MakerModel, d: PairedDevice) {
        val app = context.applicationContext
        Setup.scope.launch(Dispatchers.IO) {
            runCatching { Linux(app).run("hatchlybox sync forget ${quote(d.id)}") }
            withContext(Dispatchers.Main) { devices.removeAll { it.id == d.id }; keepServing(app, m) }
        }
    }
}

/** The sync sheet: show a code (with its QR code), or type the other device's; the paired devices; what came. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncSheet(m: MakerModel, close: () -> Unit) {
    val context = LocalContext.current
    var typed by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { DeviceSync.loadDevices(context) }
    // Visible to nearby devices, and looking for them, while this sheet is open.
    DisposableEffect(m.toolsInstalled) {
        if (m.toolsInstalled) DeviceSync.openNearby(context, m)
        onDispose { DeviceSync.closeNearby() }
    }
    DeviceSync.asked?.let { who ->
        AlertDialog(onDismissRequest = { DeviceSync.answer(false) },
            title = { Text("$who wants to pair") },
            text = { Text("Pair this phone with $who? Your library and its library will be shared between the two.") },
            confirmButton = { Button(onClick = { DeviceSync.answer(true) }) { Text("Pair") } },
            dismissButton = { TextButton(onClick = { DeviceSync.answer(false) }) { Text("Not now") } })
    }
    DeviceSync.digits?.let { d ->
        AlertDialog(onDismissRequest = { DeviceSync.answer(false) },
            title = { Text("Do the numbers match?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${DeviceSync.digitsWith.ifBlank { "The other device" }} shows six digits too. Pair only if they're exactly these:")
                    Text("${d.take(3)} ${d.drop(3)}", style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { Button(onClick = { DeviceSync.answer(true) }) { Text("They match") } },
            dismissButton = { TextButton(onClick = { DeviceSync.answer(false) }) { Text("They don't") } })
    }
    // A scanned link: straight to it.
    LaunchedEffect(DeviceSync.pendingLink, m.toolsInstalled) {
        val l = DeviceSync.pendingLink ?: return@LaunchedEffect
        if (m.toolsInstalled && !DeviceSync.running) { DeviceSync.pendingLink = null; DeviceSync.start(context, m, send = false, typed = l) }
    }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Sync, null, tint = MaterialTheme.colorScheme.primary)
                Text("Sync with your devices", style = MaterialTheme.typography.titleMedium)
            }
            when (DeviceSync.state) {
                "", "done", "failed" -> {
                    when (DeviceSync.state) {
                        "done" -> SyncResult(DeviceSync.result)
                        "failed" -> Text(DeviceSync.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        else -> Muted("Everything you make goes both ways: apps, games and their versions for other systems. Pair a device once with a code; after that they sync by themselves. Logins and publishing keys stay where they are.")
                    }
                    val can = m.toolsInstalled
                    if (!can) Muted("The build tools are still installing; syncing works once they're in.")
                    if (can) NearbyDevices(m)
                    Button(onClick = { DeviceSync.start(context, m, send = true) }, enabled = can, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Show a code and a QR code") }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val go = { if (typed.isNotBlank()) DeviceSync.start(context, m, send = false, typed = typed) }
                        OutlinedTextField(typed, { typed = it }, singleLine = true, placeholder = { Text("Or type its code: 7 violet orbit") }, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { go() }))
                        FilledTonalButton(onClick = go, enabled = can && typed.isNotBlank()) { Text("Connect") }
                    }
                    PairedDevices(m)
                }
                else -> {
                    if (DeviceSync.state == "waiting" || DeviceSync.state == "starting") {
                        Text(DeviceSync.code.replace('-', ' ').ifBlank { "…" }, style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, letterSpacing = 1.sp),
                            color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        if (DeviceSync.qr.isNotEmpty()) QrCode(DeviceSync.qr, Modifier.align(Alignment.CenterHorizontally).size(200.dp))
                        Muted("On the other device, type this code (on a phone: Library › Sync; on a computer: Hatchlybox › Devices), or scan the square with its camera. It works once, for 10 minutes.")
                    }
                    val words = when (DeviceSync.state) {
                        "starting" -> "Getting a code…"; "waiting" -> "Waiting for the other device…"; "finding" -> "Looking for the device with that code…"
                        "connected" -> "Connected to ${DeviceSync.device}"; "packing" -> "Getting ready what ${DeviceSync.device.ifBlank { "it" }} doesn't have…"
                        "sending" -> "Sending…"; "receiving" -> "Receiving…"; "merging" -> "Adding to your library…"
                        "asking" -> "Waiting for ${DeviceSync.device.ifBlank { "the other device" }} to accept…"; "pairing" -> "Pairing with ${DeviceSync.device.ifBlank { "the other device" }}…"; else -> "Working…"
                    }
                    Text(words, style = MaterialTheme.typography.bodyMedium)
                    DeviceSync.progress?.let { LinearProgressIndicator({ it }, Modifier.fillMaxWidth()) } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = DeviceSync::stop, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
                }
            }
        }
    }
}

/** The Hatchlybox devices on this Wi-Fi with their sync page open, each with Pair (no code: both confirm six digits). */
@Composable
private fun NearbyDevices(m: MakerModel) {
    val context = LocalContext.current
    Text("Nearby", style = MaterialTheme.typography.titleSmall)
    val paired = DeviceSync.devices.map { it.id }.toSet()
    val list = DeviceSync.nearby.filter { it.id !in paired }
    if (list.isEmpty()) Muted("Looking for nearby devices. Open sync on the other one too (on a computer: Hatchlybox › Devices), on the same Wi-Fi: it shows up here, and this phone shows up there.")
    list.forEach { d ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Devices, null, tint = MaterialTheme.colorScheme.primary)
            Text(d.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Button(onClick = { DeviceSync.pairWith(context, m, d) }) { Text("Pair") }
        }
    }
    HorizontalDivider()
}

/** A QR code from rows of '1' and '0': dark on white with its quiet zone, whatever the theme (cameras need it). */
@Composable
private fun QrCode(rows: List<String>, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val n = rows.size
            val cell = size.minDimension / n
            for (r in 0 until n) for (c in 0 until n) if (rows[r].getOrNull(c) == '1')
                drawRect(Color.Black, Offset(c * cell, r * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

/** The devices this one syncs with by itself, the switch for it, and the relay for different networks. */
@Composable
private fun PairedDevices(m: MakerModel) {
    val context = LocalContext.current
    if (DeviceSync.devices.isEmpty()) return
    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Sync by itself", style = MaterialTheme.typography.bodyLarge)
            Muted("With your paired devices, whenever both are on the same Wi-Fi (or reach the relay).")
        }
        var on by remember { mutableStateOf(DeviceSync.auto(context)) }
        Switch(checked = on, onCheckedChange = { on = it; DeviceSync.setAuto(context, m, it) })
    }
    val now = System.currentTimeMillis() / 1000
    DeviceSync.devices.forEach { d ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Devices, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.bodyMedium)
                Muted(if (d.last <= 0) "Not synced yet" else "Synced ${ago(now - d.last)}")
            }
            TextButton(onClick = { DeviceSync.forget(context, m, d) }) { Text("Forget") }
        }
    }
    if (DeviceSync.serving) TextButton(onClick = { DeviceSync.now(context); m.notice = "Syncing with your devices that are on." }) { Text("Sync now") }
    Relay(m)
}

/** The relay (optional): a server both devices reach, for syncing from different networks. */
@Composable
private fun Relay(m: MakerModel) {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    var relay by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(open) {
        if (open) relay = withContext(Dispatchers.IO) {
            runCatching { JSONObject(Linux(context.applicationContext).run("hatchlybox settings").second.lines().last { it.startsWith("{") }).optString("relay") }.getOrDefault("")
        }
    }
    TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) { Text(if (open) "Hide the relay" else "Different networks: the relay") }
    if (open) {
        Muted("Devices on different networks meet through a relay: a small server you run (java Relay.java <port>, from Hatchlybox Engine). It only passes along encrypted bytes. Leave empty for the same Wi-Fi only.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(relay, { relay = it.trim() }, singleLine = true, placeholder = { Text("relay.example.com:47200") }, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = {
                val v = relay
                Setup.scope.launch(Dispatchers.IO) {
                    val (rc, out) = runCatching { Linux(context.applicationContext).run("hatchlybox set relay ${quote(v)}") }.getOrDefault(1 to "")
                    withContext(Dispatchers.Main) {
                        m.notice = if (rc == 0) (if (v.isEmpty()) "No relay: the same Wi-Fi only." else "Relay set: syncing works across networks.") else out.ifBlank { "That isn't host:port." }
                        if (rc == 0 && DeviceSync.serving) { DeviceSync.setAuto(context, m, false); DeviceSync.setAuto(context, m, true) }
                    }
                }
            }) { Text("Save") }
        }
    }
}

private fun ago(s: Long) = when {
    s < 90 -> "just now"; s < 3600 -> "${s / 60} min ago"; s < 86400 -> "${s / 3600} h ago"; else -> "${s / 86400} days ago"
}

@Composable
private fun SyncResult(r: JSONObject?) {
    fun list(k: String) = r?.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
    val added = list("added"); val updated = list("updated"); val conflicts = list("conflicts"); val skipped = list("skipped")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (added.isEmpty() && updated.isEmpty() && conflicts.isEmpty()) "In sync with ${DeviceSync.device.ifBlank { "the other device" }}: nothing new here."
            else "Synced with ${DeviceSync.device.ifBlank { "the other device" }}. They're paired now: they sync by themselves from here on.", style = MaterialTheme.typography.bodyLarge)
        if (added.isNotEmpty()) Muted("New here: ${added.joinToString(", ")}")
        if (updated.isNotEmpty()) Muted("Updated: ${updated.joinToString(", ")}. Rebuild them to try the new versions.")
        if (conflicts.isNotEmpty()) Text("Changed on both devices: ${conflicts.joinToString(", ")}. Open each to choose which to keep.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        if (skipped.isNotEmpty()) Muted("Not this time: ${skipped.joinToString("; ")}")
    }
}

/** On an app's page after both devices changed it: keep one; the other is kept aside in its history. */
@Composable
internal fun SyncConflict(m: MakerModel, slug: String) {
    val c = m.creations.firstOrNull { it.text("slug") == slug }?.optJSONObject("conflict") ?: return
    val other = c.text("device", "the other device")
    val idle = !m.busy && !MakerRuntime.running && !m.installing && !m.godotInstalling
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Changed on two devices", style = MaterialTheme.typography.titleMedium)
            Text("This phone and $other both changed it since they last synced. Keep one: the other is saved and can come back.", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { m.action("Keep this phone's version", listOf("sync", "resolve", slug, "mine")) }, enabled = idle) { Text("Keep this phone's") }
                Button(onClick = { m.action("Take $other's version", listOf("sync", "resolve", slug, "theirs")) }, enabled = idle) { Text("Take $other's") }
            }
        }
    }
}
