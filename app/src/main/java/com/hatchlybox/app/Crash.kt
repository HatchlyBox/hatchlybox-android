package com.hatchlybox.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

// If Hatchlybox closes unexpectedly, the error is saved and shown on the next start, ready to copy.

object Crash {
    private fun file(context: Context) = File(context.filesDir, "last-crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime }.getOrDefault(0L)
                file(app).writeText("Hatchlybox (build ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(version))}), Android ${android.os.Build.VERSION.RELEASE} | ${oneLine(e)}\n\n" + summary(e) + "\n\nFull trace:\n" + e.stackTraceToString().take(60000))
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** The short form: each cause's type and message, with the app's own lines (where it went wrong) under it. */
    private fun summary(e: Throwable): String = generateSequence(e) { it.cause.takeIf { c -> c !== it } }.take(8).joinToString("\n") { t ->
        val own = t.stackTrace.filter { it.className.startsWith("com.hatchlybox") }.take(8)
        val lines = own.ifEmpty { t.stackTrace.take(4).toList() }
        "${t.javaClass.name}: ${t.message.orEmpty().take(600)}\n" + lines.joinToString("\n") { "  at ${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
    }

    /** Everything that matters on one line (pastes sometimes keep only the first): the root cause and where. */
    private fun oneLine(e: Throwable): String {
        val chain = generateSequence(e) { it.cause.takeIf { c -> c !== it } }.take(8).toList()
        val root = chain.last()
        val where = chain.flatMap { t -> t.stackTrace.filter { it.className.startsWith("com.hatchlybox") } }.distinct().take(4)
            .joinToString(" < ") { "${it.fileName}:${it.lineNumber}" }
        return "${root.javaClass.simpleName}: ${root.message.orEmpty().replace('\n', ' ').take(300)} @ ${where.ifBlank { root.stackTrace.take(2).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } }}"
    }

    fun last(context: Context): String? = file(context).takeIf { it.isFile }?.readText()
    fun clear(context: Context) { file(context).delete() }
}

@Composable
fun CrashScreen(report: String, onContinue: () -> Unit) {
    val context = LocalContext.current
    // The short form (what went wrong and where) is what Copy takes; Share sends everything.
    val short = report.substringBefore("\n\nFull trace:").take(3000)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Hatchlybox closed unexpectedly", style = MaterialTheme.typography.headlineSmall)
            Text("Tap Copy error and paste it to whoever is fixing the app: the first lines say what went wrong. Your saved data has not been checked. Open Storage and Tasks after continuing to review it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
                Text(short + "\n\n" + report.substringAfter("\n\nFull trace:", ""), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Hatchlybox error", short))
                }) { Text("Copy error") }
                OutlinedButton(onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), "Send the error"))
                }) { Text("Share") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onContinue) { Text("Continue") }
            }
        }
    }
}
