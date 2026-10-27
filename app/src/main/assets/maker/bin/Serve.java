// Serves the built APKs ($CACHE/apks) on 127.0.0.1 so the phone's browser can download and install an APK
// (or save an .aab for Play Console):
//   java Serve.java <port> <dir> <token> [address]
// The address is 127.0.0.1 (the phone's own browser) unless a computer offers files to phones on its Wi-Fi (0.0.0.0).
// Every Android app on the phone can reach 127.0.0.1, so files answer only under /<token>/ (a random
// token per server), and the server stops after 10 minutes without a download; hatchlybox starts it again.
// A file fully sent is noted in <dir>/.fetched/<name>, so hatchlybox knows a key backup really left.
// With "receive", a one-time page instead: the browser picks a file (the Play service account key),
// which is saved to <out>, then the server exits. Only /<token> answers, and it gives up after 15 min:
//   java Serve.java receive <port> <token> <out>
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.*;

public class Serve {
    static final String VERSION = "4";
    static final long IDLE_MS = 10 * 60 * 1000;
    static volatile long last = System.currentTimeMillis();

    public static void main(String[] a) throws Exception {
        if (a[0].equals("receive")) { receive(Integer.parseInt(a[1]), a[2], Paths.get(a[3])); return; }
        Path dir = Paths.get(a[1]).toRealPath();
        String prefix = "/" + a[2] + "/";
        String bind = a.length > 3 ? a[3] : "127.0.0.1";
        HttpServer s = HttpServer.create(new InetSocketAddress(bind, Integer.parseInt(a[0])), 0);
        s.createContext("/", ex -> {
            String p = ex.getRequestURI().getPath();
            if (p.equals("/.version")) { send(ex, 200, "text/plain", VERSION + " " + bind); return; }
            if (!p.startsWith(prefix) || p.length() == prefix.length()) { send(ex, 404, "text/plain", "hatchlybox\n"); return; }
            if (!ex.getRequestMethod().equals("GET") && !ex.getRequestMethod().equals("HEAD")) { send(ex, 405, "text/plain", "method not allowed"); return; }
            Path f = dir.resolve(p.substring(prefix.length())).normalize();
            if (!f.startsWith(dir) || f.getParent() == null || !f.getParent().equals(dir) || !Files.isRegularFile(f) || Files.isSymbolicLink(f) || !f.toRealPath().getParent().equals(dir)) {
                send(ex, 404, "text/plain", "hatchlybox\n"); return;
            }
            last = System.currentTimeMillis();
            String name = f.getFileName().toString();
            ex.getResponseHeaders().set("Content-Type", name.endsWith(".apk") ? "application/vnd.android.package-archive" : "application/octet-stream");
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name + "\"");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            if (ex.getRequestMethod().equals("HEAD")) { ex.getResponseHeaders().set("Content-Length", Long.toString(Files.size(f))); ex.sendResponseHeaders(200, -1); ex.close(); return; }
            ex.sendResponseHeaders(200, Files.size(f));
            Files.copy(f, ex.getResponseBody());
            ex.close();
            Files.createDirectories(dir.resolve(".fetched"));
            Files.writeString(dir.resolve(".fetched").resolve(name), "");
        });
        s.start();
        while (System.currentTimeMillis() - last < IDLE_MS) Thread.sleep(30_000);
        s.stop(0);
        System.exit(0);
    }

    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes("UTF-8");
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static final String PAGE = String.join("\n",
        "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">",
        "<title>Hatchlybox · Google Play key</title>",
        "<style>body{font:16px system-ui,sans-serif;max-width:32em;margin:2em auto;padding:0 1em;color:#222;background:#fafafa}",
        "@media(prefers-color-scheme:dark){body{color:#eee;background:#151515}}",
        "button,input{font:inherit;padding:.7em;margin:.3em 0;width:100%}#m{margin-top:1em;font-weight:600}</style>",
        "<h2>Connect Google Play</h2>",
        "<p>Pick the service account's <b>JSON key</b> you downloaded from Google Cloud (usually in Downloads).",
        "It stays on this phone: Hatchlybox uses it to upload your apps.</p>",
        "<input type=\"file\" id=\"f\" accept=\".json,application/json\">",
        "<button id=\"b\">Connect</button><div id=\"m\"></div>",
        "<script>",
        "b.onclick = async () => {",
        "  const file = f.files[0]; if (!file) { m.textContent = 'Pick the .json file first.'; return; }",
        "  const t = await file.text();",
        "  try { const j = JSON.parse(t); if (j.type !== 'service_account' || !j.private_key || !j.client_email) throw 0; }",
        "  catch (e) { m.textContent = 'That is not a service account key: create one in Google Cloud › Service accounts › Keys › Add key › JSON.'; return; }",
        "  const r = await fetch(location.pathname, {method: 'POST', body: t});",
        "  m.textContent = r.ok ? 'Done. Go back to the front end: it checks the key now. You can close this tab.' : 'Upload failed: ' + await r.text();",
        "};",
        "</script>",
        "");

    static void receive(int port, String token, Path out) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        s.createContext("/", ex -> {
            if (!ex.getRequestURI().getPath().equals("/" + token)) { send(ex, 404, "text/plain", "not found"); return; }
            if (ex.getRequestMethod().equals("POST")) {
                byte[] b = ex.getRequestBody().readNBytes(65537);
                if (b.length > 65536) { send(ex, 413, "text/plain", "key file too large"); return; }
                Path tmp = out.resolveSibling(out.getFileName() + ".part");
                Files.write(tmp, b);
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
                send(ex, 200, "text/plain", "ok");
                // Let the response reach the browser, then stop.
                new Thread(() -> { try { Thread.sleep(500); } catch (InterruptedException ignored) { } System.exit(0); }).start();
            } else {
                send(ex, 200, "text/html", PAGE);
            }
        });
        s.start();
        Thread.sleep(15 * 60 * 1000);
        System.exit(1);
    }
}
