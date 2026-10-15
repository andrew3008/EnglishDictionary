package space.br1440.platform.tracing.otel.javaagent.distribution;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import space.br1440.platform.common.util.StringUtils;

final class AgentArchive {

    static final String EXTENSION_ENTRY = "extensions/platform-tracing-otel-javaagent-extension.jar";
    static final String IDENTITY_ENTRY = "META-INF/platform-tracing/javaagent-distribution.properties";

    private AgentArchive() {
    }

    static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (StringUtils.isNullOrBlank(value)) {
            throw new IllegalStateException("Required test system property is missing: %s".formatted(name));
        }
        return value;
    }

    static Path requiredPath(String name) {
        Path path = Path.of(requiredProperty(name));
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Required test file is missing: %s".formatted(path));
        }
        return path;
    }

    @SuppressWarnings("SameParameterValue")
    static Path requiredDirectory(String name) {
        Path path = Path.of(requiredProperty(name));
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException("Required test directory is missing: %s".formatted(path));
        }
        return path;
    }

    static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append("%02x".formatted(value & 0xFF));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    static Map<String, byte[]> readEntries(Path archive) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            for (ZipEntry entry : entries(zip)) {
                try (InputStream input = zip.getInputStream(entry)) {
                    result.put(entry.getName(), input.readAllBytes());
                }
            }
        }
        return result;
    }

    static byte[] readNestedEntry(Path archive, String name) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (name.equals(entry.getName())) {
                    return zip.readAllBytes();
                }
            }
        }
        throw new IllegalStateException("Required ZIP entry is missing: %s".formatted(name));
    }

    static Properties loadProperties(byte[] bytes) throws IOException {
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(bytes));
        return properties;
    }

    static List<ZipEntry> entries(ZipFile zip) {
        List<ZipEntry> result = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            result.add(entries.nextElement());
        }
        return result;
    }
}
