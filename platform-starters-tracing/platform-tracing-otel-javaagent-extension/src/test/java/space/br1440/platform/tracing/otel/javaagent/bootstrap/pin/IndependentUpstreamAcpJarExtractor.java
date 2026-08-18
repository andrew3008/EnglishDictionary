package space.br1440.platform.tracing.otel.javaagent.bootstrap.pin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class IndependentUpstreamAcpJarExtractor {

    public static final String ACP_SPI =
            "io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider";
    public static final String DESCRIPTOR_PATH = "inst/META-INF/services/" + ACP_SPI;
    public static final String PLATFORM_PROVIDER =
            "space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapAutoConfigurationCustomizerProvider";

    private static final Pattern BINARY_NAME =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*$");

    private IndependentUpstreamAcpJarExtractor() {
    }

    public static List<String> extractSortedNames(Path agentJar) throws IOException {
        try (ZipFile zip = new ZipFile(agentJar.toFile())) {
            List<String> acpDescriptors = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".jar")) {
                    throw new IllegalStateException("Nested JAR is not part of the 2.30.0 loader contract: " + name);
                }
                if (name.contains("META-INF/services/" + ACP_SPI)) {
                    acpDescriptors.add(name);
                }
            }
            if (!acpDescriptors.equals(List.of(DESCRIPTOR_PATH))) {
                throw new IllegalStateException("Unexpected ACP descriptors: " + acpDescriptors);
            }
            ZipEntry descriptor = zip.getEntry(DESCRIPTOR_PATH);
            if (descriptor == null) {
                throw new IllegalStateException("Missing ACP descriptor: " + DESCRIPTOR_PATH);
            }
            byte[] raw = zip.getInputStream(descriptor).readAllBytes();
            return parseDescriptor(raw);
        }
    }

    public static List<String> parseDescriptor(byte[] raw) {
        if (raw.length >= 3
                && (raw[0] & 0xFF) == 0xEF
                && (raw[1] & 0xFF) == 0xBB
                && (raw[2] & 0xFF) == 0xBF) {
            throw new IllegalStateException("ACP descriptor must not have a UTF-8 BOM");
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        if (text.indexOf('\r') >= 0) {
            throw new IllegalStateException("ACP descriptor must be LF-only");
        }
        Set<String> names = new LinkedHashSet<>();
        for (String line : text.split("\n", -1)) {
            int comment = line.indexOf('#');
            String name = (comment >= 0 ? line.substring(0, comment) : line).trim();
            if (name.isEmpty()) {
                continue;
            }
            if (!BINARY_NAME.matcher(name).matches()) {
                throw new IllegalStateException("Malformed ACP binary name: " + name);
            }
            if (!names.add(name)) {
                throw new IllegalStateException("Duplicate ACP declaration: " + name);
            }
            if (PLATFORM_PROVIDER.equals(name)) {
                throw new IllegalStateException("Platform provider must not appear in upstream pin");
            }
        }
        if (names.isEmpty()) {
            throw new IllegalStateException("ACP descriptor is empty");
        }
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }
}
