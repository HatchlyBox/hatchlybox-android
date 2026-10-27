package com.hatchlybox.nightly

import org.json.JSONObject

// How each CLI is driven: one headless run per prompt, plus its own sign-in commands.
enum class Agent(val label: String, val bin: String, val account: String) {
    CLAUDE("Claude", "claude", "Claude account"),
    CODEX("Codex", "codex", "ChatGPT account"),
    OPENCODE("OpenCode", "opencode", "provider account");

    fun runCommand(resume: Boolean): String = when (this) {
        CLAUDE -> "claude -p --verbose --output-format stream-json --permission-mode bypassPermissions" +
            (if (resume) " --continue" else "") + " \"\$PROMPT\""
        CODEX -> "codex exec" + (if (resume) " resume --last" else "") +
            " --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox \"\$PROMPT\""
        // --auto approves its permission prompts (headless, nobody can answer them); --standalone (OpenCode 2) runs
        // its own server instead of the shared background one, which another process may have taken down with it.
        OPENCODE -> "opencode run $OPENCODE_FLAGS" + (if (resume) " -c" else "") + " \"\$PROMPT\""
    }

    /** The interactive program again, carrying on its last session (a terminal widget restarted after an update). */
    val continueFlag get() = when (this) {
        CLAUDE -> "--continue"
        CODEX -> "resume --last"
        OPENCODE -> "-c"
    }

    val loginCommand get() = when (this) {
        CLAUDE -> "claude auth login"
        CODEX -> "codex login"
        OPENCODE -> "opencode auth login"
    }

    // Logging out of OpenCode asks which account, so it runs in the console like a login.
    val logoutCommand get() = when (this) {
        CLAUDE -> "claude auth logout"
        CODEX -> "codex logout"
        OPENCODE -> "opencode auth logout"
    }
    val interactiveLogout get() = this == OPENCODE

    val statusCommand get() = when (this) {
        CLAUDE -> "claude auth status --text"
        CODEX -> "codex login status"
        OPENCODE -> "opencode auth list"
    }

    // OpenCode needs no account: without a provider it uses OpenCode's free models, so it's always ready.
    fun isConnected(code: Int, output: String): Boolean = when (this) {
        OPENCODE -> code == 0
        else -> code == 0 && !output.contains("not logged in", ignoreCase = true)
    }

    /** OpenCode with no provider signed in: running on its free models. */
    fun onFreeModels(output: String) = this == OPENCODE && Regex("""no authenticated|\b0 credentials|no credentials""", RegexOption.IGNORE_CASE).containsMatchIn(output)

    /** One quick answer, no tools: used to decide what a request is. */
    val askCommand get() = when (this) {
        CLAUDE -> "claude -p --model haiku \"\$PROMPT\""
        CODEX -> "codex exec --skip-git-repo-check --sandbox read-only \"\$PROMPT\" 2>/dev/null"
        OPENCODE -> "opencode run $OPENCODE_FLAGS \"\$PROMPT\""
    }

    // Turns raw output lines into what the window shows. Claude's stream-json becomes text and tool lines.
    fun render(line: String): String? {
        if (this != CLAUDE) return Ansi.strip(line)
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return Ansi.strip(line)
        return when (json.optString("type")) {
            "assistant" -> {
                val content = json.optJSONObject("message")?.optJSONArray("content") ?: return null
                buildList {
                    for (i in 0 until content.length()) {
                        val block = content.optJSONObject(i) ?: continue
                        when (block.optString("type")) {
                            "text" -> add(block.optString("text"))
                            "tool_use" -> add("› ${block.optString("name")} ${toolSummary(block.optJSONObject("input"))}".trimEnd())
                        }
                    }
                }.joinToString("\n").ifEmpty { null }
            }
            "result" -> if (json.optBoolean("is_error")) "! ${json.optString("result")}" else null
            else -> null
        }
    }

    private fun toolSummary(input: JSONObject?): String {
        input ?: return ""
        val v = listOf("command", "file_path", "pattern", "url", "description").firstNotNullOfOrNull { k -> input.optString(k).takeIf { it.isNotEmpty() } }
        return v?.lineSequence()?.first()?.take(80) ?: ""
    }
}

/** The flags of `opencode run` that this OpenCode has, of --auto and --standalone (older versions lack them). */
private const val OPENCODE_FLAGS = "\$(opencode run --help 2>&1 | grep -oE -- '--(auto|standalone)\\b' | sort -u | tr '\\n' ' ')"
