import javax.imageio.*; import java.awt.*; import java.awt.image.*; import java.io.*;
/** Sheet <out.jpg> <thumbWidth> <cols> <png>...: thumbnails in a grid with their names. */
public class Sheet {
  public static void main(String[] a) throws Exception {
    int tw = Integer.parseInt(a[1]), cols = Integer.parseInt(a[2]), n = a.length - 3, th = tw * 3 / 2, rows = (n + cols - 1) / cols;
    BufferedImage out = new BufferedImage(cols * tw, rows * (th + 14), BufferedImage.TYPE_INT_RGB);
    Graphics2D g = out.createGraphics(); g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    for (int i = 0; i < n; i++) {
      BufferedImage s = ImageIO.read(new File(a[i + 3]));
      while (s.getWidth() / 2 >= tw) s = Shrink.scale(s, s.getWidth() / 2, s.getHeight() / 2);
      int x = (i % cols) * tw, y = (i / cols) * (th + 14);
      g.drawImage(s, x, y + 14, tw, th, null);
      g.setColor(Color.WHITE); g.setFont(new Font("SansSerif", Font.PLAIN, 11)); g.drawString(new File(a[i + 3]).getName().replace(".png", ""), x + 3, y + 11);
    }
    ImageIO.write(out, "jpg", new File(a[0]));
  }
}
