// Store graphics for Google Play, drawn from an app's own launcher icon (no image tools needed):
//   java PlayArt.java icon <res dir> <out.png>                  512x512 icon
//   (<res dir> can be a square PNG icon instead, such as a Godot game's, for icon and feature)
//   java PlayArt.java feature <res dir> <title> <out.png>       1024x500 feature graphic
//   java PlayArt.java shot <in.png> <out.png>                   screenshot cropped to Play's 2:1 limit
//   java PlayArt.java thumb <in.png> <out.png> <width>         a screenshot scaled down for an agent to look at
// The icon is the adaptive icon's background color or vector, plus its foreground vector, cropped to
// the 72dp a launcher shows. Vectors support paths, groups, clip-paths and solid or gradient colors.
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

public class PlayArt {
    static final String A = "http://schemas.android.com/apk/res/android";
    static Path res;
    static Map<String, String> colors = new HashMap<>();
    static BufferedImage source;

    public static void main(String[] a) throws Exception {
        System.setProperty("java.awt.headless", "true");
        switch (a[0]) {
            case "icon" -> { load(a[1]); ImageIO.write(icon(512, false), "png", new File(a[2])); }
            case "feature" -> { load(a[1]); ImageIO.write(feature(a[2]), "png", new File(a[3])); }
            case "shot" -> ImageIO.write(shot(ImageIO.read(new File(a[1]))), "png", new File(a[2]));
            case "thumb" -> ImageIO.write(thumb(ImageIO.read(new File(a[1])), Integer.parseInt(a[3])), "png", new File(a[2]));
            default -> throw new IllegalArgumentException(a[0]);
        }
    }

    static void load(String dir) throws Exception {
        res = Paths.get(dir);
        if (dir.toLowerCase(Locale.ROOT).endsWith(".png")) { source = ImageIO.read(res.toFile()); return; }
        try (var s = Files.newDirectoryStream(res, "values*")) {
            for (Path v : s) {
                if (!v.getFileName().toString().equals("values")) continue;
                try (var f = Files.newDirectoryStream(v, "*.xml")) {
                    for (Path x : f) {
                        NodeList l = xml(x).getElementsByTagName("color");
                        for (int i = 0; i < l.getLength(); i++) {
                            Element e = (Element) l.item(i);
                            colors.put(e.getAttribute("name"), e.getTextContent().trim());
                        }
                    }
                }
            }
        }
    }

    static Document xml(Path p) throws Exception {
        var f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(p.toFile());
    }

    // ---- icon ----

    // The adaptive icon's layers, or null for a layer it doesn't have.
    static String[] layers() throws Exception {
        for (String d : new String[]{"mipmap-anydpi-v26", "mipmap-anydpi"}) {
            Path p = res.resolve(d).resolve("ic_launcher.xml");
            if (!Files.exists(p)) continue;
            Element root = xml(p).getDocumentElement();
            String bg = null, fg = null;
            for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element e)) continue;
                String v = e.getAttributeNS(A, "drawable");
                if (e.getLocalName().equals("background")) bg = v;
                if (e.getLocalName().equals("foreground")) fg = v;
            }
            return new String[]{bg, fg};
        }
        return new String[]{"#FFFFFF", "@drawable/ic_fg"};
    }

    static BufferedImage icon(int size, boolean transparentBg) throws Exception {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = gfx(img);
        if (source != null) { g.drawImage(source, 0, 0, size, size, null); g.dispose(); return img; }
        String[] l = layers();
        // 108dp layers; a launcher shows the central 72dp.
        double s = size / 72.0;
        g.scale(s, s);
        g.translate(-18, -18);
        if (!transparentBg) layer(g, l[0]);
        layer(g, l[1]);
        g.dispose();
        return img;
    }

    static void layer(Graphics2D g, String ref) throws Exception {
        if (ref == null || ref.isEmpty()) return;
        if (ref.startsWith("@drawable/") || ref.startsWith("@mipmap/")) {
            String name = ref.substring(ref.indexOf('/') + 1);
            for (String d : new String[]{"drawable", "drawable-v24", "drawable-anydpi", "mipmap-anydpi-v26"}) {
                Path p = res.resolve(d).resolve(name + ".xml");
                if (Files.exists(p)) { vector(g, xml(p).getDocumentElement(), 108); return; }
            }
            return;
        }
        Color c = color(ref);
        if (c != null) { g.setColor(c); g.fill(new Rectangle2D.Double(0, 0, 108, 108)); }
    }

    // Draws a <vector> scaled to fill a box of `box` units.
    static void vector(Graphics2D g, Element v, double box) {
        if (!v.getLocalName().equals("vector")) return;
        double vw = num(v.getAttributeNS(A, "viewportWidth"), 108), vh = num(v.getAttributeNS(A, "viewportHeight"), 108);
        Graphics2D c = (Graphics2D) g.create();
        c.scale(box / vw, box / vh);
        children(c, v);
        c.dispose();
    }

    static void children(Graphics2D g, Element parent) {
        Shape clip = null;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element e)) continue;
            switch (e.getLocalName()) {
                case "clip-path" -> {
                    Shape s = PathParser.parse(e.getAttributeNS(A, "pathData"));
                    clip = clip == null ? s : intersect(clip, s);
                    g.clip(s);
                }
                case "group" -> {
                    Graphics2D c = (Graphics2D) g.create();
                    double px = num(e.getAttributeNS(A, "pivotX"), 0), py = num(e.getAttributeNS(A, "pivotY"), 0);
                    c.translate(num(e.getAttributeNS(A, "translateX"), 0) + px, num(e.getAttributeNS(A, "translateY"), 0) + py);
                    c.rotate(Math.toRadians(num(e.getAttributeNS(A, "rotation"), 0)));
                    c.scale(num(e.getAttributeNS(A, "scaleX"), 1), num(e.getAttributeNS(A, "scaleY"), 1));
                    c.translate(-px, -py);
                    children(c, e);
                    c.dispose();
                }
                case "path" -> path(g, e);
            }
        }
    }

    static Shape intersect(Shape a, Shape b) {
        Area r = new Area(a);
        r.intersect(new Area(b));
        return r;
    }

    static void path(Graphics2D g, Element e) {
        Path2D p = PathParser.parse(e.getAttributeNS(A, "pathData"));
        if ("evenOdd".equals(e.getAttributeNS(A, "fillType"))) p.setWindingRule(Path2D.WIND_EVEN_ODD);
        Paint fill = paint(e, "fillColor", p);
        if (fill != null) {
            Graphics2D c = (Graphics2D) g.create();
            c.setComposite(AlphaComposite.SrcOver.derive((float) num(e.getAttributeNS(A, "fillAlpha"), 1)));
            c.setPaint(fill);
            c.fill(p);
            c.dispose();
        }
        Paint stroke = paint(e, "strokeColor", p);
        double w = num(e.getAttributeNS(A, "strokeWidth"), 0);
        if (stroke != null && w > 0) {
            Graphics2D c = (Graphics2D) g.create();
            c.setComposite(AlphaComposite.SrcOver.derive((float) num(e.getAttributeNS(A, "strokeAlpha"), 1)));
            int cap = switch (e.getAttributeNS(A, "strokeLineCap")) { case "round" -> BasicStroke.CAP_ROUND; case "square" -> BasicStroke.CAP_SQUARE; default -> BasicStroke.CAP_BUTT; };
            int join = switch (e.getAttributeNS(A, "strokeLineJoin")) { case "round" -> BasicStroke.JOIN_ROUND; case "bevel" -> BasicStroke.JOIN_BEVEL; default -> BasicStroke.JOIN_MITER; };
            c.setStroke(new BasicStroke((float) w, cap, join, (float) Math.max(1, num(e.getAttributeNS(A, "strokeMiterLimit"), 4))));
            c.setPaint(stroke);
            c.draw(p);
            c.dispose();
        }
    }

    // A solid color attribute, or an <aapt:attr name="android:fillColor"><gradient/></aapt:attr> child.
    static Paint paint(Element e, String attr, Shape shape) {
        String v = e.getAttributeNS(A, attr);
        if (!v.isEmpty()) return color(v);
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element a && a.getLocalName().equals("attr") && a.getAttribute("name").equals("android:" + attr)) {
                for (Node m = a.getFirstChild(); m != null; m = m.getNextSibling()) {
                    if (m instanceof Element gr && gr.getLocalName().equals("gradient")) return gradient(gr, shape.getBounds2D());
                }
            }
        }
        return null;
    }

    static Paint gradient(Element gr, Rectangle2D b) {
        List<Float> stops = new ArrayList<>();
        List<Color> cs = new ArrayList<>();
        for (Node n = gr.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element it && it.getLocalName().equals("item")) {
                Color c = color(it.getAttributeNS(A, "color"));
                if (c == null) continue;
                stops.add((float) num(it.getAttributeNS(A, "offset"), stops.isEmpty() ? 0 : 1));
                cs.add(c);
            }
        }
        if (cs.isEmpty()) {
            for (String k : new String[]{"startColor", "centerColor", "endColor"}) {
                Color c = color(gr.getAttributeNS(A, k));
                if (c != null) cs.add(c);
            }
            for (int i = 0; i < cs.size(); i++) stops.add(cs.size() == 1 ? 0f : (float) i / (cs.size() - 1));
        }
        if (cs.isEmpty()) return null;
        if (cs.size() == 1) return cs.get(0);
        float[] f = new float[stops.size()];
        for (int i = 0; i < f.length; i++) f[i] = Math.min(1, Math.max(i == 0 ? 0 : f[i - 1] + 1e-4f, stops.get(i)));
        Color[] c = cs.toArray(new Color[0]);
        if ("radial".equals(gr.getAttributeNS(A, "type"))) {
            float cx = (float) num(gr.getAttributeNS(A, "centerX"), b.getCenterX()), cy = (float) num(gr.getAttributeNS(A, "centerY"), b.getCenterY());
            float r = (float) Math.max(0.01, num(gr.getAttributeNS(A, "gradientRadius"), b.getWidth() / 2));
            return new RadialGradientPaint(cx, cy, r, f, c);
        }
        Point2D s = new Point2D.Double(num(gr.getAttributeNS(A, "startX"), b.getMinX()), num(gr.getAttributeNS(A, "startY"), b.getMinY()));
        Point2D t = new Point2D.Double(num(gr.getAttributeNS(A, "endX"), b.getMaxX()), num(gr.getAttributeNS(A, "endY"), b.getMaxY()));
        if (s.equals(t)) return c[0];
        return new LinearGradientPaint(s, t, f, c);
    }

    static Color color(String v) {
        if (v == null || v.isEmpty()) return null;
        for (int i = 0; i < 5 && v.startsWith("@color/"); i++) v = colors.getOrDefault(v.substring(7), "");
        switch (v) {
            case "@android:color/white": return Color.WHITE;
            case "@android:color/black": return Color.BLACK;
            case "@android:color/transparent": return new Color(0, 0, 0, 0);
        }
        if (!v.startsWith("#")) return null;
        String h = v.substring(1);
        if (h.length() == 3 || h.length() == 4) {
            StringBuilder b = new StringBuilder();
            for (char ch : h.toCharArray()) b.append(ch).append(ch);
            h = b.toString();
        }
        try {
            long x = Long.parseLong(h, 16);
            if (h.length() == 6) return new Color((int) x);
            if (h.length() == 8) return new Color((int) (x >> 16) & 255, (int) (x >> 8) & 255, (int) x & 255, (int) (x >> 24) & 255);
        } catch (NumberFormatException ignored) { }
        return null;
    }

    static double num(String s, double d) {
        if (s == null || s.isEmpty()) return d;
        try { return Double.parseDouble(s.replaceAll("(dp|px|sp)$", "")); } catch (NumberFormatException e) { return d; }
    }

    static Graphics2D gfx(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        return g;
    }

    // ---- feature graphic: the icon on its own color, the name beside it ----

    static BufferedImage feature(String title) throws Exception {
        BufferedImage img = new BufferedImage(1024, 500, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = gfx(img);
        BufferedImage ic = icon(300, false);
        // The background is the icon's own edge color, so the icon blends in.
        Color bg = new Color(ic.getRGB(2, 150));
        g.setPaint(new GradientPaint(0, 0, bg, 1024, 500, shade(bg, luma(bg) > 0.5 ? -0.12 : 0.18)));
        g.fillRect(0, 0, 1024, 500);
        Shape r = new RoundRectangle2D.Double(90, 100, 300, 300, 80, 80);
        g.setColor(new Color(0, 0, 0, 40));
        g.fill(AffineTransform.getTranslateInstance(0, 8).createTransformedShape(r));
        Graphics2D c = (Graphics2D) g.create();
        c.clip(r);
        c.drawImage(ic, 90, 100, null);
        c.dispose();
        g.setColor(luma(bg) > 0.55 ? new Color(0x1C1B1F) : Color.WHITE);
        // The largest size at which the name fits in two lines beside the icon.
        int size = 96;
        List<String> lines;
        while (true) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size));
            lines = wrap(g.getFontMetrics(), title, 540);
            if ((lines.size() <= 2 && lines.stream().allMatch(l -> g.getFontMetrics().stringWidth(l) <= 540)) || size <= 36) break;
            size -= 4;
        }
        FontMetrics fm = g.getFontMetrics();
        int lh = fm.getHeight(), y = 250 - lines.size() * lh / 2 + fm.getAscent();
        for (String l : lines) { g.drawString(l, 450, y); y += lh; }
        g.dispose();
        return img;
    }

    static List<String> wrap(FontMetrics fm, String text, int width) {
        List<String> out = new ArrayList<>();
        String line = "";
        for (String w : text.trim().split("\\s+")) {
            String t = line.isEmpty() ? w : line + " " + w;
            if (fm.stringWidth(t) > width && !line.isEmpty()) { out.add(line); line = w; } else line = t;
        }
        if (!line.isEmpty()) out.add(line);
        return out;
    }

    static double luma(Color c) { return (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255; }

    static Color shade(Color c, double f) {
        int[] v = {c.getRed(), c.getGreen(), c.getBlue()};
        for (int i = 0; i < 3; i++) v[i] = (int) Math.max(0, Math.min(255, f < 0 ? v[i] * (1 + f) : v[i] + (255 - v[i]) * f));
        return new Color(v[0], v[1], v[2]);
    }

    // ---- screenshots: Play rejects a side longer than twice the other, so trim the bars ----

    static BufferedImage thumb(BufferedImage in, int w) {
        if (in.getWidth() <= w) return in;
        int h = (int) Math.round(in.getHeight() * (double) w / in.getWidth());
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(in, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    static BufferedImage shot(BufferedImage in) {
        int w = in.getWidth(), h = in.getHeight();
        int nh = Math.min(h, 2 * w), nw = Math.min(w, 2 * h);
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        // Keep the top (the app's title bar) and cut from the bottom (the navigation bar) first.
        int cut = h - nh, top = Math.min(cut / 3, cut);
        g.drawImage(in, 0, 0, nw, nh, (w - nw) / 2, top, (w - nw) / 2 + nw, top + nh, null);
        g.dispose();
        return out;
    }

    // ---- SVG path data, as Android's pathData uses it ----

    static class PathParser {
        final String s;
        int i;

        PathParser(String s) { this.s = s; }

        static Path2D parse(String d) {
            Path2D.Double p = new Path2D.Double();
            if (d == null || d.isEmpty()) return p;
            new PathParser(d).run(p);
            return p;
        }

        void skip() { while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++; }

        boolean more() {
            skip();
            if (i >= s.length()) return false;
            char c = s.charAt(i);
            return c == '-' || c == '+' || c == '.' || Character.isDigit(c);
        }

        double n() {
            skip();
            int st = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            boolean dot = false, exp = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isDigit(c)) i++;
                else if (c == '.' && !dot && !exp) { dot = true; i++; }
                else if ((c == 'e' || c == 'E') && !exp) { exp = true; i++; if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++; }
                else break;
            }
            return Double.parseDouble(s.substring(st, i));
        }

        // Arc flags may be written without separators ("a4,4 0 011,1").
        boolean flag() { skip(); return s.charAt(i++) == '1'; }

        void run(Path2D.Double p) {
            double x = 0, y = 0, sx = 0, sy = 0, cx = 0, cy = 0;
            char cmd = 0, prev = 0;
            while (true) {
                skip();
                if (i >= s.length()) break;
                char c = s.charAt(i);
                if (Character.isLetter(c) && c != 'e' && c != 'E') { cmd = c; i++; }
                else if (cmd == 0) { i++; continue; }
                boolean rel = Character.isLowerCase(cmd);
                double ox = rel ? x : 0, oy = rel ? y : 0;
                switch (Character.toUpperCase(cmd)) {
                    case 'M' -> {
                        x = ox + n(); y = oy + n(); p.moveTo(x, y); sx = x; sy = y;
                        // Further pairs after a moveto are linetos.
                        cmd = rel ? 'l' : 'L';
                    }
                    case 'L' -> { x = ox + n(); y = oy + n(); p.lineTo(x, y); }
                    case 'H' -> { x = ox + n(); p.lineTo(x, y); }
                    case 'V' -> { y = (rel ? y : 0) + n(); p.lineTo(x, y); }
                    case 'C' -> {
                        double x1 = ox + n(), y1 = oy + n(), x2 = ox + n(), y2 = oy + n();
                        x = ox + n(); y = oy + n(); p.curveTo(x1, y1, x2, y2, x, y); cx = x2; cy = y2;
                    }
                    case 'S' -> {
                        boolean cu = "CcSs".indexOf(prev) >= 0;
                        double x1 = cu ? 2 * x - cx : x, y1 = cu ? 2 * y - cy : y, x2 = ox + n(), y2 = oy + n();
                        x = ox + n(); y = oy + n(); p.curveTo(x1, y1, x2, y2, x, y); cx = x2; cy = y2;
                    }
                    case 'Q' -> {
                        double x1 = ox + n(), y1 = oy + n();
                        x = ox + n(); y = oy + n(); p.quadTo(x1, y1, x, y); cx = x1; cy = y1;
                    }
                    case 'T' -> {
                        boolean q = "QqTt".indexOf(prev) >= 0;
                        double x1 = q ? 2 * x - cx : x, y1 = q ? 2 * y - cy : y;
                        x = ox + n(); y = oy + n(); p.quadTo(x1, y1, x, y); cx = x1; cy = y1;
                    }
                    case 'A' -> {
                        double rx = n(), ry = n(), rot = n();
                        boolean large = flag(), sweep = flag();
                        double ex = ox + n(), ey = oy + n();
                        arc(p, x, y, rx, ry, rot, large, sweep, ex, ey);
                        x = ex; y = ey;
                    }
                    case 'Z' -> { p.closePath(); x = sx; y = sy; }
                    default -> { i++; }
                }
                prev = cmd;
                if (Character.toUpperCase(cmd) == 'Z') { cmd = 0; prev = 'Z'; }
                // Implicit repeats: keep the command while numbers follow.
                if (cmd != 0 && !more()) { /* next letter picks the command */ }
            }
        }

        // Endpoint arc (SVG F.6.5) as a center arc.
        static void arc(Path2D.Double p, double x1, double y1, double rx, double ry, double deg, boolean large, boolean sweep, double x2, double y2) {
            if (rx == 0 || ry == 0) { p.lineTo(x2, y2); return; }
            if (x1 == x2 && y1 == y2) return;
            rx = Math.abs(rx); ry = Math.abs(ry);
            double phi = Math.toRadians(deg % 360), cos = Math.cos(phi), sin = Math.sin(phi);
            double dx = (x1 - x2) / 2, dy = (y1 - y2) / 2;
            double xp = cos * dx + sin * dy, yp = -sin * dx + cos * dy;
            double lam = xp * xp / (rx * rx) + yp * yp / (ry * ry);
            if (lam > 1) { rx *= Math.sqrt(lam); ry *= Math.sqrt(lam); }
            double num = rx * rx * ry * ry - rx * rx * yp * yp - ry * ry * xp * xp;
            double den = rx * rx * yp * yp + ry * ry * xp * xp;
            double k = Math.sqrt(Math.max(0, num / den)) * (large == sweep ? -1 : 1);
            double cxp = k * rx * yp / ry, cyp = -k * ry * xp / rx;
            double cx = cos * cxp - sin * cyp + (x1 + x2) / 2, cy = sin * cxp + cos * cyp + (y1 + y2) / 2;
            double t1 = ang(1, 0, (xp - cxp) / rx, (yp - cyp) / ry);
            double dt = ang((xp - cxp) / rx, (yp - cyp) / ry, (-xp - cxp) / rx, (-yp - cyp) / ry);
            if (!sweep && dt > 0) dt -= 2 * Math.PI;
            if (sweep && dt < 0) dt += 2 * Math.PI;
            // Arc2D angles run counter-clockwise in a y-up space: negate them for y-down.
            Arc2D.Double a = new Arc2D.Double(-rx, -ry, 2 * rx, 2 * ry, -Math.toDegrees(t1), -Math.toDegrees(dt), Arc2D.OPEN);
            AffineTransform t = AffineTransform.getTranslateInstance(cx, cy);
            t.rotate(phi);
            p.append(t.createTransformedShape(a).getPathIterator(null), true);
        }

        static double ang(double ux, double uy, double vx, double vy) {
            return Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
        }
    }
}
