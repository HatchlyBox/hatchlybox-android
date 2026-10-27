// Relay.java: meets two Hatchlybox devices that can't reach each other directly (different networks) and passes
// bytes between them. It only ever sees what the devices encrypted for each other (Sync.java's SPAKE2 keys),
// so it needs no secrets and keeps nothing. Run it anywhere both devices can reach:
//   java Relay.java <port>
// A device's first line says what it wants:
//   HATCHLYBOX1 host        a code being shown: answered "plate <n>" (a number free here), later "paired"
//   HATCHLYBOX1 join <n>    the device typing that code: "paired", or "none"
//   HATCHLYBOX1 wait <ch>   a paired device, ready to be called on its meeting point: later "paired"
//   HATCHLYBOX1 call <ch>   its partner calling: "paired", or "none"
// After "paired" the two connections are one. Limits: 10 minutes to be met, 8 GB each way, 2 hours a
// connection, 500 connections at once and 20 from one address.
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

public class Relay {
    static final long MEET_MS = 10 * 60 * 1000, LIFE_MS = 2 * 60 * 60 * 1000, MAX_BYTES = 8L << 30;
    static final int MAX_CONN = 500, MAX_PER_ADDRESS = 20;
    static final SecureRandom RNG = new SecureRandom();
    record Waiting(Socket s, long since) {}
    static final Map<String, Waiting> hosts = new ConcurrentHashMap<>(), waits = new ConcurrentHashMap<>();
    static final Map<InetAddress, Integer> perAddress = new ConcurrentHashMap<>();
    static final Semaphore slots = new Semaphore(MAX_CONN);

    public static void main(String[] a) throws Exception {
        ServerSocket server = new ServerSocket(Integer.parseInt(a[0]));
        System.out.println("Hatchlybox relay on port " + server.getLocalPort());
        // Nobody came in time: let them go.
        ScheduledExecutorService clean = Executors.newSingleThreadScheduledExecutor();
        clean.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            for (Map<String, Waiting> m : List.of(hosts, waits))
                m.entrySet().removeIf(e -> { if (now - e.getValue().since() > MEET_MS || e.getValue().s().isClosed()) { close(e.getValue().s()); return true; } return false; });
        }, 30, 30, TimeUnit.SECONDS);
        while (true) {
            Socket s = server.accept();
            InetAddress from = s.getInetAddress();
            if (!slots.tryAcquire() ) { close(s); continue; }
            if (perAddress.merge(from, 1, Integer::sum) > MAX_PER_ADDRESS) { done(s); continue; }
            Thread t = new Thread(() -> handle(s));
            t.setDaemon(true); t.start();
        }
    }

    static void handle(Socket s) {
        boolean kept = false;
        try {
            s.setSoTimeout(15_000);
            String[] m = readLine(s.getInputStream()).split(" ");
            s.setSoTimeout(0);
            if (m.length < 2 || !m[0].equals("HATCHLYBOX1")) return;
            switch (m[1]) {
                case "host" -> {
                    String plate;
                    do plate = Integer.toString(1 + RNG.nextInt(9999)); while (hosts.putIfAbsent(plate, new Waiting(s, System.currentTimeMillis())) != null);
                    send(s, "plate " + plate);
                    kept = true;
                }
                case "wait" -> {
                    if (m.length != 3 || !m[2].matches("[0-9a-f]{40}")) return;
                    Waiting old = waits.put(m[2], new Waiting(s, System.currentTimeMillis()));
                    if (old != null) done(old.s());
                    kept = true;
                }
                case "join", "call" -> {
                    if (m.length != 3) return;
                    Waiting w = (m[1].equals("join") ? hosts : waits).remove(m[2]);
                    if (w == null) { send(s, "none"); return; }
                    try { send(w.s(), "paired"); } catch (IOException e) { done(w.s()); send(s, "none"); return; }
                    send(s, "paired");
                    join(w.s(), s);
                    kept = true;
                }
                default -> { }
            }
        } catch (IOException e) {
            /* gone */
        } finally {
            if (!kept) done(s);
        }
    }

    /** Both ways at once until either side ends; then both end. */
    static void join(Socket a, Socket b) {
        long until = System.currentTimeMillis() + LIFE_MS;
        for (Socket[] p : new Socket[][] {{a, b}, {b, a}}) {
            Thread t = new Thread(() -> {
                try (InputStream in = p[0].getInputStream(); OutputStream out = p[1].getOutputStream()) {
                    byte[] buf = new byte[64 * 1024]; long total = 0; int n;
                    while ((n = in.read(buf)) > 0 && (total += n) <= MAX_BYTES && System.currentTimeMillis() < until) out.write(buf, 0, n);
                } catch (IOException e) { /* one side ended */ }
                finally { done(p[0]); done(p[1]); }
            });
            t.setDaemon(true); t.start();
        }
    }

    static void send(Socket s, String line) throws IOException { OutputStream o = s.getOutputStream(); o.write((line + "\n").getBytes(StandardCharsets.UTF_8)); o.flush(); }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1 && c != '\n') { b.write(c); if (b.size() > 120) throw new IOException("too long"); }
        return b.toString(StandardCharsets.UTF_8);
    }

    static void close(Socket s) { try { s.close(); } catch (IOException e) { /* gone */ } }

    /** Closes it and frees its place (once). */
    static final Set<Socket> freed = ConcurrentHashMap.newKeySet();
    static void done(Socket s) {
        close(s);
        if (freed.add(s)) {
            slots.release();
            perAddress.computeIfPresent(s.getInetAddress(), (k, v) -> v > 1 ? v - 1 : null);
            // Forget it after a while, so the set doesn't grow.
            CompletableFuture.delayedExecutor(1, TimeUnit.MINUTES).execute(() -> freed.remove(s));
        }
    }
}
