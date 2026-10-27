package com.hatchlybox.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

// The local model that turns what's typed into one of the hands' actions, on the phone, offline and in about a
// second: FunctionGemma (Google's 270M model for function calling) run by llama.cpp, bundled in the APK as
// libllamaserver.so and started only when needed (stopped again after 10 idle minutes). Only the actions whose
// words appear in the text are offered to it, with "none", and a grammar keeps its answer to exactly one call
// in its own format; it fills in the details. What it chooses is always shown to confirm, never run blindly.

class Choice(val hand: Hand, val args: Map<String, Any?>)

object Assist {
    private const val MODEL_URL = "https://huggingface.co/unsloth/functiongemma-270m-it-GGUF/resolve/main/functiongemma-270m-it-Q8_0.gguf"
    private const val MODEL_SHA = "8a17a4eda2b28eaf685a487129fd5295eeff3f722b9c6451dd3d98c18c23a98d"
    const val MODEL_MB = 292
    private const val PORT = 8772

    /** none, downloading, ready (or failed: the message). */
    var state by mutableStateOf("none")
    var progress by mutableStateOf(0f)
    var message by mutableStateOf("")
    private var server: Process? = null
    private var lastUse = 0L
    private var idle: Job? = null
    private val lock = Mutex()
    private val inference = Mutex()
    private var cachedText = ""
    private var cachedChoice: Choice? = null
    private var cachedAt = 0L
    @Volatile private var activeText = ""
    @Volatile private var activeConnection: HttpURLConnection? = null
    fun invalidate(text: String) { if (activeText != text) activeConnection?.disconnect() }
    private var downloadJob: Job? = null
    @Volatile private var downloading: HttpURLConnection? = null
    fun cancelDownload() { downloadJob?.cancel(); downloading?.disconnect(); state = "none"; message = "Download paused. Tap to resume." }

    private fun model(c: Context) = File(c.filesDir, "assist/functiongemma-270m-it-Q8_0.gguf")
    fun check(c: Context) { if (state != "downloading") state = if (model(c).isFile) "ready" else "none" }

    fun download(context: Context) {
        if (state == "downloading" || state == "ready" || downloadJob?.isCompleted == false) return
        val c = context.applicationContext
        state = "downloading"; progress = 0f; message = ""
        downloadJob = Setup.scope.launch(Dispatchers.IO) {
            val out = model(c); out.parentFile?.mkdirs()
            val part = File(out.path + ".part")
            val ok = runCatching {
                SafeFiles.space(c, MODEL_MB * 1_000_000L)
                val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
                downloading = conn
                val resume = part.length()
                if (resume > 0) conn.setRequestProperty("Range", "bytes=$resume-")
                conn.connectTimeout = 20000; conn.readTimeout = 30000; conn.instanceFollowRedirects = true
                val append = resume > 0 && conn.responseCode == 206
                val total = (conn.contentLengthLong.takeIf { it > 0 } ?: (MODEL_MB * 1_000_000L)) + if (append) resume else 0L
                val md = MessageDigest.getInstance("SHA-256")
                if (append) part.inputStream().use { input -> val b = ByteArray(65536); while (true) { val n = input.read(b); if (n < 0) break; md.update(b, 0, n) } }
                conn.inputStream.use { input -> java.io.FileOutputStream(part, append).use { o ->
                    val buf = ByteArray(1 shl 16); var n: Int; var done = if (append) resume else 0L
                    while (input.read(buf).also { n = it } > 0) { coroutineContext.ensureActive(); o.write(buf, 0, n); md.update(buf, 0, n); done += n
                        if (done % (1 shl 20) < n) withContext(Dispatchers.Main) { progress = done / total.toFloat() } }
                } }
                coroutineContext.ensureActive()
                val valid = md.digest().joinToString("") { "%02x".format(it) } == MODEL_SHA
                if (!valid) part.delete()
                valid && part.renameTo(out)
            }.getOrDefault(false)
            downloading?.disconnect(); downloading = null
            coroutineContext.ensureActive()
            withContext(Dispatchers.Main) { state = if (ok) "ready" else "none"; message = if (ok) "" else "The model didn't download completely. Try again on Wi-Fi." }
        }
    }

    fun remove(c: Context) { cancelDownload(); stop(); model(c).delete(); File(model(c).path + ".part").delete(); state = "none" }

    private fun stop() { server?.destroy(); server = null }

    private fun up(): Boolean = runCatching {
        (URL("http://127.0.0.1:$PORT/health").openConnection() as HttpURLConnection).run { connectTimeout = 500; readTimeout = 800; try { responseCode == 200 } finally { disconnect() } }
    }.getOrDefault(false)

    /** Starts the model when it isn't running (a couple of seconds), so the first answer is quick. */
    suspend fun warm(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (state != "ready") return@withContext false
        lock.withLock {
            lastUse = System.currentTimeMillis()
            if (server?.isAlive == true && up()) return@withLock true
            stop()
            val c = context.applicationContext
            val lib = c.applicationInfo.nativeLibraryDir
            val p = runCatching {
                ProcessBuilder(File(lib, "libllamaserver.so").path, "-m", model(c).path, "--host", "127.0.0.1", "--port", PORT.toString(),
                    "--jinja", "-c", "2048", "-t", Runtime.getRuntime().availableProcessors().coerceIn(2, 4).toString(), "-np", "1")
                    .redirectErrorStream(true).redirectOutput(File(c.cacheDir, "assist.log")).apply { environment()["LD_LIBRARY_PATH"] = lib; directory(c.cacheDir) }.start()
            }.getOrNull() ?: return@withLock false
            server = p
            try { for (i in 0 until 120) { if (up() || !p.isAlive) break; delay(250) } }
            catch (e: kotlinx.coroutines.CancellationException) { stop(); throw e }
            if (!p.isAlive || !up()) {
                val log = runCatching { File(c.cacheDir, "assist.log").readLines().filter { it.isNotBlank() }.takeLast(4).joinToString("\n") }.getOrDefault("")
                val why = if (p.isAlive) "it didn't answer in 30 seconds" else "it stopped (code ${p.exitValue()})"
                withContext(Dispatchers.Main) { message = "The local model didn't start: $why." + if (log.isNotEmpty()) "\n$log" else "" }
                stop(); return@withLock false
            }
            withContext(Dispatchers.Main) { message = "" }
            // Ten minutes unused: let the memory go.
            idle?.cancel()
            idle = Setup.scope.launch(Dispatchers.IO) {
                while (server?.isAlive == true) { delay(60_000); if (System.currentTimeMillis() - lastUse > 600_000) { lock.withLock { stop() } } }
            }
            true
        }
    }

    /** The action the text asks for, with its details, or null (nothing fits, or no model). */
    suspend fun choose(context: Context, text: String): Choice? = withContext(Dispatchers.IO) {
        invalidate(text)
        inference.lock()
        try {
        coroutineContext.ensureActive()
        activeText = text
        val hands = Hands.candidates(text).ifEmpty { return@withContext null }
        val cacheKey = text + hands.joinToString { it.name + it.description }
        if (cachedText == cacheKey && System.currentTimeMillis() - cachedAt < 5000) return@withContext cachedChoice
        if (!warm(context)) return@withContext null
        lastUse = System.currentTimeMillis()
        val tools = JSONArray()
        (hands.map { tool(it.name, it.description, it.params) } + tool("none", "Use when the request is a question, chit-chat, or none of the other functions does it", emptyList())).forEach { tools.put(it) }
        val messages = JSONArray().put(JSONObject().put("role", "developer").put("content", "You are a model that can do function calling with the following functions"))
            .put(JSONObject().put("role", "user").put("content", text.trim()))
        val prompt = post("/apply-template", JSONObject().put("messages", messages).put("tools", tools))?.optString("prompt") ?: return@withContext null
        val out = post("/completion", JSONObject().put("prompt", prompt).put("grammar", grammar(hands)).put("n_predict", 160).put("temperature", 0).put("cache_prompt", true))
            ?.optString("content") ?: return@withContext null
        val call = parse(out) ?: return@withContext null
        val hand = hands.firstOrNull { it.name == call.first } ?: return@withContext null
        Choice(hand, call.second).also { cachedText = cacheKey; cachedChoice = it; cachedAt = System.currentTimeMillis() }
        } finally { activeConnection?.disconnect(); activeConnection = null; activeText = ""; inference.unlock() }
    }

    private fun tool(name: String, description: String, params: List<Param>): JSONObject {
        val props = JSONObject()
        params.forEach { p -> props.put(p.name, JSONObject().put("type", p.type).put("description", p.description).apply { if (p.enum.isNotEmpty()) put("enum", JSONArray(p.enum)) }) }
        return JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", description)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(params.map { it.name }))))
    }

    /** Exactly one call, in FunctionGemma's own format, to one of these (or none), every detail in order. */
    private fun grammar(hands: List<Hand>): String {
        val rules = mutableListOf<String>()
        val alts = (hands + null).mapIndexed { i, h ->
            val parts = h?.params?.sortedBy { it.name }?.mapIndexed { j, p ->
                val v = when {
                    p.enum.isNotEmpty() -> "(" + p.enum.joinToString(" | ") { "\"<escape>$it<escape>\"" } + ")"
                    p.type == "integer" -> "int"; p.type == "number" -> "num"; p.type == "boolean" -> "bool"
                    else -> "\"<escape>\" str \"<escape>\""
                }
                (if (j > 0) "\",\" " else "") + "\"${p.name}:\" $v"
            }.orEmpty()
            rules += "f$i ::= \"${h?.name ?: "none"}{\" ${parts.joinToString(" ")} \"}\""
            "f$i"
        }
        return (listOf("root ::= \"<start_function_call>call:\" (${alts.joinToString(" | ")}) \"<end_function_call>\"") + rules +
            listOf("str ::= [^<\\n]{0,300}", "int ::= \"-\"? [0-9]{1,9}", "num ::= \"-\"? [0-9]{1,9} (\".\" [0-9]{1,6})?", "bool ::= \"true\" | \"false\"")).joinToString("\n")
    }

    /** "<start_function_call>call:send_text{contact:<escape>Ana<escape>,message:<escape>hi<escape>}…" → (send_text, {contact, message}). */
    internal fun parse(out: String): Pair<String, Map<String, Any?>>? {
        val body = out.substringAfter("call:", "").substringBefore("<end_function_call>").ifEmpty { return null }
        val name = body.substringBefore('{')
        val inner = body.substringAfter('{').removeSuffix("}")
        val args = mutableMapOf<String, Any?>()
        var i = 0
        while (i < inner.length) {
            val colon = inner.indexOf(':', i).takeIf { it > 0 } ?: break
            val key = inner.substring(i, colon).trim(',', ' ')
            var j = colon + 1
            if (inner.startsWith("<escape>", j)) {
                val end = inner.indexOf("<escape>", j + 8).takeIf { it >= 0 } ?: inner.length
                args[key] = inner.substring(j + 8, end); j = end + 8
            } else {
                val end = inner.indexOf(',', j).let { if (it < 0) inner.length else it }
                val raw = inner.substring(j, end)
                args[key] = raw.toBooleanStrictOrNull() ?: raw.toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it } ?: raw.toDoubleOrNull() ?: raw
                j = end
            }
            i = j + 1
        }
        return name to args
    }

    private fun post(path: String, body: JSONObject): JSONObject? = runCatching {
        val c = URL("http://127.0.0.1:$PORT$path").openConnection() as HttpURLConnection
        activeConnection = c
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 2000; c.readTimeout = 20000
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        try { JSONObject(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
    }.getOrNull()
}
