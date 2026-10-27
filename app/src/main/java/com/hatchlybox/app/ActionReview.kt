package com.hatchlybox.nightly

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object ActionReview {
    class Pending(val choice: Choice, val answer: CompletableDeferred<Choice?>)
    var pending by mutableStateOf<Pending?>(null)
    private val serial = Mutex()
    suspend fun ask(choice: Choice): Choice? = serial.withLock {
        val p = Pending(choice, CompletableDeferred())
        pending = p
        try { kotlinx.coroutines.withTimeoutOrNull(120000) { p.answer.await() } } finally { if (pending === p) pending = null }
    }
}

@Composable
fun ActionReviewDialog() {
    val p = ActionReview.pending ?: return
    val h = p.choice.hand
    var args by remember(p) { mutableStateOf(p.choice.args.mapValues { it.value?.toString().orEmpty() }) }
    var error by remember(p) { mutableStateOf("") }
    AlertDialog(onDismissRequest = { p.answer.complete(null) }, title = { Text("Review ${h.title.lowercase()}") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Check the target and details. Sending, calling and deleting may not be reversible.")
            h.params.forEach { param ->
                OutlinedTextField(args[param.name].orEmpty(), { args = args + (param.name to it) }, label = { Text(param.name.replace('_', ' ')) }, supportingText = { Text(param.description) }, modifier = Modifier.fillMaxWidth())
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, dismissButton = { TextButton(onClick = { p.answer.complete(null) }) { Text("Cancel") } }, confirmButton = {
        TextButton(onClick = {
            runCatching {
                val converted = h.params.associate { param ->
                    val v = args[param.name].orEmpty()
                    require(param.enum.isEmpty() || v in param.enum) { "Choose one of ${param.enum.joinToString()}" }
                    param.name to when (param.type) {
                        "integer" -> v.toLong(); "number" -> v.toDouble()
                        "boolean" -> when (v.trim().lowercase()) { "true", "yes", "1", "on" -> true; "false", "no", "0", "off" -> false; else -> throw IllegalArgumentException("Enter true or false") }
                        else -> v
                    }
                }
                require(!converted.containsKey("contact") || converted["contact"].toString().isNotBlank()) { "Choose a recipient" }
                p.answer.complete(Choice(h, converted))
            }.onFailure { error = "Check the values: ${it.message}" }
        }) { Text("Confirm") }
    })
}
