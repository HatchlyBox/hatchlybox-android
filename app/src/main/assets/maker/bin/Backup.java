// Portable project backup; extraction never creates links or follows paths outside the staging folder.
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
public class Backup {
    static long limit = 4L * 1024 * 1024 * 1024;
    static void add(ZipOutputStream z, Path root, Path path, String prefix) throws IOException {
        if (Files.isSymbolicLink(path)) return;
        if (Files.isDirectory(path)) {
            String name = path.getFileName().toString();
            String relative=root.relativize(path).toString().replace('\\', '/');
            if (Set.of("build", "app/build", ".gradle", ".kotlin", ".peek").contains(relative)) return;
            try (var children = Files.list(path)) {
                for (Path child : children.sorted().toList()) add(z, root, child, prefix);
            }
        } else if (Files.isRegularFile(path)) {
            z.putNextEntry(new ZipEntry(prefix + root.relativize(path).toString().replace('\\', '/')));
            Files.copy(path, z); z.closeEntry();
        }
    }
    public static void main(String[] a) throws Exception {
        Path file = Paths.get(a[1]);
        if (a[0].equals("create")) {
            try (var z = new ZipOutputStream(Files.newOutputStream(file, StandardOpenOption.CREATE_NEW))) {
                for (int i=2; i<a.length; i+=2) {
                    Path root = Paths.get(a[i+1]);
                    if (Files.isDirectory(root)) add(z, root, root, a[i] + "/");
                }
            }
        } else if (a[0].equals("extract")) {
            Path root = Paths.get(a[2]).toAbsolutePath().normalize();
            Set<String> seen = new HashSet<>(); long total=0;
            try (var z = new ZipInputStream(Files.newInputStream(file))) {
                ZipEntry e; byte[] b = new byte[65536];
                while ((e=z.getNextEntry()) != null) {
                    String name=e.getName();
                    if (!name.matches("(project|keys|artifacts)/.+") || name.contains("\\") || !seen.add(name)) throw new IOException("invalid archive entry");
                    Path dest=root.resolve(name).normalize();
                    if (!dest.startsWith(root) || name.contains("../") || name.startsWith("/")) throw new IOException("unsafe archive path");
                    if (e.isDirectory()) { Files.createDirectories(dest); continue; }
                    Files.createDirectories(dest.getParent());
                    try (var out=Files.newOutputStream(dest, StandardOpenOption.CREATE_NEW)) {
                        int n; while ((n=z.read(b))!=-1) { total+=n; if (total>limit) throw new IOException("backup exceeds 4 GiB"); out.write(b,0,n); }
                    }
                    z.closeEntry(); // CRC check detects damaged archives.
                }
            }
            if (!Files.isRegularFile(root.resolve("project/.hatchlybox.json"))) throw new IOException("project metadata missing");
        } else throw new IOException("unknown operation");
    }
}
