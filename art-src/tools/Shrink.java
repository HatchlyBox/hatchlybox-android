import javax.imageio.*; import javax.imageio.stream.*; import java.awt.*; import java.awt.image.*; import java.io.*;
/** Shrink <in> <out.jpg> <width> <quality 0-1>: a high-quality downscale saved as JPEG. */
public class Shrink {
  public static void main(String[] a) throws Exception {
    BufferedImage src = ImageIO.read(new File(a[0]));
    int w = Integer.parseInt(a[2]), h = Math.round(src.getHeight() * (float) w / src.getWidth());
    BufferedImage cur = src;
    while (cur.getWidth() / 2 >= w) cur = scale(cur, cur.getWidth() / 2, cur.getHeight() / 2);
    cur = scale(cur, w, h);
    ImageWriter wr = ImageIO.getImageWritersByFormatName("jpg").next();
    ImageWriteParam p = wr.getDefaultWriteParam(); p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); p.setCompressionQuality(Float.parseFloat(a[3]));
    try (ImageOutputStream o = ImageIO.createImageOutputStream(new File(a[1]))) { wr.setOutput(o); wr.write(null, new IIOImage(cur, null, null), p); }
  }
  static BufferedImage scale(BufferedImage s, int w, int h) {
    BufferedImage d = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB); Graphics2D g = d.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    g.drawImage(s, 0, 0, w, h, null); g.dispose(); return d;
  }
}
