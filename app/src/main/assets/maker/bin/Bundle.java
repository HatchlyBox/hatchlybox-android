// Remove only the old JAR signature; signing happens afterwards without executing project code.
import java.nio.file.*;
import java.util.zip.*;
public class Bundle {
    public static void main(String[] a) throws Exception {
        try (var in=new ZipInputStream(Files.newInputStream(Paths.get(a[0])));
             var out=new ZipOutputStream(Files.newOutputStream(Paths.get(a[1]), StandardOpenOption.CREATE_NEW))) {
            ZipEntry e;
            while ((e=in.getNextEntry())!=null) {
                String name=e.getName().toUpperCase(java.util.Locale.ROOT);
                if (name.matches("META-INF/[^/]+\\.(RSA|DSA|EC|SF)") || name.equals("META-INF/MANIFEST.MF")) continue;
                out.putNextEntry(new ZipEntry(e.getName())); in.transferTo(out); out.closeEntry();
            }
        }
    }
}
