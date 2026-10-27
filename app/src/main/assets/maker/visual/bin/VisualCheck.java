// visual check / visual render's contact sheet: measures screenshots so an agent can judge them with numbers
// as well as its eyes (no image libraries needed: plain Java).
//   java VisualCheck.java check <png>... [--style pixel|flat|painted|realistic|ui] [--against old.png]
//   java VisualCheck.java sheet <out.png> <png>...      the images side by side, small enough to look at
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

public class VisualCheck {
    // VISUAL_JSON=<file>: the measurements as JSON too (Excellence Lab scores them).
    static StringBuilder json = new StringBuilder();

    public static void main(String[] a) throws Exception {
        System.setProperty("java.awt.headless", "true");
        if (a.length < 2) { System.err.println("usage: check <png>... | sheet <out.png> <png>..."); System.exit(2); }
        if (a[0].equals("sheet")) { sheet(a[1], Arrays.copyOfRange(a, 2, a.length)); return; }
        String style = "", against = null;
        List<String> files = new ArrayList<>();
        for (int i = 1; i < a.length; i++) {
            if (a[i].equals("--style")) style = a[++i];
            else if (a[i].equals("--against")) against = a[++i];
            else files.add(a[i]);
        }
        int warnings = 0;
        for (String f : files) warnings += check(f, style, against);
        String jf = System.getenv("VISUAL_JSON");
        if (jf != null) java.nio.file.Files.writeString(java.nio.file.Path.of(jf), "[" + json + "]\n");
        if (files.size() > 1) sheet(new File(files.get(0)).getParent() + "/sheet.png", files.toArray(new String[0]));
        System.out.println(warnings == 0 ? "no warnings: now look at the image itself (sheet.png) and judge it against design/visual-plan.md"
            : warnings + " warning(s): fix them or say in design/visual-plan.md why they're intended");
    }

    static double luma(int rgb) { return 0.2126 * ((rgb >> 16) & 255) + 0.7152 * ((rgb >> 8) & 255) + 0.0722 * (rgb & 255); }

    static int check(String file, String style, String against) throws Exception {
        BufferedImage img = ImageIO.read(new File(file));
        if (img == null) { System.out.println(file + ": not an image"); return 1; }
        int w = img.getWidth(), h = img.getHeight(), n = w * h;
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        List<String> warn = new ArrayList<>();
        // Brightness and contrast.
        int[] hist = new int[256];
        double sum = 0, sum2 = 0, rg = 0, yb = 0, rg2 = 0, yb2 = 0;
        int black = 0, white = 0;
        Map<Integer, Integer> exact = new HashMap<>();
        int[] bins = new int[4096];
        for (int p : px) {
            double l = luma(p); hist[(int) l]++; sum += l; sum2 += l * l;
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            if (r < 8 && g < 8 && b < 8) black++;
            if (r > 247 && g > 247 && b > 247) white++;
            double x = r - g, y = 0.5 * (r + g) - b; rg += x; yb += y; rg2 += x * x; yb2 += y * y;
            if (exact.size() <= 100000) exact.merge(p & 0xFFFFFF, 1, Integer::sum);
            bins[((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4)]++;
        }
        double mean = sum / n, sd = Math.sqrt(Math.max(0, sum2 / n - mean * mean));
        int p2 = pct(hist, n, 0.02), p98 = pct(hist, n, 0.98);
        double mrg = rg / n, myb = yb / n;
        double colorful = Math.sqrt(Math.max(0, rg2 / n - mrg * mrg) + Math.max(0, yb2 / n - myb * myb)) + 0.3 * Math.sqrt(mrg * mrg + myb * myb);
        // Detail: how much of the image has edges, and where (a 3x3 grid).
        double[] grid = new double[9];
        int edges = 0;
        for (int y = 1; y < h - 1; y++) for (int x = 1; x < w - 1; x++) {
            double gx = luma(px[y * w + x + 1]) - luma(px[y * w + x - 1]), gy = luma(px[(y + 1) * w + x]) - luma(px[(y - 1) * w + x]);
            if (gx * gx + gy * gy > 400) { edges++; grid[Math.min(2, y * 3 / h) * 3 + Math.min(2, x * 3 / w)]++; }
        }
        double edgeShare = (double) edges / n;
        // Palette: the main colours, by share of the image.
        Integer[] order = new Integer[4096];
        for (int i = 0; i < 4096; i++) order[i] = i;
        Arrays.sort(order, (x, y) -> bins[y] - bins[x]);
        StringBuilder pal = new StringBuilder();
        int covered = 0, mainColours = 0;
        for (int i = 0; i < 4096 && bins[order[i]] > 0; i++) {
            if (bins[order[i]] * 200 < n) break;
            mainColours++; covered += bins[order[i]];
            if (i < 8) {
                int c = order[i];
                pal.append(String.format(" #%02x%02x%02x %d%%", ((c >> 8) & 15) * 17, ((c >> 4) & 15) * 17, (c & 15) * 17, Math.round(100.0 * bins[c] / n)));
            }
        }
        // Empty bands at the edges (letterboxing), and busy top/bottom strips (status and gesture bars).
        int bandTop = uniformRows(px, w, h, true), bandBottom = uniformRows(px, w, h, false);
        double topBusy = stripEdges(px, w, h, 0, Math.max(1, h * 4 / 100)), bottomBusy = stripEdges(px, w, h, h - Math.max(1, h * 3 / 100), h);

        System.out.println("== " + file + " (" + w + "x" + h + ")");
        System.out.printf("  brightness %.0f/255, contrast (sd) %.0f, range p2..p98 %d..%d, black %.1f%%, white %.1f%%%n", mean, sd, p2, p98, 100.0 * black / n, 100.0 * white / n);
        System.out.printf("  colourfulness %.0f (0 grey, 15 muted, 33 moderate, 59 quite, 82+ very colourful)%n", colorful);
        System.out.printf("  colours: %s exact, %d main bins cover %.0f%%; top:%s%n", exact.size() > 100000 ? ">100k" : String.valueOf(exact.size()), mainColours, 100.0 * covered / n, pal);
        System.out.printf("  detail (edges) %.1f%% of pixels; by thirds (rows top→bottom):%s%n", 100 * edgeShare, gridText(grid, edges));

        if (sd < 6 && edgeShare < 0.002) warn.add("almost blank: a solid or nearly solid frame (scene not loaded, camera off, or everything hidden?)");
        if (p98 - p2 < 60 && sd >= 6) warn.add("low contrast (p2..p98 under 60): things will be hard to tell apart on a phone outdoors");
        if (black * 100L > n * 60L) warn.add("mostly black: missing lighting, a CanvasModulate too dark, or nothing drawn");
        if (white * 100L > n * 50L) warn.add("mostly white: overexposed (glow/tonemap too strong?) or an empty UI");
        // Letterboxing: flat bars of the same colour at both ends (a plain sky over the ground isn't).
        if (bandTop > h / 20 && bandBottom > h / 20 && Math.abs(luma(px[0]) - luma(px[(h - 1) * w])) < 4) warn.add("empty bands at the top/bottom (" + bandTop + "/" + bandBottom + " px): set window/stretch/aspect=\"expand\" and fill the extra space");
        if (topBusy > 0.08) warn.add("busy detail in the top 4%: HUD or text may sit under the status bar or notch (use the display safe area)");
        if (bottomBusy > 0.08) warn.add("busy detail in the bottom 3%: controls may sit under the gesture bar");
        if (edgeShare > 0.30) warn.add("very busy image (edges on " + Math.round(100 * edgeShare) + "% of pixels): the eye has nowhere to rest; simplify backgrounds or lower their contrast");
        if (edgeShare < 0.01 && sd >= 6) warn.add("very little detail: flat and empty-looking; add shape variety, texture or props");
        double centre = grid[4] / Math.max(1, edges), maxCell = Arrays.stream(grid).max().orElse(0) / Math.max(1, edges);
        if (edges > 0 && maxCell < 0.16) warn.add("detail spread evenly everywhere: no focal point; lead the eye with contrast, light or colour");
        switch (style) {
            case "pixel" -> {
                if (exact.size() > 256) warn.add("pixel style but " + (exact.size() > 100000 ? ">100k" : exact.size()) + " exact colours: filtering or scaling is blurring the pixels (texture_filter = nearest, integer scale, no MSAA)");
                double soft = softPixels(px, w, h);
                if (soft > 0.25) warn.add(String.format("pixel style but %.0f%% of edges are soft: textures are filtered or scaled by a non-integer factor", soft * 100));
            }
            case "flat" -> { if (mainColours > 40) warn.add("flat style but " + mainColours + " main colour bins: gradients or noise creep in; keep to a tight palette"); }
            case "ui" -> { if (p98 - p2 < 120) warn.add("UI with a narrow tonal range: text contrast may fail WCAG 4.5:1"); }
            case "painted", "realistic" -> { if (colorful < 15) warn.add("muted/grey for a " + style + " look: add colour variation in light and shadow"); }
            default -> {}
        }
        if (against != null) {
            BufferedImage o = ImageIO.read(new File(against));
            if (o != null && o.getWidth() == w && o.getHeight() == h) {
                int[] q = o.getRGB(0, 0, w, h, null, 0, w);
                BufferedImage d = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                long changed = 0; double diff = 0;
                for (int i = 0; i < n; i++) {
                    double dl = Math.abs(luma(px[i]) - luma(q[i]));
                    int dc = Math.abs(((px[i] >> 16) & 255) - ((q[i] >> 16) & 255)) + Math.abs(((px[i] >> 8) & 255) - ((q[i] >> 8) & 255)) + Math.abs((px[i] & 255) - (q[i] & 255));
                    diff += dc / 3.0;
                    if (dc > 24) changed++;
                    int v = (int) Math.min(255, dl * 3); d.setRGB(i % w, i / w, (v << 16) | (v / 3 << 8));
                }
                String out = file.replaceAll("\\.png$", "") + ".diff.png";
                ImageIO.write(d, "png", new File(out));
                System.out.printf("  against %s: %.1f%% of pixels changed, mean difference %.1f/255 (heat map: %s)%n", against, 100.0 * changed / n, diff / n, out);
            } else System.out.println("  against " + against + ": not comparable (missing or another size)");
        }
        for (String s : warn) System.out.println("  WARNING " + s);
        if (json.length() > 0) json.append(",");
        StringBuilder ws = new StringBuilder();
        for (String s : warn) ws.append(ws.length() > 0 ? "," : "").append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        json.append(String.format(Locale.ROOT,
            "{\"file\":\"%s\",\"w\":%d,\"h\":%d,\"brightness\":%.1f,\"contrast\":%.1f,\"range\":%d,\"colourfulness\":%.1f,\"exact_colours\":%d,\"main_colours\":%d,\"detail\":%.4f,\"focus\":%.3f,\"warnings\":[%s]}",
            new File(file).getName(), w, h, mean, sd, p98 - p2, colorful, exact.size(), mainColours, edgeShare, maxCell, ws));
        return warn.size();
    }

    static int pct(int[] hist, int n, double p) { long c = 0; for (int i = 0; i < 256; i++) { c += hist[i]; if (c >= n * p) return i; } return 255; }

    static String gridText(double[] g, int total) {
        StringBuilder s = new StringBuilder();
        for (int r = 0; r < 3; r++) {
            s.append(" [");
            for (int c = 0; c < 3; c++) s.append(String.format("%s%2d", c > 0 ? " " : "", Math.round(100 * g[r * 3 + c] / Math.max(1, total))));
            s.append("]");
        }
        return s.toString() + " %";
    }

    static int uniformRows(int[] px, int w, int h, boolean top) {
        // Rows of one and the same colour (a vertical gradient isn't a band).
        int rows = 0, edge = px[top ? 0 : (h - 1) * w];
        for (int k = 0; k < h; k++) {
            int y = top ? k : h - 1 - k;
            for (int x = 0; x < w; x++) if (Math.abs(luma(px[y * w + x]) - luma(edge)) > 3) return rows;
            rows++;
        }
        return rows;
    }

    static double stripEdges(int[] px, int w, int h, int y0, int y1) {
        int e = 0, c = 0;
        for (int y = Math.max(1, y0); y < Math.min(h - 1, y1); y++) for (int x = 1; x < w - 1; x++) {
            c++;
            if (Math.abs(luma(px[y * w + x + 1]) - luma(px[y * w + x - 1])) > 40) e++;
        }
        return c == 0 ? 0 : (double) e / c;
    }

    // Share of colour changes that go through an in-between colour (a blurred edge) rather than a hard step.
    static double softPixels(int[] px, int w, int h) {
        long hard = 0, soft = 0;
        for (int y = 0; y < h; y++) for (int x = 1; x < w - 1; x++) {
            int a = px[y * w + x - 1], b = px[y * w + x], c = px[y * w + x + 1];
            double la = luma(a), lb = luma(b), lc = luma(c);
            if (Math.abs(la - lc) < 40) continue;
            if (b == a || b == c) hard++;
            else if ((lb - la) * (lc - lb) > 0) soft++;
        }
        return hard + soft == 0 ? 0 : (double) soft / (hard + soft);
    }

    static void sheet(String out, String[] files) throws Exception {
        List<BufferedImage> imgs = new ArrayList<>();
        for (String f : files) { BufferedImage i = ImageIO.read(new File(f)); if (i != null) imgs.add(i); }
        if (imgs.isEmpty()) return;
        int cols = Math.min(4, imgs.size()), rows = (imgs.size() + cols - 1) / cols;
        int tw = Math.min(240, 960 / cols), th = 0;
        for (BufferedImage i : imgs) th = Math.max(th, i.getHeight() * tw / i.getWidth());
        BufferedImage s = new BufferedImage(cols * (tw + 8) + 8, rows * (th + 26) + 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = s.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x202024)); g.fillRect(0, 0, s.getWidth(), s.getHeight());
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        for (int k = 0; k < imgs.size(); k++) {
            BufferedImage i = imgs.get(k);
            int x = 8 + (k % cols) * (tw + 8), y = 8 + (k / cols) * (th + 26), ih = i.getHeight() * tw / i.getWidth();
            g.drawImage(thumb(i, tw, ih), x, y, null);
            g.setColor(new Color(0xD0D0D8)); g.drawString(new File(files[k]).getName(), x, y + th + 17);
        }
        g.dispose();
        ImageIO.write(s, "png", new File(out));
    }

    // Scaled down in halving steps, so the thumbnail stays sharp.
    static BufferedImage thumb(BufferedImage src, int w, int h) {
        BufferedImage cur = src;
        while (cur.getWidth() / 2 >= w) {
            BufferedImage half = new BufferedImage(cur.getWidth() / 2, cur.getHeight() / 2, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = half.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(cur, 0, 0, half.getWidth(), half.getHeight(), null); g.dispose();
            cur = half;
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(cur, 0, 0, w, h, null); g.dispose();
        return out;
    }
}
