package cl.duoc.eft.batch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

public final class InputManifest {
    private InputManifest() { }

    public static List<Path> files(Path root, Kind kind) throws IOException {
        try (Stream<Path> entries = Files.walk(root, 2)) {
            List<Path> files = entries.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(kind.filename))
                    .sorted().toList();
            if (files.isEmpty()) throw new IllegalArgumentException("No se encuentra " + kind.filename + " en " + root);
            return files;
        }
    }

    public static String fingerprint(Path root, Kind kind, String policy) throws IOException {
        MessageDigest digest = digest();
        digest.update(("eft-batch-v1|" + kind + "|" + policy + "\n").getBytes(StandardCharsets.UTF_8));
        for (Path path : files(root, kind)) {
            digest.update(relative(root, path).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    public static String hash(String value) {
        return HexFormat.of().formatHex(digest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
