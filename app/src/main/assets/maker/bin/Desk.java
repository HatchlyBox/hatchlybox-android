// Desk: the desktop front end's server (hatchlybox desktop). The page in desktop/ runs in the user's own browser and
// talks to the engine through this, on 127.0.0.1 only:
//   java Desk.java <port> <hatchlybox> <ui dir> <cache dir> <ports dir> <state file> [backups dir]
// It tries <port> and the next nine. Security: a random token per start; the launcher opens /?t=<token>, which
// sets an HttpOnly, SameSite=Strict cookie. Every other request needs that cookie and a Host of 127.0.0.1 or
// localhost on this port (so no other site or DNS rebinding can reach it); the API also needs an X-Hatchlybox
// header (other origins can't send one without a preflight, which isn't answered). The API runs hatchlybox with an
// argument list (never a shell) from a fixed set of commands. It writes {port, token, pid} to <state file> (600)
// and stops after an hour without requests; hatchlybox desktop starts it again.
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

public class Desk {
    static final String VERSION = "1";
    static final long IDLE_MS = 60 * 60 * 1000;
    static volatile long last = System.currentTimeMillis();
    static String token, hatchlybox;
    static Path ui, cache, ports, backups;
    static int port;
    // Commands the page may run, and those that take long (run as tasks the page polls).
    static final Set<String> ALLOWED = Set.of("license", "creations", "list", "poll", "history", "settings", "set", "create", "make",
        "change", "accept", "discard", "cancel", "retry", "again", "here", "install", "uninstall", "download", "wrap", "native",
        "route", "doctor", "toolchain", "agents", "version", "platform", "isolation", "setup", "setup-state", "godot", "sync", "qr", "pair",
        "connect", "adb", "delete", "build", "icons", "skills", "skill", "health", "renderer", "alive",
        "github", "play", "backup", "restore", "terminal", "installed", "verify", "checks", "keys-state", "remove");
    static final Set<String> LONG = Set.of("setup", "godot", "build", "install", "here", "sync", "pair", "connect", "delete", "wrap",
        "backup", "restore", "verify", "play", "github");
    static final Map<String, Task> tasks = new ConcurrentHashMap<>();
    static final ExecutorService pool = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });

    static class Task {
        final StringBuffer out = new StringBuffer();
        volatile Integer code;
        // A pairing (sync visible, sync ask) waits for the person's answers on its standard input.
        volatile Process process; volatile boolean interactive;
        final long started = System.currentTimeMillis();
        final String what;
        Task(String what) { this.what = what; }
    }

    public static void main(String[] a) throws Exception {
        int want = Integer.parseInt(a[0]);
        hatchlybox = a[1]; ui = Paths.get(a[2]).toRealPath(); cache = Paths.get(a[3]); ports = Paths.get(a[4]);
        Path state = Paths.get(a[5]);
        backups = a.length > 6 ? Paths.get(a[6]) : cache.resolve("backups");
        // Files the page sent (a Play key, a backup to restore) don't outlive a day.
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(cache.resolve("uploads"))) {
            for (Path f : ds) if (System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis() > 86_400_000L) Files.deleteIfExists(f);
        } catch (IOException e) { }
        byte[] b = new byte[24]; new SecureRandom().nextBytes(b);
        token = HexFormat.of().formatHex(b);
        HttpServer s = null;
        for (int p = want; p < want + 10 && s == null; p++) {
            try { s = HttpServer.create(new InetSocketAddress("127.0.0.1", p), 0); port = p; } catch (IOException e) { s = null; }
        }
        if (s == null) { System.err.println("no free port from " + want); System.exit(1); }
        s.createContext("/", Desk::handle);
        s.setExecutor(Executors.newFixedThreadPool(6));
        s.start();
        Path tmp = state.resolveSibling(state.getFileName() + ".tmp");
        Files.createDirectories(state.getParent());
        // build: which engine's server this is (hatchlybox desktop replaces one from before an update).
        String build = System.getenv().getOrDefault("HATCHLYBOX_DESK_BUILD", "").replaceAll("[^0-9a-f]", "");
        Files.writeString(tmp, "{\"port\": " + port + ", \"token\": \"" + token + "\", \"pid\": " + ProcessHandle.current().pid() + ", \"version\": " + VERSION + ", \"build\": \"" + build + "\"}\n");
        try { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")); } catch (Exception e) { }
        Files.move(tmp, state, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        System.out.println("http://127.0.0.1:" + port + "/");
        while (System.currentTimeMillis() - last < IDLE_MS) Thread.sleep(30_000);
        Files.deleteIfExists(state);
        System.exit(0);
    }

    static void handle(HttpExchange ex) throws IOException {
        try {
            last = System.currentTimeMillis();
            String path = ex.getRequestURI().getPath(), method = ex.getRequestMethod();
            String host = Optional.ofNullable(ex.getRequestHeaders().getFirst("Host")).orElse("");
            if (!host.equals("127.0.0.1:" + port) && !host.equals("localhost:" + port)) { send(ex, 421, "text/plain", "hatchlybox"); return; }
            if (path.equals("/.version")) { send(ex, 200, "text/plain", "hatchlybox-desktop " + VERSION); return; }
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            if (path.equals("/") && token.equals(q.get("t"))) {
                ex.getResponseHeaders().add("Set-Cookie", "hatchlybox=" + token + "; Path=/; HttpOnly; SameSite=Strict");
                ex.getResponseHeaders().set("Location", "/");
                send(ex, 302, "text/plain", ""); return;
            }
            if (!token.equals(cookie(ex))) {
                send(ex, 403, "text/html; charset=utf-8", "<!doctype html><meta charset=utf-8><title>Hatchlybox</title><body style=\"font:16px system-ui;padding:40px\">"
                    + "<h1>Open Hatchlybox from its launcher</h1><p>This page only works from the Hatchlybox app (or <code>hatchlybox desktop</code>), which signs it in.</p>"); return;
            }
            if (path.startsWith("/api/")) {
                if (!"1".equals(ex.getRequestHeaders().getFirst("X-Hatchlybox"))) { send(ex, 403, "text/plain", "missing header"); return; }
                api(ex, path.substring(5), method, q); return;
            }
            if (path.startsWith("/web/")) { webApp(ex, path.substring(5)); return; }
            if (path.startsWith("/icon/")) {
                String slug = path.substring(6);
                if (!slug.matches("[a-z0-9][a-z0-9-]*")) { send(ex, 404, "text/plain", ""); return; }
                // The engine keeps each app's newest icon as <slug>-<time>.png.
                Path icon = null;
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(cache.resolve("icons"), slug + "-*.png")) {
                    for (Path p : ds) {
                        if (!p.getFileName().toString().matches(java.util.regex.Pattern.quote(slug) + "-\\d+\\.png")) continue;
                        if (icon == null || p.getFileName().toString().compareTo(icon.getFileName().toString()) > 0) icon = p;
                    }
                } catch (IOException e) { }
                if (icon == null) { send(ex, 404, "text/plain", ""); return; }
                file(ex, icon, false); return;
            }
            String rel = path.equals("/") ? "index.html" : path.substring(1);
            Path f = ui.resolve(rel).normalize();
            if (!f.startsWith(ui)) { send(ex, 404, "text/plain", ""); return; }
            file(ex, f, true);
        } catch (Exception e) {
            try { send(ex, 500, "text/plain", String.valueOf(e)); } catch (Exception ignored) { }
        } finally { ex.close(); }
    }

    // api: run (a command), task (a long one's output), job (the current job and its log), reveal (a folder).
    static void api(HttpExchange ex, String what, String method, Map<String, String> q) throws Exception {
        switch (what) {
            case "run" -> {
                if (!method.equals("POST")) { send(ex, 405, "text/plain", ""); return; }
                String body = new String(ex.getRequestBody().readNBytes(64 * 1024), StandardCharsets.UTF_8);
                List<String> args = Json.strings(body, "args");
                // What the command reads on its standard input (a passphrase): passed straight to it, never kept.
                String input = Json.string(body, "input");
                boolean bg = body.matches("(?s).*\"bg\"\\s*:\\s*true.*");
                if (args.isEmpty() || !ALLOWED.contains(args.get(0))) { send(ex, 400, "application/json", "{\"error\": \"not allowed\"}"); return; }
                for (String x : args) if (x.length() > 8000 || x.indexOf('\0') >= 0) { send(ex, 400, "application/json", "{\"error\": \"bad argument\"}"); return; }
                if (bg || LONG.contains(args.get(0))) {
                    String id = Long.toString(System.nanoTime(), 36);
                    Task t = new Task(String.join(" ", args));
                    t.interactive = args.size() > 1 && args.get(0).equals("sync") && args.get(1).matches("visible|ask|send|receive");
                    tasks.put(id, t);
                    pool.submit(() -> { t.code = run(args, t.out, 3 * 60 * 60, input, t); });
                    send(ex, 200, "application/json", "{\"task\": " + Json.q(id) + "}");
                } else {
                    StringBuffer out = new StringBuffer();
                    int code = run(args, out, 120, input, null);
                    send(ex, 200, "application/json", "{\"code\": " + code + ", \"out\": " + Json.q(out.toString()) + "}");
                }
            }
            case "task" -> {
                Task t = tasks.get(q.getOrDefault("id", ""));
                if (t == null) { send(ex, 404, "application/json", "{}"); return; }
                String o = t.out.toString(); if (o.length() > 40000) o = o.substring(o.length() - 40000);
                send(ex, 200, "application/json", "{\"done\": " + (t.code != null) + ", \"code\": " + t.code + ", \"what\": " + Json.q(t.what) + ", \"out\": " + Json.q(o) + "}");
            }
            case "say" -> {
                // The person's answer to a pairing that asks (yes or no), or stop: the pairing ends.
                Task t = tasks.get(q.getOrDefault("id", ""));
                String a = q.getOrDefault("answer", "");
                if (t == null || t.process == null || !t.interactive || !a.matches("yes|no|stop")) { send(ex, 400, "application/json", "{}"); return; }
                if (a.equals("stop")) t.process.destroy();
                else try { OutputStream o = t.process.getOutputStream(); o.write((a + "\n").getBytes(StandardCharsets.UTF_8)); o.flush(); } catch (IOException e) { }
                send(ex, 200, "application/json", "{}");
            }
            case "job" -> {
                String job = read(cache.resolve("job.json"), 64 * 1024).trim();
                String log = tail(cache.resolve("log.txt"), Integer.parseInt(q.getOrDefault("lines", "40")));
                send(ex, 200, "application/json", "{\"job\": " + (job.startsWith("{") ? job : "null") + ", \"log\": " + Json.q(log) + "}");
            }
            case "joblog" -> {
                // One past job's log (the Tasks page).
                String id = q.getOrDefault("id", "");
                if (!id.matches("[0-9A-Za-z-]{1,80}")) { send(ex, 400, "application/json", "{}"); return; }
                send(ex, 200, "application/json", "{\"log\": " + Json.q(tail(cache.resolve("jobs").resolve(id).resolve("log.txt"), 200)) + "}");
            }
            case "upload" -> {
                // A file the page picked, saved privately for the command that takes it (play key, restore).
                if (!method.equals("POST")) { send(ex, 405, "text/plain", ""); return; }
                String name = q.getOrDefault("name", "file").replaceAll("[^A-Za-z0-9._-]", "_");
                if (name.length() > 80) name = name.substring(name.length() - 80);
                Path dir = cache.resolve("uploads"); Files.createDirectories(dir);
                try { Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------")); } catch (Exception e) { }
                Path f = dir.resolve(Long.toString(System.nanoTime(), 36) + "-" + name);
                long max = 2L << 30, n = 0;
                try (InputStream in = ex.getRequestBody(); OutputStream o = Files.newOutputStream(f)) {
                    try { Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------")); } catch (Exception e) { }
                    byte[] buf = new byte[65536]; int r;
                    while ((r = in.read(buf)) > 0) { n += r; if (n > max) break; o.write(buf, 0, r); }
                }
                if (n > max || n == 0) { Files.deleteIfExists(f); send(ex, 400, "application/json", "{\"error\": \"" + (n == 0 ? "empty file" : "too big") + "\"}"); return; }
                send(ex, 200, "application/json", "{\"path\": " + Json.q(f.toString()) + "}");
            }
            case "reveal" -> {
                String slug = q.getOrDefault("slug", ""), sys = q.getOrDefault("system", "");
                if (q.containsKey("backups")) {
                    Files.createDirectories(backups);
                    String opener = System.getenv().getOrDefault("HATCHLYBOX_OPENER", "xdg-open");
                    new ProcessBuilder(opener, backups.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                    send(ex, 200, "application/json", "{\"path\": " + Json.q(backups.toString()) + "}"); return;
                }
                if (!slug.matches("[a-z0-9][a-z0-9-]*") || !sys.matches("[a-z0-9][a-z0-9-]*")) { send(ex, 400, "text/plain", ""); return; }
                Path d = ports.resolve(slug).resolve(sys).resolve("export");
                if (!Files.isDirectory(d)) d = d.getParent();
                if (!Files.isDirectory(d)) { send(ex, 404, "application/json", "{\"error\": \"no folder\"}"); return; }
                String opener = System.getenv().getOrDefault("HATCHLYBOX_OPENER", "xdg-open");
                new ProcessBuilder(opener, d.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                send(ex, 200, "application/json", "{\"path\": " + Json.q(d.toString()) + "}");
            }
            default -> send(ex, 404, "application/json", "{}");
        }
    }

    // run: hatchlybox with these arguments, output and errors together, at most <secs> seconds.
    static int run(List<String> args, StringBuffer out, int secs, String input, Task task) {
        try {
            List<String> cmd = new ArrayList<>(); cmd.add(hatchlybox); cmd.addAll(args);
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            boolean keep = task != null && task.interactive;
            if (input == null && !keep) pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
            Process p = pb.start();
            if (task != null) task.process = p;
            if (input != null && !keep) try (OutputStream in = p.getOutputStream()) { in.write((input + "\n").getBytes(StandardCharsets.UTF_8)); } catch (IOException e) { }
            Thread r = new Thread(() -> {
                try (Reader in = new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
                    char[] buf = new char[4096]; int n;
                    while ((n = in.read(buf)) > 0) { out.append(buf, 0, n); if (out.length() > 400000) out.delete(0, out.length() - 200000); }
                } catch (IOException ignored) { }
            });
            r.start();
            if (!p.waitFor(secs, TimeUnit.SECONDS)) { p.destroy(); out.append("\n(stopped after ").append(secs).append(" s)"); r.join(2000); return 124; }
            r.join(5000);
            return p.exitValue();
        } catch (Exception e) { out.append(e.toString()); return 1; }
    }

    // A web version: its export folder, read-only.
    static void webApp(HttpExchange ex, String rest) throws IOException {
        int i = rest.indexOf('/');
        String slug = i < 0 ? rest : rest.substring(0, i);
        if (!slug.matches("[a-z0-9][a-z0-9-]*")) { send(ex, 404, "text/plain", ""); return; }
        if (i < 0) { ex.getResponseHeaders().set("Location", "/web/" + slug + "/"); send(ex, 302, "text/plain", ""); return; }
        Path root = ports.resolve(slug).resolve("web").resolve("export");
        if (!Files.isDirectory(root)) { send(ex, 404, "text/plain", "no web version"); return; }
        root = root.toRealPath();
        String rel = URLDecoder.decode(rest.substring(i + 1), StandardCharsets.UTF_8);
        if (rel.isEmpty() || rel.endsWith("/")) rel += "index.html";
        Path f = root.resolve(rel).normalize();
        if (!f.startsWith(root) || (Files.exists(f) && !f.toRealPath().startsWith(root))) { send(ex, 404, "text/plain", ""); return; }
        // A creation's page is its own: it may not frame or call the front end (it's sandboxed, like any site).
        ex.getResponseHeaders().set("Content-Security-Policy", "sandbox allow-scripts allow-forms allow-modals allow-popups allow-downloads allow-pointer-lock");
        file(ex, f, false);
    }

    static void file(HttpExchange ex, Path f, boolean frontEnd) throws IOException {
        if (!Files.isRegularFile(f)) { send(ex, 404, "text/plain", "not found"); return; }
        if (frontEnd) {
            ex.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; frame-ancestors 'none'");
            ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        }
        ex.getResponseHeaders().set("Content-Type", type(f.getFileName().toString()));
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        long n = Files.size(f);
        ex.sendResponseHeaders(200, n == 0 ? -1 : n);
        if (n > 0) try (OutputStream o = ex.getResponseBody()) { Files.copy(f, o); }
    }

    static String type(String n) {
        n = n.toLowerCase(Locale.ROOT);
        String[][] t = {{".html", "text/html; charset=utf-8"}, {".js", "text/javascript; charset=utf-8"}, {".mjs", "text/javascript; charset=utf-8"},
            {".css", "text/css; charset=utf-8"}, {".json", "application/json"}, {".svg", "image/svg+xml"}, {".png", "image/png"}, {".jpg", "image/jpeg"},
            {".jpeg", "image/jpeg"}, {".gif", "image/gif"}, {".webp", "image/webp"}, {".ico", "image/x-icon"}, {".wasm", "application/wasm"},
            {".pck", "application/octet-stream"}, {".mp3", "audio/mpeg"}, {".ogg", "audio/ogg"}, {".wav", "audio/wav"}, {".mp4", "video/mp4"},
            {".woff2", "font/woff2"}, {".woff", "font/woff"}, {".ttf", "font/ttf"}, {".txt", "text/plain; charset=utf-8"}, {".md", "text/plain; charset=utf-8"}};
        for (String[] x : t) if (n.endsWith(x[0])) return x[1];
        return "application/octet-stream";
    }

    static String cookie(HttpExchange ex) {
        for (String h : ex.getRequestHeaders().getOrDefault("Cookie", List.of()))
            for (String c : h.split(";")) { c = c.trim(); if (c.startsWith("hatchlybox=")) return c.substring("hatchlybox=".length()); }
        return null;
    }

    static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    static String read(Path f, int max) {
        try (InputStream in = Files.newInputStream(f)) { return new String(in.readNBytes(max), StandardCharsets.UTF_8); } catch (IOException e) { return ""; }
    }

    static String tail(Path f, int lines) {
        try (RandomAccessFile r = new RandomAccessFile(f.toFile(), "r")) {
            long len = r.length(), from = Math.max(0, len - 64 * 1024);
            r.seek(from); byte[] b = new byte[(int) (len - from)]; r.readFully(b);
            String[] all = new String(b, StandardCharsets.UTF_8).split("\n", -1);
            int start = Math.max(from > 0 ? 1 : 0, all.length - Math.max(1, Math.min(lines, 400)));
            return String.join("\n", Arrays.copyOfRange(all, start, all.length));
        } catch (IOException e) { return ""; }
    }

    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    // Just enough JSON: quoting, and an array of strings under one key.
    static class Json {
        static String q(String s) {
            StringBuilder b = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\"); case '\n' -> b.append("\\n"); case '\r' -> b.append("\\r"); case '\t' -> b.append("\\t");
                    default -> { if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == 0x2028 || c == 0x2029) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
                }
            }
            return b.append('"').toString();
        }
        // string: the value of one string key (null: none).
        static String string(String json, String key) {
            int k = json.indexOf("\"" + key + "\"");
            if (k < 0) return null;
            int c = json.indexOf(':', k), i = c < 0 ? -1 : json.indexOf('"', c);
            if (i < 0) return null;
            List<String> one = strings("{\"x\": [" + json.substring(i, end(json, i) + 1) + "]}", "x");
            return one.isEmpty() ? null : one.get(0);
        }
        // end: where the string starting at i (a quote) ends.
        static int end(String json, int i) {
            for (int j = i + 1; j < json.length(); j++) { char d = json.charAt(j); if (d == '\\') j++; else if (d == '"') return j; }
            return json.length() - 1;
        }
        static List<String> strings(String json, String key) {
            List<String> out = new ArrayList<>();
            int k = json.indexOf("\"" + key + "\"");
            if (k < 0) return out;
            int i = json.indexOf('[', k);
            if (i < 0) return out;
            i++;
            while (i < json.length()) {
                char c = json.charAt(i);
                if (c == ']') break;
                if (c == '"') {
                    StringBuilder s = new StringBuilder(); i++;
                    while (i < json.length() && json.charAt(i) != '"') {
                        char d = json.charAt(i);
                        if (d == '\\' && i + 1 < json.length()) {
                            char e = json.charAt(++i);
                            switch (e) {
                                case 'n' -> s.append('\n'); case 't' -> s.append('\t'); case 'r' -> s.append('\r'); case 'b' -> s.append('\b'); case 'f' -> s.append('\f');
                                case 'u' -> { s.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16)); i += 4; }
                                default -> s.append(e);
                            }
                        } else s.append(d);
                        i++;
                    }
                    out.add(s.toString());
                }
                i++;
            }
            return out;
        }
    }
}
