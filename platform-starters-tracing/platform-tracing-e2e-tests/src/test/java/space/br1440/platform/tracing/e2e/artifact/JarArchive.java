package space.br1440.platform.tracing.e2e.artifact;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

final class JarArchive {

    private JarArchive() {
    }

    record Content(Manifest manifest, Map<String, byte[]> entries) {
    }

    static Content read(byte[] source) throws IOException {
        try (JarInputStream input = new JarInputStream(new ByteArrayInputStream(source))) {
            Manifest manifest = input.getManifest();
            Map<String, byte[]> entries = new LinkedHashMap<>();

            JarEntry entry;
            while ((entry = input.getNextJarEntry()) != null) {
                if (!entry.isDirectory() && keepUnsigned(entry.getName())) {
                    entries.put(entry.getName(), input.readAllBytes());
                }
            }
            return new Content(copyManifest(manifest), entries);
        }
    }

    static byte[] write(Content content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output, content.manifest())) {
            writeEntries(jar, content.entries());
        }
        return output.toByteArray();
    }

    static void write(Path target, Content content) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Target JAR path must have a parent directory");
        }
        Files.createDirectories(parent);
        Files.write(target, write(content));
    }

    static byte[] requiredEntry(Map<String, byte[]> entries, String name) {
        byte[] value = entries.get(name);
        if (value == null) {
            throw new IllegalArgumentException("JAR entry is missing: " + name);
        }
        return value;
    }

    private static boolean keepUnsigned(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return !(upper.startsWith("META-INF/")
                && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA")));
    }

    private static Manifest copyManifest(Manifest source) {
        Manifest result = new Manifest();
        if (source == null) {
            result.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            return result;
        }
        result.getMainAttributes().putAll(source.getMainAttributes());
        source.getEntries().forEach((name, attributes) ->
                result.getEntries().put(name, new Attributes(attributes)));
        return result;
    }

    private static void writeEntries(JarOutputStream output, Map<String, byte[]> entries) throws IOException {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            JarEntry targetEntry = new JarEntry(entry.getKey());
            targetEntry.setTime(0L);
            output.putNextEntry(targetEntry);
            output.write(entry.getValue());
            output.closeEntry();
        }
    }
}
