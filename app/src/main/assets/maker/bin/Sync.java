// Sync.java: the library synced between the user's own devices, two-way.
//   java Sync.java send ...                 show a code ("7-violet-orbit") and wait up to 10 minutes for one device
//   java Sync.java receive <code> [--to h:p] find the device showing that code and sync with it
//   java Sync.java serve ...                 sync by itself with the devices paired before: answer them, and call
//                                            them when something changed here (the trigger file) or every 10 minutes
//   java Sync.java visible                   be found by nearby devices (no code) until one pairs: asks on stdin
//   java Sync.java nearby                    the devices that are visible on this Wi-Fi now (JSON)
//   java Sync.java ask <host:port> [--peer <its name>]   pair with one of them: they accept, both confirm six digits
// Nearby pairing has no code to type: the two run the same handshake with a public password, and each screen shows
// six digits from the key it made; someone in the middle makes two different keys, so the digits differ. Both people
// confirm the digits match (a line "yes" on stdin, after {"state":"confirm","sas":...}), the receiving one first
// accepts the request ({"state":"asked","device":...}); then it's a pairing like a code's.
// Options from hatchlybox: --engine <hatchlybox> --name <this device> --id <its id> --devices <devices.tsv>
//   [--relay host:port] [--trigger <file>].
// Finding each other: on the same Wi-Fi a UDP broadcast that only the right device answers ("find <number>"
// for a code, "call <device> <caller>" for a paired one); on different networks the relay (Relay.java), which
// only passes bytes along. Either way the two devices then run SPAKE2 (a PAKE): the password is the code's
// words, or a paired device's key, so neither the network nor the relay learns anything, and a code has one
// guess. Pairing with a code leaves both devices a shared key (devices.tsv), for every sync after.
// Each sync: manifests both ways (hatchlybox sync manifest), packs both ways (hatchlybox sync pack), each side merges
// (hatchlybox sync apply). Progress on stdout, one JSON object per line ({"state": ...}); in a terminal, in words
// with the code as a QR code.
// Tests: HATCHLYBOX_SYNC_UDP / HATCHLYBOX_SYNC_CALL_UDP (the discovery ports), HATCHLYBOX_SYNC_FIND (addresses to ask
// instead of broadcasting), HATCHLYBOX_SYNC_CALL_TO (the port a call goes to), HATCHLYBOX_SYNC_CODE (send: this code),
// HATCHLYBOX_SYNC_EVERY (serve: seconds between calls).
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class Sync {
    static final String WORDS = "acid acorn actor aim alarm album alien alpine amber angle ankle apple april arch arena armor arrow atlas atom aunt autumn awake bacon badge bake balloon bamboo banana banjo barn basil basket beach beacon beam bean bear beaver bee bell belt bench berry bike bird biscuit blade blanket blaze bloom blue boat bolt bonus book boot bottle bow box brave bread brick bridge brook broom brush bubble bucket bud bunny butter button cabin cactus cake camel camera candle canoe canyon cape card carrot castle cat cedar cello chain chalk cheese cherry chess chief chip cider cinema circle citrus city clay cliff clock cloud clover coach coast cocoa coin comet coral corn cotton cove crab crane crayon cream creek crown cup curry daisy dance dawn deer delta desert diamond dingo disco dock dolphin donut dove dragon dream drum duck dune eagle echo eclipse elbow elm ember emerald engine falcon feather fern ferry fiddle fig flame flute focus forest fossil fox frost fudge galaxy garden garlic gecko gem ginger glacier globe glove goat gold grape gravel guitar hammock harbor harp hazel heron hill honey hope horizon igloo island ivory jacket jade jam jazz jelly jungle kayak kettle kite kiwi koala lagoon lake lamp lantern lava lemon lily lime lion llama lotus lunar mango maple marble meadow melon meteor mint mirror mocha moon moose moss mountain muffin nectar nest noodle oak oasis ocean olive onion opal orange orbit orchid otter owl panda paper parrot peach pearl pebble pepper piano pilot pine pixel planet plum pony prism puzzle quartz quill";
    static final int FIND_UDP = Integer.parseInt(System.getenv().getOrDefault("HATCHLYBOX_SYNC_UDP", "47198"));
    static final int CALL_UDP = Integer.parseInt(System.getenv().getOrDefault("HATCHLYBOX_SYNC_CALL_UDP", "47199"));
    static final int CALL_TO = Integer.parseInt(System.getenv().getOrDefault("HATCHLYBOX_SYNC_CALL_TO", Integer.toString(CALL_UDP)));
    static final int NEAR_UDP = Integer.parseInt(System.getenv().getOrDefault("HATCHLYBOX_SYNC_NEAR_UDP", "47197"));
    static final long WAIT_MS = 10 * 60 * 1000;
    static final int CHUNK = 256 * 1024;
    static final SecureRandom RNG = new SecureRandom();
    static final Map<String, String> opts = new HashMap<>();
    static String engine, name, myId;
    static boolean human;

    public static void main(String[] a) {
        // The QR encoder alone (hatchlybox qr: a download link to scan): rows of 1 (dark) and 0.
        if (a.length == 2 && a[0].equals("qr")) { Qr.rows(a[1]).forEach(System.out::println); return; }
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < a.length; i++) {
            if (a[i].startsWith("--") && i + 1 < a.length) opts.put(a[i].substring(2), a[++i]); else rest.add(a[i]);
        }
        engine = opts.get("engine"); name = opts.getOrDefault("name", "device"); myId = opts.getOrDefault("id", "");
        human = System.console() != null && !opts.containsKey("json");
        try {
            if (rest.isEmpty() || engine == null) throw new Failure("usage: Sync send|receive <code>|serve --engine <hatchlybox>");
            switch (rest.get(0)) {
                case "send" -> send();
                case "receive" -> { if (rest.size() < 2) throw new Failure("usage: Sync receive <code>"); receive(normal(rest.get(1))); }
                case "serve" -> serve();
                case "visible" -> visible();
                case "nearby" -> nearby();
                case "ask" -> { if (rest.size() < 2) throw new Failure("usage: Sync ask <host:port>"); askNear(rest.get(1)); }
                default -> throw new Failure("usage: Sync send|receive <code>|serve|visible|nearby|ask <host:port> --engine <hatchlybox>");
            }
            System.exit(0);
        } catch (Failure f) {
            status("failed", "msg", f.getMessage()); System.exit(1);
        } catch (Exception e) {
            status("failed", "msg", "The sync stopped: " + why(e)); System.exit(1);
        }
    }

    static class Failure extends Exception { Failure(String m) { super(m); } }
    static String why(Exception e) { return e.getMessage() == null ? e.toString() : e.getMessage(); }

    // ---- with a code ----

    static void send() throws Exception {
        String[] words = WORDS.split(" ");
        String relay = opts.get("relay");
        BlockingQueue<Socket> arrived = new LinkedBlockingQueue<>();
        List<Closeable> open = new ArrayList<>();
        String plate;
        // With a relay the number comes from it (unique there); without, any number for this Wi-Fi.
        Socket hosted = null;
        if (relay != null) {
            try {
                hosted = relayOpen(relay, "host");
                String l = readLine(hosted.getInputStream());
                if (l == null || !l.startsWith("plate ")) throw new IOException("the relay said " + l);
                plate = l.substring(6);
                open.add(hosted);
            } catch (IOException e) { hosted = null; plate = Integer.toString(1 + RNG.nextInt(99)); status("note", "msg", "The relay can't be reached, so only this Wi-Fi works now."); }
        } else plate = Integer.toString(1 + RNG.nextInt(99));
        String code = System.getenv("HATCHLYBOX_SYNC_CODE");
        if (code == null) code = plate + "-" + words[RNG.nextInt(256)] + "-" + words[RNG.nextInt(256)];
        else plate = code.substring(0, code.indexOf('-'));
        final String p = plate;
        ServerSocket server = listen(FIND_UDP);
        int port = server.getLocalPort();
        open.add(server);
        DatagramSocket udp = new DatagramSocket(null);
        udp.setReuseAddress(true);
        try { udp.bind(new InetSocketAddress(FIND_UDP)); } catch (SocketException e) { closeAll(open); throw new Failure("Another code is already showing on this device."); }
        open.add(udp);
        daemon(() -> {
            byte[] buf = new byte[256];
            while (!udp.isClosed()) {
                try {
                    DatagramPacket d = new DatagramPacket(buf, buf.length);
                    udp.receive(d);
                    if (text(d).equals("HATCHLYBOX1 find " + p)) reply(udp, d, "HATCHLYBOX1 here " + p + " " + port);
                } catch (IOException e) { /* closed */ }
            }
        });
        daemon(() -> { try { arrived.add(server.accept()); } catch (IOException e) { /* closed */ } });
        if (hosted != null) {
            final Socket h = hosted;
            daemon(() -> { try { if ("paired".equals(readLine(h.getInputStream()))) arrived.add(h); } catch (IOException e) { /* closed */ } });
        }
        List<String> ips = addresses();
        String link = "hatchlybox-nightly://sync/" + code + (ips.isEmpty() ? "" : "?at=" + ips.get(0) + ":" + port);
        status("waiting", "code", code, "port", port, "addresses", ips, "link", link, "qr", Qr.rows(link));
        Socket s = arrived.poll(WAIT_MS, TimeUnit.MILLISECONDS);
        for (Closeable c : open) if (c != s) try { c.close(); } catch (IOException e) { /* gone */ }
        if (s == null) throw new Failure("No device came for the code in 10 minutes. Make a new one when you're ready.");
        Session x = handshake(s, false, code, null);
        exchange(x, false, null);
    }

    static void receive(String code) throws Exception {
        if (!code.matches("[0-9]{1,4}-[a-z]+-[a-z]+")) throw new Failure("That doesn't look like a code: a number and two words, like 7-violet-orbit.");
        String plate = code.substring(0, code.indexOf('-'));
        String relay = opts.get("relay");
        Socket s = null;
        // A scanned link says where the device was: straight there, else looked for as with a typed code.
        if (opts.containsKey("to")) {
            try { s = connect(hostPort(opts.get("to"))); } catch (Failure f) { status("note", "msg", "Not at the link's address: looking for it."); }
        }
        if (s == null) {
            status("finding", "code", plate);
            InetSocketAddress at = ask("HATCHLYBOX1 find " + plate, FIND_UDP, plate, relay != null ? 5_000 : 30_000);
            if (at != null) s = connect(at);
            else if (relay != null) {
                try { s = relayOpen(relay, "join " + plate); }
                catch (IOException e) { throw new Failure("Couldn't find the other device on this Wi-Fi, and the relay can't be reached."); }
                String l = readLine(s.getInputStream());
                if (!"paired".equals(l)) { s.close(); throw new Failure("No device is showing that code. Check the number, and that the code is still showing there."); }
            } else throw new Failure("Couldn't find the other device. Check both are on the same Wi-Fi and the code is still showing there. Some Wi-Fi (guest, hotel, office) keeps devices apart: a hotspot works.");
        }
        Session x = handshake(s, true, code, null);
        exchange(x, true, null);
    }

    /** A server on that port (the same number as its UDP one, so a firewall opens both together), else any free one. */
    static ServerSocket listen(int port) throws IOException {
        try { return new ServerSocket(port); } catch (IOException e) { return new ServerSocket(0); }
    }

    static Socket connect(InetSocketAddress to) throws Failure {
        Socket s = new Socket();
        try { s.connect(to, 10_000); return s; }
        catch (IOException e) { throw new Failure("Found the other device but couldn't connect. This Wi-Fi may keep devices apart: try a hotspot."); }
    }

    // ---- nearby: found on the Wi-Fi, no code; both people confirm the same six digits ----

    static final BufferedReader answers = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    /** The person's answer, a line on stdin: "yes", anything else (or nothing) no. */
    static boolean yes() throws IOException { String l = answers.readLine(); return l != null && l.trim().equalsIgnoreCase("yes"); }

    /** Visible to nearby devices until one pairs (or 10 minutes pass): each request is asked about first. */
    static void visible() throws Exception {
        if (myId.isEmpty()) throw new Failure("visible needs this device's id");
        ServerSocket server = listen(NEAR_UDP);
        int port = server.getLocalPort();
        DatagramSocket udp = new DatagramSocket(null);
        udp.setReuseAddress(true);
        try { udp.bind(new InetSocketAddress(NEAR_UDP)); } catch (SocketException e) { server.close(); throw new Failure("This device is already visible in another window."); }
        daemon(() -> {
            byte[] buf = new byte[256];
            while (!udp.isClosed()) {
                try {
                    DatagramPacket d = new DatagramPacket(buf, buf.length);
                    udp.receive(d);
                    String[] m = text(d).split(" ");
                    if (m.length == 3 && m[0].equals("HATCHLYBOX1") && m[1].equals("who") && !m[2].equals(myId))
                        reply(udp, d, "HATCHLYBOX1 me " + myId + " " + port + " " + name);
                } catch (IOException e) { /* closed */ }
            }
        });
        status("visible", "port", port, "name", name);
        server.setSoTimeout((int) WAIT_MS);
        try {
            while (true) {
                Socket s;
                try { s = server.accept(); } catch (SocketTimeoutException e) { throw new Failure("No device asked to pair in 10 minutes."); }
                s.setSoTimeout((int) WAIT_MS);
                String hello = readLine(s.getInputStream());
                String[] h = hello == null ? new String[0] : hello.split(" ", 4);
                if (h.length < 4 || !h[0].equals("HATCHLYBOX1") || !h[1].equals("near") || !h[2].matches("[0-9a-f]{16}")) { s.close(); continue; }
                String other = h[3];
                status("asked", "device", other, "id", h[2]);
                if (!yes()) { s.close(); status("declined", "device", other); continue; }
                Session x;
                try { x = handshake(s, false, "near", null); } catch (Exception e) { s.close(); status("error", "device", other, "msg", why(e)); continue; }
                confirm(x, other);
                exchange(x, false, null);
                return;
            }
        } finally { udp.close(); server.close(); }
    }

    /** The visible devices on this Wi-Fi now: one JSON array line, [{id, name, at}]. */
    static void nearby() throws Exception {
        List<InetAddress> targets = broadcastTargets();
        Map<String, String> found = new LinkedHashMap<>();
        try (DatagramSocket udp = new DatagramSocket()) {
            udp.setBroadcast(true); udp.setSoTimeout(300);
            byte[] q = ("HATCHLYBOX1 who " + (myId.isEmpty() ? "0" : myId)).getBytes(StandardCharsets.UTF_8), buf = new byte[256];
            long end = System.currentTimeMillis() + Long.parseLong(System.getenv().getOrDefault("HATCHLYBOX_SYNC_LOOK_MS", "2500")), again = 0;
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= again) {
                    for (InetAddress t : targets) { try { udp.send(new DatagramPacket(q, q.length, t, NEAR_UDP)); } catch (IOException e) { /* not on that network */ } }
                    again = System.currentTimeMillis() + 800;
                }
                try {
                    DatagramPacket d = new DatagramPacket(buf, buf.length);
                    udp.receive(d);
                    String[] m = text(d).split(" ", 5);
                    if (m.length == 5 && m[0].equals("HATCHLYBOX1") && m[1].equals("me") && m[2].matches("[0-9a-f]{16}") && !m[2].equals(myId) && m[3].matches("[0-9]{1,5}"))
                        found.put(m[2], "{\"id\":" + json(m[2]) + ",\"name\":" + json(m[4]) + ",\"at\":" + json(d.getAddress().getHostAddress() + ":" + m[3]) + "}");
                } catch (SocketTimeoutException e) { /* ask again */ }
            }
        }
        System.out.println("[" + String.join(",", found.values()) + "]");
    }

    /** Pairs with a visible device: it's asked first, then both confirm the digits, then they sync. */
    static void askNear(String at) throws Exception {
        if (myId.isEmpty()) throw new Failure("ask needs this device's id");
        String other = opts.getOrDefault("peer", "the other device");
        Socket s = connect(hostPort(at));
        s.getOutputStream().write(("HATCHLYBOX1 near " + myId + " " + name + "\n").getBytes(StandardCharsets.UTF_8));
        s.getOutputStream().flush();
        status("asking", "device", other);
        Session x;
        try { x = handshake(s, true, "near", null); }
        catch (Failure f) { throw new Failure(other + " didn't accept: nothing was paired."); }
        catch (IOException e) { throw new Failure(other + " didn't accept: nothing was paired."); }
        confirm(x, other);
        exchange(x, true, null);
    }

    /** Both screens show six digits from the key; both people must confirm they match. */
    static void confirm(Session x, String other) throws Exception {
        String sas = String.format("%06d", new BigInteger(1, hmac(x.pairKey, "six digits")).mod(BigInteger.valueOf(1_000_000)).intValue());
        status("confirm", "sas", sas, "device", other);
        boolean me = yes();
        x.send((me ? "match" : "no").getBytes(StandardCharsets.UTF_8)); x.out.flush();
        boolean them;
        try { them = new String(x.recv(), StandardCharsets.UTF_8).equals("match"); } catch (Exception e) { them = false; }
        if (!me || !them) {
            x.close();
            throw new Failure(!me ? "Not paired: the numbers weren't confirmed here." : other + " didn't confirm the numbers: nothing was paired.");
        }
    }

    static List<InetAddress> broadcastTargets() throws Exception {
        List<InetAddress> targets = new ArrayList<>();
        String fixed = System.getenv("HATCHLYBOX_SYNC_FIND");
        if (fixed != null) { for (String h : fixed.split(",")) targets.add(InetAddress.getByName(h.trim())); return targets; }
        for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!n.isUp() || n.isLoopback()) continue;
            for (InterfaceAddress ia : n.getInterfaceAddresses()) if (ia.getBroadcast() != null) targets.add(ia.getBroadcast());
        }
        targets.add(InetAddress.getByName("255.255.255.255"));
        return targets;
    }

    // ---- paired devices, by themselves ----

    record Device(String id, String name, byte[] key) {}

    static Map<String, Device> devices() {
        Map<String, Device> r = new LinkedHashMap<>();
        String f = opts.get("devices");
        if (f == null) return r;
        try {
            for (String l : Files.readAllLines(Paths.get(f), StandardCharsets.UTF_8)) {
                String[] c = l.split("\t");
                if (c.length >= 3 && c[1].matches("[0-9a-f]{64}")) r.put(c[0], new Device(c[0], c[2], hex(c[1])));
            }
        } catch (IOException e) { /* none yet */ }
        return r;
    }

    /** One call out at a time. */
    static final ReentrantLock one = new ReentrantLock();
    static final Map<String, Long> lastSync = new ConcurrentHashMap<>();

    static void serve() throws Exception {
        if (myId.isEmpty()) throw new Failure("serve needs this device's id");
        ServerSocket server = listen(CALL_UDP);
        int port = server.getLocalPort();
        DatagramSocket udp = new DatagramSocket(null);
        udp.setReuseAddress(true);
        try { udp.bind(new InetSocketAddress(CALL_UDP)); } catch (SocketException e) { throw new Failure("Sync is already running on this device."); }
        // Answer a paired device that calls this one.
        daemon(() -> {
            byte[] buf = new byte[256];
            while (true) {
                try {
                    DatagramPacket d = new DatagramPacket(buf, buf.length);
                    udp.receive(d);
                    String[] m = text(d).split(" ");
                    if (m.length == 4 && m[0].equals("HATCHLYBOX1") && m[1].equals("call") && m[2].equals(myId) && devices().containsKey(m[3]))
                        reply(udp, d, "HATCHLYBOX1 here " + myId + " " + port);
                } catch (IOException e) { /* go on */ }
            }
        });
        daemon(() -> {
            while (true) {
                try { Socket s = server.accept(); daemon(() -> answer(s)); } catch (IOException e) { /* go on */ }
            }
        });
        // Through the relay: one connection waiting there for each paired device.
        String relay = opts.get("relay");
        Set<String> waiting = ConcurrentHashMap.newKeySet();
        status("serving", "port", port, "devices", new ArrayList<>(devices().keySet()));
        Path trigger = opts.containsKey("trigger") ? Paths.get(opts.get("trigger")) : null;
        long every = Long.parseLong(System.getenv().getOrDefault("HATCHLYBOX_SYNC_EVERY", "600")) * 1000;
        // A moment of chance before the first call, so two devices starting together rarely call at once.
        long seen = mtime(trigger), next = System.currentTimeMillis() + 2_000 + RNG.nextInt(5_000);
        while (true) {
            Map<String, Device> known = devices();
            if (relay != null) for (Device d : known.values()) if (waiting.add(d.id())) daemon(() -> relayWait(relay, d.id(), waiting));
            long t = mtime(trigger);
            boolean changed = t != seen;
            if (changed || System.currentTimeMillis() >= next) {
                seen = t; next = System.currentTimeMillis() + every;
                for (Device d : known.values()) {
                    // Synced with it a moment ago (either side calling): not again yet, unless something changed here.
                    if (!changed && System.currentTimeMillis() - lastSync.getOrDefault(d.id(), 0L) < every / 2) continue;
                    call(d, relay);
                }
            }
            Thread.sleep(5_000);
        }
    }

    /** A paired device called (on the Wi-Fi or through the relay): the handshake says which, with its key. */
    static void answer(Socket s) {
        String who = null;
        try {
            Session x = handshake(s, false, null, devices());
            who = x.peer.name();
            // No lock here: the other device may be calling this one at the same moment, holding its own lock
            // while it waits for this answer. Two syncs at once are safe: hatchlybox sync takes turns itself.
            exchange(x, false, x.peer);
        } catch (Exception e) {
            status("error", "device", who == null ? "" : who, "msg", why(e));
            try { s.close(); } catch (IOException io) { /* gone */ }
        }
    }

    /** Calls a paired device: on this Wi-Fi first, then through the relay. Not there: nothing happens. */
    static void call(Device d, String relay) {
        Socket s = null;
        try {
            InetSocketAddress at = ask("HATCHLYBOX1 call " + d.id() + " " + myId, CALL_TO, d.id(), 2_500);
            if (at != null) s = connect(at);
            else if (relay != null) {
                s = relayOpen(relay, "call " + channel(d, d.id()));
                if (!"paired".equals(readLine(s.getInputStream()))) { s.close(); return; }
            } else return;
            if (!one.tryLock(60, TimeUnit.SECONDS)) { s.close(); return; }
            try { Session x = handshake(s, true, null, Map.of(d.id(), d)); exchange(x, true, d); }
            finally { one.unlock(); }
        } catch (Exception e) {
            status("error", "device", d.name(), "msg", why(e));
            if (s != null) try { s.close(); } catch (IOException io) { /* gone */ }
        }
    }

    static void relayWait(String relay, String id, Set<String> waiting) {
        try {
            while (true) {
                Device d = devices().get(id);
                if (d == null) return;
                try (Socket s = relayOpen(relay, "wait " + channel(d, myId))) {
                    if ("paired".equals(readLine(s.getInputStream()))) { answer(s); continue; }
                } catch (IOException e) { Thread.sleep(60_000); }
                catch (Exception e) { Thread.sleep(60_000); }
            }
        } catch (InterruptedException e) { /* stopping */ }
        finally { waiting.remove(id); }
    }

    /** The relay's meeting point for calls to that device: from the pair's key, so only the two know it. */
    static String channel(Device d, String callee) throws Exception { return hexOf(hmac(d.key(), "relay to " + callee)).substring(0, 40); }

    // ---- finding and meeting ----

    /** Asks the Wi-Fi (or HATCHLYBOX_SYNC_FIND) "message", until "HATCHLYBOX1 here <expect> <port>" comes back. */
    static InetSocketAddress ask(String message, int port, String expect, long ms) throws Exception {
        List<InetAddress> targets = new ArrayList<>();
        String fixed = System.getenv("HATCHLYBOX_SYNC_FIND");
        if (fixed != null) { for (String h : fixed.split(",")) targets.add(InetAddress.getByName(h.trim())); }
        else {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InterfaceAddress ia : n.getInterfaceAddresses()) if (ia.getBroadcast() != null) targets.add(ia.getBroadcast());
            }
            targets.add(InetAddress.getByName("255.255.255.255"));
        }
        try (DatagramSocket udp = new DatagramSocket()) {
            udp.setBroadcast(true); udp.setSoTimeout(500);
            byte[] q = message.getBytes(StandardCharsets.UTF_8), buf = new byte[256];
            long end = System.currentTimeMillis() + ms, again = 0;
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= again) {
                    for (InetAddress t : targets) { try { udp.send(new DatagramPacket(q, q.length, t, port)); } catch (IOException e) { /* not on that network */ } }
                    again = System.currentTimeMillis() + 1000;
                }
                try {
                    DatagramPacket d = new DatagramPacket(buf, buf.length);
                    udp.receive(d);
                    String[] m = text(d).split(" ");
                    if (m.length == 4 && m[0].equals("HATCHLYBOX1") && m[1].equals("here") && m[2].equals(expect))
                        return new InetSocketAddress(d.getAddress(), Integer.parseInt(m[3]));
                } catch (SocketTimeoutException e) { /* ask again */ }
            }
        }
        return null;
    }

    static Socket relayOpen(String relay, String what) throws IOException {
        Socket s = new Socket();
        s.connect(hostPort(relay), 8_000);
        s.setSoTimeout((int) WAIT_MS + 60_000);
        OutputStream o = s.getOutputStream();
        o.write(("HATCHLYBOX1 " + what + "\n").getBytes(StandardCharsets.UTF_8)); o.flush();
        return s;
    }

    /** One line, read a byte at a time so nothing after it is taken from the stream. */
    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1 && c != '\n') { b.write(c); if (b.size() > 200) throw new IOException("a line too long"); }
        return c == -1 && b.size() == 0 ? null : b.toString(StandardCharsets.UTF_8);
    }

    // ---- one sync ----

    /**
     * Manifests both ways, then packs both ways, then each side merges; the calling side goes first each time.
     * peer: the paired device expected (null with a code: then both remember each other's key).
     */
    static void exchange(Session c, boolean first, Device peer) throws Exception {
        Path w = Files.createTempDirectory("hatchlybox-sync");
        try {
            Path mine = w.resolve("mine.json"), theirs = w.resolve("theirs.json");
            engine(mine, null, "manifest");
            // The other side hanging up here means it stopped (its screen says why): said so, not as a broken stream.
            try { if (first) { c.sendFile(mine, false); c.recvFile(theirs, false); } else { c.recvFile(theirs, false); c.sendFile(mine, false); } }
            catch (EOFException | SocketException e) { throw new Failure("The other device stopped the sync: its screen says why."); }
            String m = new String(Files.readAllBytes(theirs), StandardCharsets.UTF_8);
            String otherId = jsonString(m, "id"), other = jsonString(m, "name");
            if (otherId == null || !otherId.matches("[0-9a-f]{16}")) throw new Failure("The other device didn't say who it is.");
            if (other == null) other = "the other device";
            if (peer != null && !peer.id().equals(otherId)) throw new Failure("A different device answered.");
            if (peer == null) engine(null, "{\"id\":\"" + otherId + "\",\"name\":" + json(other) + ",\"key\":\"" + hexOf(c.pairKey) + "\"}", "remember");
            status("connected", "device", other);
            Path out = w.resolve("out.tar"), in = w.resolve("in.tar");
            status("packing");
            engine(null, null, "pack", theirs.toString(), out.toString());
            if (first) { c.sendFile(out, true); c.recvFile(in, true); } else { c.recvFile(in, true); c.sendFile(out, true); }
            status("merging");
            Path result = w.resolve("result.json"), theirResult = w.resolve("their-result.json");
            engine(result, null, "apply", in.toString());
            if (first) { c.sendFile(result, false); c.recvFile(theirResult, false); } else { c.recvFile(theirResult, false); c.sendFile(result, false); }
            engine(null, null, "seen", otherId);
            lastSync.put(otherId, System.currentTimeMillis());
            String got = new String(Files.readAllBytes(result), StandardCharsets.UTF_8).trim();
            String gave = new String(Files.readAllBytes(theirResult), StandardCharsets.UTF_8).trim();
            done(peer == null ? "done" : "synced", other, got.startsWith("{") ? got : "{}", gave.startsWith("{") ? gave : "{}");
        } finally {
            c.close();
            try (var paths = Files.walk(w)) { paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        }
    }

    /** Runs hatchlybox sync <args> (input: its stdin), its output to a file; a failure ends the sync with its words. */
    static void engine(Path out, String input, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(engine, "sync")); cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Path err = Files.createTempFile("hatchlybox-sync", ".err");
        pb.redirectOutput(out != null ? ProcessBuilder.Redirect.to(out.toFile()) : ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(err.toFile());
        if (input == null) pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Process p = pb.start();
        if (input != null) try (OutputStream o = p.getOutputStream()) { o.write(input.getBytes(StandardCharsets.UTF_8)); }
        int rc = p.waitFor();
        String e = new String(Files.readAllBytes(err), StandardCharsets.UTF_8).trim();
        Files.deleteIfExists(err);
        if (rc != 0) throw new Failure("Hatchlybox couldn't " + args[0] + " the library" + (e.isEmpty() ? "." : ": " + e.lines().reduce((x, y) -> y).orElse("")));
    }

    // ---- SPAKE2 over the 2048-bit MODP group (RFC 3526, group 14) ----

    static final BigInteger P = new BigInteger(
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
        "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
        "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
        "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
        "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
        "15728E5A8AACAA68FFFFFFFFFFFFFFFF", 16);
    static final BigInteger Q = P.subtract(BigInteger.ONE).shiftRight(1);
    static final BigInteger G = BigInteger.TWO;
    // M and N: in the order-q subgroup (squares), with logarithms nobody knows (from a hash).
    static final BigInteger M = toGroup("hatchlybox sync spake2 M"), N = toGroup("hatchlybox sync spake2 N");

    static BigInteger toGroup(String label) {
        BigInteger h = new BigInteger(1, expand(label.getBytes(StandardCharsets.UTF_8), 288)).mod(P);
        return h.multiply(h).mod(P);
    }

    static boolean valid(BigInteger v) {
        return v.compareTo(BigInteger.ONE) > 0 && v.compareTo(P.subtract(BigInteger.ONE)) < 0 && v.modPow(Q, P).equals(BigInteger.ONE);
    }

    /** After the handshake: the channel, the key the two keep for later syncs, and (paired) who the other is. */
    static class Session extends Channel {
        final byte[] pairKey; final Device peer;
        Session(Channel c, byte[] pairKey, Device peer) { super(c); this.pairKey = pairKey; this.peer = peer; }
    }

    /**
     * SPAKE2: the client (A, with M) says first how it proves itself: "code", or "pair <its id>" with the key
     * the two share. The server (B, with N) answers with its element; each then proves it has the same key
     * before anything else is sent. code: the code (both sides); known: the paired devices by id.
     */
    static Session handshake(Socket s, boolean client, String code, Map<String, Device> known) throws Exception {
        s.setSoTimeout((int) WAIT_MS);
        s.setTcpNoDelay(true);
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
        String password; Device peer = null;
        BigInteger x = new BigInteger(Q.bitLength() + 64, RNG).mod(Q.subtract(BigInteger.ONE)).add(BigInteger.ONE);
        BigInteger mine, theirs, w;
        if (client) {
            if (code != null) password = "code " + code;
            else { peer = known.values().iterator().next(); password = "pair " + hexOf(peer.key()); }
            w = secret(password);
            mine = G.modPow(x, P).multiply(M.modPow(w, P)).mod(P);
            writeBlock(out, ("HATCHLYBOX1 A " + (code != null ? "code" : "pair " + myId)).getBytes(StandardCharsets.UTF_8));
            writeBlock(out, fixed(mine)); out.flush();
            if (!new String(readBlock(in, 64), StandardCharsets.UTF_8).equals("HATCHLYBOX1 B")) throw new Failure("The other side isn't a Hatchlybox sync.");
            theirs = new BigInteger(1, readBlock(in, 512));
        } else {
            String[] hello = new String(readBlock(in, 128), StandardCharsets.UTF_8).split(" ");
            if (hello.length < 3 || !hello[0].equals("HATCHLYBOX1") || !hello[1].equals("A")) throw new Failure("The other side isn't a Hatchlybox sync.");
            if (hello[2].equals("code") && code != null) password = "code " + code;
            else if (hello[2].equals("pair") && hello.length == 4 && known != null && known.containsKey(hello[3])) { peer = known.get(hello[3]); password = "pair " + hexOf(peer.key()); }
            else throw new Failure("A device that isn't paired with this one tried to sync.");
            w = secret(password);
            theirs = new BigInteger(1, readBlock(in, 512));
            mine = G.modPow(x, P).multiply(N.modPow(w, P)).mod(P);
            writeBlock(out, "HATCHLYBOX1 B".getBytes(StandardCharsets.UTF_8));
            writeBlock(out, fixed(mine)); out.flush();
        }
        if (!valid(theirs)) throw new Failure("The other device sent something that isn't a Hatchlybox sync.");
        BigInteger k = theirs.multiply((client ? N : M).modPow(Q.subtract(w), P)).mod(P).modPow(x, P);
        BigInteger X = client ? mine : theirs, Y = client ? theirs : mine;
        byte[] t = sha256(concat("hatchlybox sync 1".getBytes(StandardCharsets.UTF_8), fixed(X), fixed(Y), fixed(k), fixed(w)));
        byte[] macA = hmac(hmac(t, "confirm A"), concat(fixed(X), fixed(Y))), macB = hmac(hmac(t, "confirm B"), concat(fixed(X), fixed(Y)));
        // The server hangs up at once on a wrong code: for the client that's the answer too.
        if (client) {
            writeBlock(out, macA); out.flush();
            byte[] m; try { m = readBlock(in, 64); } catch (EOFException e) { throw wrong(code); }
            if (!MessageDigest.isEqual(m, macB)) throw wrong(code);
        } else {
            if (!MessageDigest.isEqual(readBlock(in, 64), macA)) throw wrong(code);
            writeBlock(out, macB); out.flush();
        }
        byte[] ab = hmac(t, "key A to B"), ba = hmac(t, "key B to A");
        return new Session(new Channel(s, in, out, client ? ab : ba, client ? ba : ab), hmac(t, "pair key"), peer);
    }

    static BigInteger secret(String password) { return new BigInteger(1, sha256(("hatchlybox sync|" + password).getBytes(StandardCharsets.UTF_8))).mod(Q); }

    static Failure wrong(String code) {
        return new Failure(code != null ? "The code didn't match. For safety it can't be used again: make a new code and type it carefully."
            : "The other device didn't recognise this one. Pair them again with a code.");
    }

    /** Messages after the handshake: AES-GCM, a counter as the nonce in each direction. */
    static class Channel {
        final Socket s; final DataInputStream in; final DataOutputStream out;
        final SecretKeySpec sendKey, recvKey; long sent = 0, received = 0;
        Channel(Socket s, DataInputStream in, DataOutputStream out, byte[] sk, byte[] rk) {
            this.s = s; this.in = in; this.out = out; sendKey = new SecretKeySpec(sk, "AES"); recvKey = new SecretKeySpec(rk, "AES");
        }
        Channel(Channel c) { this(c.s, c.in, c.out, c.sendKey.getEncoded(), c.recvKey.getEncoded()); }
        void send(byte[] plain) throws Exception {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, sendKey, new GCMParameterSpec(128, nonce(sent++)));
            writeBlock(out, c.doFinal(plain));
        }
        byte[] recv() throws Exception {
            byte[] b = readBlock(in, CHUNK + 64);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, recvKey, new GCMParameterSpec(128, nonce(received++)));
            try { return c.doFinal(b); } catch (Exception e) { throw new Failure("The connection was tampered with or broke. Try again."); }
        }
        /** A file: its size, its bytes in chunks, then an empty chunk. big: progress lines. */
        void sendFile(Path f, boolean big) throws Exception {
            long total = Files.size(f), done = 0, shown = 0;
            send(Long.toString(total).getBytes(StandardCharsets.UTF_8));
            try (InputStream is = Files.newInputStream(f)) {
                byte[] buf = new byte[CHUNK]; int n;
                while ((n = is.readNBytes(buf, 0, CHUNK)) > 0) {
                    send(Arrays.copyOf(buf, n)); done += n;
                    if (big && (done - shown > 2_000_000 || done == total)) { shown = done; status("sending", "bytes", done, "total", total); }
                }
            }
            send(new byte[0]); out.flush();
        }
        void recvFile(Path f, boolean big) throws Exception {
            long total = Long.parseLong(new String(recv(), StandardCharsets.UTF_8)), done = 0, shown = 0;
            if (total > 8L << 30) throw new Failure("The other device wanted to send more than 8 GB.");
            try (OutputStream os = Files.newOutputStream(f)) {
                while (true) {
                    byte[] b = recv();
                    if (b.length == 0) break;
                    os.write(b); done += b.length;
                    if (done > total) throw new Failure("The other device sent more than it said.");
                    if (big && (done - shown > 2_000_000 || done == total)) { shown = done; status("receiving", "bytes", done, "total", total); }
                }
            }
            if (done != total) throw new Failure("The connection broke before everything arrived. Try again.");
        }
        void close() { try { s.close(); } catch (IOException e) { /* gone */ } }
    }

    // ---- QR codes: byte mode, error correction M, versions 1 to 10 (up to 213 bytes) ----

    static class Qr {
        // Per version: group 1 (blocks, data codewords each), group 2 (the same), EC codewords per block.
        static final int[][] BLOCKS = {
            {}, {1, 16, 0, 0, 10}, {1, 28, 0, 0, 16}, {1, 44, 0, 0, 26}, {2, 32, 0, 0, 18}, {2, 43, 0, 0, 24},
            {4, 27, 0, 0, 16}, {4, 31, 0, 0, 18}, {2, 38, 2, 39, 22}, {3, 36, 2, 37, 22}, {4, 43, 1, 44, 26}};
        static final int[][] ALIGN = {{}, {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34}, {6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50}};

        /** The code as rows of '1' (dark) and '0', without the quiet zone around it. */
        static List<String> rows(String text) { return rows(text.getBytes(StandardCharsets.UTF_8), -1, -1); }
        /** version, mask: -1 for the smallest that fits and the best looking (tests force them). */
        static List<String> rows(byte[] data, int forceVersion, int forceMask) {
            int v = forceVersion > 0 ? forceVersion : 1;
            while (forceVersion <= 0 && v <= 10 && capacity(v) < data.length + (v < 10 ? 2 : 3)) v++;
            if (v > 10 || capacity(v) < data.length + (v < 10 ? 2 : 3)) throw new IllegalArgumentException("too long for a QR code here");
            int[] b = BLOCKS[v];
            int dataCw = capacity(v);
            // Mode 0100 (bytes), the length (8 bits, 16 from version 10), the bytes, a terminator, then padding.
            BitBuf bits = new BitBuf();
            bits.put(4, 4); bits.put(data.length, v < 10 ? 8 : 16);
            for (byte x : data) bits.put(x & 0xff, 8);
            bits.put(0, Math.min(4, dataCw * 8 - bits.n));
            while (bits.n % 8 != 0) bits.put(0, 1);
            for (int pad = 0xEC; bits.n < dataCw * 8; pad ^= 0xEC ^ 0x11) bits.put(pad, 8);
            byte[] cw = bits.bytes();
            List<byte[]> dataBlocks = new ArrayList<>(), ecBlocks = new ArrayList<>();
            int off = 0;
            for (int g = 0; g < 2; g++) for (int i = 0; i < b[g * 2]; i++) {
                byte[] d = Arrays.copyOfRange(cw, off, off + b[g * 2 + 1]); off += d.length;
                dataBlocks.add(d); ecBlocks.add(rs(d, b[4]));
            }
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            int longest = Math.max(b[1], b[3]);
            for (int i = 0; i < longest; i++) for (byte[] d : dataBlocks) if (i < d.length) all.write(d[i]);
            for (int i = 0; i < b[4]; i++) for (byte[] e : ecBlocks) all.write(e[i]);
            byte[] seq = all.toByteArray();
            int size = 17 + 4 * v, bestScore = Integer.MAX_VALUE;
            boolean[][] best = null;
            for (int mask = 0; mask < 8; mask++) {
                if (forceMask >= 0 && mask != forceMask) continue;
                boolean[][] m = new boolean[size][size], fixed = new boolean[size][size];
                functionPatterns(m, fixed, v);
                place(m, fixed, seq, mask);
                format(m, mask);
                if (v >= 7) version(m, v);
                int score = forceMask >= 0 ? 0 : penalty(m);
                if (score < bestScore) { bestScore = score; best = m; }
            }
            List<String> r = new ArrayList<>();
            for (boolean[] row : best) { StringBuilder s = new StringBuilder(); for (boolean c : row) s.append(c ? '1' : '0'); r.add(s.toString()); }
            return r;
        }
        static int capacity(int v) { int[] b = BLOCKS[v]; return b[0] * b[1] + b[2] * b[3]; }

        static class BitBuf {
            final ByteArrayOutputStream o = new ByteArrayOutputStream(); int n = 0, cur = 0;
            void put(int value, int len) { for (int i = len - 1; i >= 0; i--) { cur = (cur << 1) | ((value >>> i) & 1); n++; if (n % 8 == 0) { o.write(cur); cur = 0; } } }
            byte[] bytes() { return o.toByteArray(); }
        }

        // Reed-Solomon over GF(256), polynomial 0x11D.
        static final int[] EXP = new int[512], LOG = new int[256];
        static { int x = 1; for (int i = 0; i < 255; i++) { EXP[i] = x; LOG[x] = i; x <<= 1; if (x >= 256) x ^= 0x11D; } for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255]; }
        static int mul(int a, int b) { return a == 0 || b == 0 ? 0 : EXP[LOG[a] + LOG[b]]; }
        static byte[] rs(byte[] data, int n) {
            int[] gen = {1};
            for (int i = 0; i < n; i++) {
                int[] g = new int[gen.length + 1];
                for (int j = 0; j < gen.length; j++) { g[j] ^= gen[j]; g[j + 1] ^= mul(gen[j], EXP[i]); }
                gen = g;
            }
            int[] r = new int[n];
            for (byte d : data) {
                int f = (d & 0xff) ^ r[0];
                System.arraycopy(r, 1, r, 0, n - 1); r[n - 1] = 0;
                for (int j = 0; j < n; j++) r[j] ^= mul(gen[j + 1], f);
            }
            byte[] o = new byte[n]; for (int i = 0; i < n; i++) o[i] = (byte) r[i]; return o;
        }

        static void set(boolean[][] m, boolean[][] f, int r, int c, boolean dark) { m[r][c] = dark; f[r][c] = true; }
        static void functionPatterns(boolean[][] m, boolean[][] f, int v) {
            int size = m.length;
            for (int[] at : new int[][] {{0, 0}, {0, size - 7}, {size - 7, 0}}) {
                for (int r = -1; r <= 7; r++) for (int c = -1; c <= 7; c++) {
                    int rr = at[0] + r, cc = at[1] + c;
                    if (rr < 0 || cc < 0 || rr >= size || cc >= size) continue;
                    boolean dark = r >= 0 && r <= 6 && c >= 0 && c <= 6 && (r == 0 || r == 6 || c == 0 || c == 6 || (r >= 2 && r <= 4 && c >= 2 && c <= 4));
                    set(m, f, rr, cc, dark);
                }
            }
            for (int i = 8; i < size - 8; i++) { set(m, f, 6, i, i % 2 == 0); set(m, f, i, 6, i % 2 == 0); }
            int[] al = ALIGN[v];
            int n = al.length;
            for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
                if (i == 0 && j == 0 || i == 0 && j == n - 1 || i == n - 1 && j == 0) continue;   // where the finders are
                int r = al[i], c = al[j];
                for (int dr = -2; dr <= 2; dr++) for (int dc = -2; dc <= 2; dc++)
                    set(m, f, r + dr, c + dc, Math.max(Math.abs(dr), Math.abs(dc)) != 1);
            }
            // The format areas (filled in after masking), and the dark module.
            for (int i = 0; i < 9; i++) { f[8][i] = true; f[i][8] = true; }
            for (int i = 0; i < 8; i++) { f[8][size - 1 - i] = true; f[size - 1 - i][8] = true; }
            set(m, f, size - 8, 8, true);
            if (v >= 7) for (int i = 0; i < 6; i++) for (int j = 0; j < 3; j++) { f[i][size - 11 + j] = true; f[size - 11 + j][i] = true; }
        }
        static boolean maskBit(int mask, int r, int c) {
            return switch (mask) {
                case 0 -> (r + c) % 2 == 0; case 1 -> r % 2 == 0; case 2 -> c % 3 == 0; case 3 -> (r + c) % 3 == 0;
                case 4 -> (r / 2 + c / 3) % 2 == 0; case 5 -> (r * c) % 2 + (r * c) % 3 == 0;
                case 6 -> ((r * c) % 2 + (r * c) % 3) % 2 == 0; default -> ((r + c) % 2 + (r * c) % 3) % 2 == 0;
            };
        }
        static void place(boolean[][] m, boolean[][] f, byte[] seq, int mask) {
            int size = m.length, bit = 0, total = seq.length * 8;
            boolean up = true;
            for (int right = size - 1; right >= 1; right -= 2) {
                if (right == 6) right = 5;
                for (int i = 0; i < size; i++) {
                    int r = up ? size - 1 - i : i;
                    for (int dc = 0; dc < 2; dc++) {
                        int c = right - dc;
                        if (f[r][c]) continue;
                        boolean dark = bit < total && ((seq[bit / 8] >>> (7 - bit % 8)) & 1) == 1;
                        bit++;
                        m[r][c] = dark ^ maskBit(mask, r, c);
                    }
                }
                up = !up;
            }
        }
        /** The remainder of value·x^(degree of poly) divided by poly (bits). */
        static int bch(int value, int poly) {
            int deg = 31 - Integer.numberOfLeadingZeros(poly), v = value << deg;
            for (int i = 31 - Integer.numberOfLeadingZeros(v); i >= deg; i--) if (((v >>> i) & 1) == 1) v ^= poly << (i - deg);
            return v;
        }
        static void format(boolean[][] m, int mask) {
            int size = m.length, data = mask;   // level M is 00
            int bits = ((data << 10) | bch(data, 0x537)) ^ 0x5412;
            for (int i = 0; i < 15; i++) {
                boolean d = ((bits >>> i) & 1) == 1;
                // Bit i around the top-left finder: down column 8 (skipping the timing row), then left along row 8.
                if (i < 6) m[i][8] = d; else if (i < 8) m[i + 1][8] = d; else if (i == 8) m[8][7] = d; else m[8][14 - i] = d;
                // And again, split: along row 8 at the top right, then up column 8 at the bottom left.
                if (i < 8) m[8][size - 1 - i] = d; else m[size - 15 + i][8] = d;
            }
        }
        static void version(boolean[][] m, int v) {
            int size = m.length, bits = (v << 12) | bch(v, 0x1F25);
            for (int i = 0; i < 18; i++) {
                boolean d = ((bits >>> i) & 1) == 1;
                int a = i / 3, b = size - 11 + i % 3;
                m[a][b] = d; m[b][a] = d;
            }
        }
        static int penalty(boolean[][] m) {
            int size = m.length, p = 0;
            for (int pass = 0; pass < 2; pass++) for (int i = 0; i < size; i++) {
                int run = 1;
                for (int j = 1; j < size; j++) {
                    boolean a = pass == 0 ? m[i][j] : m[j][i], b = pass == 0 ? m[i][j - 1] : m[j - 1][i];
                    if (a == b) run++; else { if (run >= 5) p += run - 2; run = 1; }
                }
                if (run >= 5) p += run - 2;
            }
            for (int r = 0; r < size - 1; r++) for (int c = 0; c < size - 1; c++)
                if (m[r][c] == m[r][c + 1] && m[r][c] == m[r + 1][c] && m[r][c] == m[r + 1][c + 1]) p += 3;
            boolean[] pat = {true, false, true, true, true, false, true, false, false, false, false}, rev = {false, false, false, false, true, false, true, true, true, false, true};
            for (int i = 0; i < size; i++) for (int j = 0; j + 11 <= size; j++) {
                boolean rowA = true, rowB = true, colA = true, colB = true;
                for (int k = 0; k < 11; k++) {
                    if (m[i][j + k] != pat[k]) rowA = false;
                    if (m[i][j + k] != rev[k]) rowB = false;
                    if (m[j + k][i] != pat[k]) colA = false;
                    if (m[j + k][i] != rev[k]) colB = false;
                }
                p += 40 * ((rowA ? 1 : 0) + (rowB ? 1 : 0) + (colA ? 1 : 0) + (colB ? 1 : 0));
            }
            int dark = 0; for (boolean[] row : m) for (boolean c : row) if (c) dark++;
            p += Math.abs(dark * 20 - size * size * 10) / (size * size) * 10;
            return p;
        }
    }

    // ---- small helpers ----

    static void daemon(Runnable r) { Thread t = new Thread(r); t.setDaemon(true); t.start(); }
    static void closeAll(List<Closeable> l) { for (Closeable c : l) try { c.close(); } catch (IOException e) { /* gone */ } }
    static String text(DatagramPacket d) { return new String(d.getData(), 0, d.getLength(), StandardCharsets.UTF_8); }
    static void reply(DatagramSocket udp, DatagramPacket to, String m) throws IOException {
        byte[] r = m.getBytes(StandardCharsets.UTF_8); udp.send(new DatagramPacket(r, r.length, to.getSocketAddress()));
    }
    static InetSocketAddress hostPort(String t) { int i = t.lastIndexOf(':'); return new InetSocketAddress(t.substring(0, i), Integer.parseInt(t.substring(i + 1))); }
    static long mtime(Path p) { try { return p == null ? 0 : Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0; } }
    static byte[] nonce(long n) { byte[] b = new byte[12]; for (int i = 0; i < 8; i++) b[11 - i] = (byte) (n >>> (8 * i)); return b; }
    static void writeBlock(DataOutputStream out, byte[] b) throws IOException { out.writeInt(b.length); out.write(b); }
    static byte[] readBlock(DataInputStream in, int max) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > max) throw new IOException("a message of the wrong size");
        byte[] b = new byte[n]; in.readFully(b); return b;
    }
    static byte[] fixed(BigInteger v) {
        byte[] r = new byte[256], b = v.toByteArray();
        int len = Math.min(b.length, 256);
        System.arraycopy(b, b.length - len, r, 256 - len, len);
        return r;
    }
    static byte[] sha256(byte[] b) { try { return MessageDigest.getInstance("SHA-256").digest(b); } catch (Exception e) { throw new RuntimeException(e); } }
    static byte[] expand(byte[] seed, int n) {
        byte[] r = new byte[n];
        for (int i = 0, off = 0; off < n; i++, off += 32) {
            byte[] h = sha256(concat(seed, new byte[] { (byte) i }));
            System.arraycopy(h, 0, r, off, Math.min(32, n - off));
        }
        return r;
    }
    static byte[] hmac(byte[] key, String label) throws Exception { return hmac(key, label.getBytes(StandardCharsets.UTF_8)); }
    static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(key, "HmacSHA256")); return m.doFinal(data);
    }
    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) { o.write(p.length >>> 8); o.write(p.length); o.writeBytes(p); }
        return o.toByteArray();
    }
    static String hexOf(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }
    static byte[] hex(String s) { byte[] b = new byte[s.length() / 2]; for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16); return b; }
    /** "7 Violet  orbit", or a hatchlybox-nightly://sync/ link → "7-violet-orbit" (a link's ?at= becomes --to). */
    static String normal(String code) {
        String c = code.trim();
        if (c.startsWith("hatchlybox-nightly://sync/")) {
            c = c.substring("hatchlybox-nightly://sync/".length());
            int q = c.indexOf("?at=");
            if (q >= 0) { if (!opts.containsKey("to")) opts.put("to", c.substring(q + 4)); c = c.substring(0, q); }
        }
        return c.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "-");
    }

    static List<String> addresses() {
        List<String> r = new ArrayList<>();
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InterfaceAddress ia : n.getInterfaceAddresses()) if (ia.getAddress() instanceof Inet4Address) r.add(ia.getAddress().getHostAddress());
            }
        } catch (SocketException e) { /* none */ }
        return r;
    }

    /** The value of "key" in a small JSON text (the first one: the device's, at the top of a manifest), or null. */
    static String jsonString(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : null;
    }

    static synchronized void done(String state, String device, String result, String theirs) {
        if (human) System.out.println("Synced with " + device + ". Here: " + result);
        else System.out.println("{\"state\":\"" + state + "\",\"device\":" + json(device) + ",\"result\":" + result + ",\"theirs\":" + theirs + "}");
        System.out.flush();
    }

    @SuppressWarnings("unchecked")
    static synchronized void status(String state, Object... kv) {
        if (human) {
            Map<String, Object> m = new HashMap<>();
            for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
            switch (state) {
                case "waiting" -> {
                    System.out.println("\nOn the other device, type this code (or scan it with its camera):\n\n    " + String.valueOf(m.get("code")).replace('-', ' ') + "\n");
                    List<String> q = (List<String>) m.get("qr");
                    for (int r = -2; r < q.size() + 2; r += 2) {
                        StringBuilder s = new StringBuilder("    ");
                        // Light modules drawn (a dark terminal's background is the dark), with a quiet zone of 2.
                        for (int c = -2; c < q.size() + 2; c++) {
                            boolean top = dark(q, r, c), bottom = dark(q, r + 1, c);
                            s.append(!top && !bottom ? '█' : !top ? '▀' : !bottom ? '▄' : ' ');
                        }
                        System.out.println(s);
                    }
                    System.out.println("\nWaiting for it (10 minutes)…");
                }
                case "connected" -> System.out.println("Connected to " + m.get("device") + ".");
                case "failed", "note", "error" -> System.out.println(m.get("msg"));
                case "serving" -> System.out.println("Syncing by itself with " + ((List<?>) m.get("devices")).size() + " paired device(s). Ctrl+C stops.");
                default -> { }
            }
        } else {
            StringBuilder b = new StringBuilder("{\"state\":\"").append(state).append('"');
            for (int i = 0; i + 1 < kv.length; i += 2) b.append(",\"").append(kv[i]).append("\":").append(json(kv[i + 1]));
            System.out.println(b.append('}'));
        }
        System.out.flush();
    }
    static boolean dark(List<String> q, int r, int c) { return r >= 0 && c >= 0 && r < q.size() && c < q.size() && q.get(r).charAt(c) == '1'; }
    static String json(Object v) {
        if (v instanceof Number) return v.toString();
        if (v instanceof List<?> l) { StringJoiner j = new StringJoiner(",", "[", "]"); for (Object o : l) j.add(json(o)); return j.toString(); }
        String s = String.valueOf(v);
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
}
