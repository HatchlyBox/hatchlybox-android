package com.hatchlybox.app

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

// A small Alpine Linux run with proot (bundled as lib*.so so Android lets it execute). The three CLIs live inside it.
class Linux(private val context: Context) {
    private val base = File(context.filesDir, "linux")
    private val rootfs = File(base, "rootfs")
    private val tmp = File(base, "tmp")
    private val cache = context.cacheDir
    private val lib = context.applicationInfo.nativeLibraryDir
    /** This device's name for the other devices it syncs with: the phone's own name, else its model. */
    private val deviceName = runCatching { android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME) }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
    private val readyMark = File(base, ".ready")
    private val baseMark = File(base, ".base")
    private val agentsMark = File(base, ".agents")

    /** Everything is in: Node, Java and the three agents. */
    val isReady get() = readyMark.exists() && File(rootfs, "bin/busybox").exists()
    /** The AI helpers are in (Node and the three agents): chat, web apps, widgets and themes work; Java may still be coming. */
    val hasAgents get() = isReady || agentsMark.exists()
    /** The basics are in (Alpine, bash, git, jq): enough to open the app while the rest installs. */
    val hasBasics get() = File(rootfs, "bin/busybox").exists() && (isReady || baseMark.exists())

    // The host-side file behind a path inside the guest, e.g. "/root/work/apps/x".
    fun hostFile(guestPath: String) = File(rootfs, guestPath.trimStart('/'))

    // Runs `command` with /bin/sh inside the guest. With pty, it gets a real terminal (via `script`), for login prompts.
    fun start(command: String, extraEnv: Map<String, String> = emptyMap(), pty: Boolean = false, cwd: String = "/root/work", cols: Int = PTY_COLS, rows: Int = 40): Process {
        tmp.mkdirs()
        val args = mutableListOf(
            "$lib/libproot.so", "--kill-on-exit", "--link2symlink", "-0", "-r", rootfs.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "${rootfs.path}/tmp:/dev/shm", "-w", cwd,
        )
        // Android hides some /proc files from apps; Node and friends read them, so give them stand-ins.
        for (name in FAKE_PROC.keys) {
            if (!File("/proc/$name").canRead()) args += listOf("-b", "${rootfs.path}/etc/fakeproc/$name:/proc/$name")
        }
        val env = mapOf(
            "HOME" to "/root", "USER" to "root", "SHELL" to "/bin/bash", "LANG" to "C.UTF-8", "TMPDIR" to "/tmp",
            "PATH" to "/root/.local/bin:/root/.opencode/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM" to if (pty) "xterm-256color" else "dumb",
            "IS_SANDBOX" to "1", "USE_BUILTIN_RIPGREP" to "0", "DISABLE_AUTOUPDATER" to "1", "BROWSER" to "/root/.local/bin/xdg-open", "ADB_SERVER_SOCKET" to "tcp:127.0.0.1:5041",
            // Hatchlybox Engine's folders in this app (a new install, so they have the engine's own names).
            "HATCHLYBOX_HOME" to "/root/.local/share/hatchlybox", "HATCHLYBOX_CACHE" to "/root/.cache/hatchlybox", "HATCHLYBOX_BACKUPS" to "/root/Backups/hatchlybox",
            "HATCHLYBOX_APPS" to "/root/apps", "HATCHLYBOX_PORTS" to "/root/ports", "HATCHLYBOX_DEVICE_NAME" to deviceName,
        ) + (if (pty) emptyMap() else mapOf("NO_COLOR" to "1")) + extraEnv
        val script = if (pty) {
            "exec script -qfec 'stty cols $cols rows $rows 2>/dev/null; eval \"\$CMD\"' /dev/null"
        } else command
        args += listOf("/usr/bin/env", "-i") + env.map { "${it.key}=${it.value}" } +
            (if (pty) listOf("CMD=$command") else emptyList()) + listOf("/bin/sh", "-c", script)
        val pb = ProcessBuilder(args).redirectErrorStream(true)
        pb.environment().apply {
            put("PROOT_LOADER", "$lib/libproot-loader.so")
            put("PROOT_TMP_DIR", tmp.path)
            put("LD_LIBRARY_PATH", lib)
        }
        return pb.start()
    }

    // Runs a command to the end and returns its exit code and output.
    fun run(command: String, timeoutMs: Long = 120_000): Pair<Int, String> {
        val p = start(command)
        p.outputStream.close()
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = java.util.Timer(true)
        watchdog.schedule(object : java.util.TimerTask() { override fun run() { timedOut.set(true); p.destroy() } }, timeoutMs)
        return try {
            val out = StringBuilder(); val buffer = CharArray(8192)
            p.inputStream.reader().use { reader ->
                while (true) { val n = reader.read(buffer); if (n < 0) break; out.append(buffer, 0, n); if (out.length > 262144) out.delete(0, out.length - 262144) }
            }
            val rc = p.waitFor()
            (if (timedOut.get()) 124 else rc) to (Ansi.strip(out.toString()).trim() + if (timedOut.get()) "\nTimed out. Check the task before retrying." else "")
        } finally { watchdog.cancel(); p.destroy() }
    }

    // First launch, step 1: Alpine and a few small packages, a minute or so. Safe to run again.
    @Synchronized fun installBasics(log: (String) -> Unit) {
        if (!File(rootfs, "bin/busybox").exists()) {
            SafeFiles.space(context, 512L * 1024 * 1024)
            log("› Downloading Alpine Linux")
            val releases = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/"
            val name = Regex("""file: (alpine-minirootfs-\S+-aarch64\.tar\.gz)""")
                .find(fetchText(releases + "latest-releases.yaml"))?.groupValues?.get(1)
                ?: error("couldn't find the Alpine release")
            val tar = File(cache, "rootfs.tar")
            URL(releases + name).openConnection().apply { connectTimeout = 20000; readTimeout = 30000 }.getInputStream().use { input ->
                GZIPInputStream(input).use { gz -> tar.outputStream().use { gz.copyTo(it) } }
            }
            log("› Unpacking")
            if (rootfs.exists() && rootfs.listFiles().orEmpty().isNotEmpty()) {
                val recovery = File(base, "recovery-rootfs-${System.currentTimeMillis()}")
                check(rootfs.renameTo(recovery)) { "Cannot preserve the damaged environment. Repair stopped without deleting it." }
                log("Saved the entire previous environment in ${recovery.name}. Nothing was deleted.")
                readyMark.delete(); baseMark.delete(); agentsMark.delete()
            }
            rootfs.mkdirs()
            val p = ProcessBuilder("/system/bin/tar", "-xf", tar.path, "-C", rootfs.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            tar.delete()
            if (p.waitFor() != 0 && !File(rootfs, "bin/busybox").exists()) error("unpacking failed: $out")
        }
        File(rootfs, "etc/resolv.conf").apply { delete(); writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n") }
        File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost\n")
        File(rootfs, "root/work").mkdirs()
        File(rootfs, "tmp").apply { mkdirs(); setWritable(true, false) }
        File(rootfs, "etc/fakeproc").mkdirs()
        FAKE_PROC.forEach { (name, text) -> File(rootfs, "etc/fakeproc/$name").writeText(text) }

        script(BASICS, log)
        baseMark.writeText("1")
    }

    // Step 2, in the background: the AI helpers first (Node and the three CLIs, OpenCode first: it needs no
    // account), so they work while step 3 installs Java and adb for Android apps. Run again to update them.
    @Synchronized fun install(log: (String) -> Unit, agentsReady: () -> Unit = {}) {
        if (!hasBasics) installBasics(log)
        script(AGENTS + "\n" + agentsScript(), log)
        agentsMark.writeText("1")
        agentsReady()
        script(BUILD, log)
        readyMark.writeText("2")
    }

    /** The AI helpers to their latest versions (each downloaded only when behind); returns the ones that changed. */
    @Synchronized fun updateAgents(log: (String) -> Unit): List<String> {
        val changed = ArrayList<String>()
        script(agentsScript()) { line -> log(line); Regex("^✓ updated (\\S+)").find(line)?.let { changed += it.groupValues[1] } }
        return changed
    }

    private fun agentsScript(): String {
        val helper = hostFile("/root/.local/bin/helper-backup")
        helper.parentFile?.mkdirs()
        helper.writeBytes(context.assets.open("agents/helper_backup.py").use { it.readBytes() }); helper.setExecutable(true)
        return context.assets.open("agents/agents.sh").use { it.readBytes() }.decodeToString()
    }

    private fun script(text: String, log: (String) -> Unit) {
        val p = start(text)
        p.outputStream.close()
        try {
            p.inputStream.bufferedReader().use { reader -> reader.forEachLine { line -> Ansi.strip(line).trim().takeIf { it.isNotEmpty() }?.let(log) } }
            if (p.waitFor() != 0) error("Setup stopped with an error. Check the setup log and try again.")
        } finally { p.destroy() }
    }

    fun reset() {
        base.deleteRecursively()
    }

    private fun fetchText(url: String): String =
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 20000; readTimeout = 20000
            try { inputStream.bufferedReader().use { it.readText() } } finally { disconnect() }
        }

    companion object {
        const val PTY_COLS = 500

        private val BASICS = """
            set -e
            echo '› Installing the basics'
            sed -i 's|^#\(.*\/community\)$|\1|' /etc/apk/repositories
            apk update -q
            apk add -q --no-progress bash curl git ca-certificates jq coreutils findutils grep sed gawk procps-ng
        """.trimIndent()

        private val AGENTS = """
            set -e
            echo '› Installing Node.js and tools'
            apk update -q
            apk add -q --no-progress bash curl git ca-certificates nodejs npm libgcc libstdc++ ripgrep util-linux util-linux-misc unzip zip jq openssl github-cli coreutils findutils grep sed gawk procps-ng python3 nano gcompat
        """.trimIndent()

        private val BUILD = """
            set -e
            echo '› Installing Java and the Android tools'
            apk add -q --no-progress openjdk17-jdk android-tools
            if command -v java >/dev/null; then echo "✓ java ready"; else echo "! java missing"; exit 1; fi
        """.trimIndent()

        private val FAKE_PROC = mapOf(
            "loadavg" to "0.12 0.07 0.02 2/165 765\n",
            "stat" to "cpu  1957 0 2877 93280 262 342 254 87 0 0\ncpu0 31 0 226 12027 82 10 4 9 0 0\n" +
                "intr 63361 0 9 0 0 0 0 0 0\nctxt 38014093\nbtime 1694292441\nprocesses 26442\nprocs_running 1\nprocs_blocked 0\n",
            "uptime" to "124.08 932.80\n",
            "version" to "Linux version 6.2.1 (proot@termux) #1 SMP PREEMPT_DYNAMIC\n",
            "vmstat" to "nr_free_pages 1743136\nnr_zone_inactive_anon 179281\nnr_zone_active_anon 7183\n",
        )
    }
}
