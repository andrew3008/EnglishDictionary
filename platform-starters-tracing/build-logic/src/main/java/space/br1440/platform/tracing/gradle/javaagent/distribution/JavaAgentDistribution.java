package space.br1440.platform.tracing.gradle.javaagent.distribution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.gradle.api.GradleException;

final class JavaAgentDistribution {

    static final String MAVEN_ARTIFACT_ID = "platform-tracing-javaagent";
    static final String MANIFEST_ENTRY_PATH = "META-INF/MANIFEST.MF";
    static final String EXTENSIONS_DIRECTORY = "extensions";
    static final String EMBEDDED_EXTENSION_NAME = "platform-tracing-otel-javaagent-extension.jar";
    static final String EXTENSION_ENTRY_PATH = EXTENSIONS_DIRECTORY + "/" + EMBEDDED_EXTENSION_NAME;
    static final String IDENTITY_DIRECTORY = "META-INF/platform-tracing";
    static final String IDENTITY_FILE_NAME = "javaagent-distribution.properties";
    static final String IDENTITY_ENTRY_PATH = IDENTITY_DIRECTORY + "/" + IDENTITY_FILE_NAME;

    private static final String UPSTREAM_PIN_ENTRY = "META-INF/platform-tracing/otel-javaagent-acp-pin.properties";
    private static final String UPSTREAM_PIN_KEY = "otel.javaagent.sha256";
    private static final Pattern LOWERCASE_SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> RESERVED_ENTRIES = Set.of(IDENTITY_ENTRY_PATH.toLowerCase(Locale.ROOT));

    private JavaAgentDistribution() {
    }

    static String requireReviewedUpstreamSha256(Path upstreamAgentJar, Path packagedExtensionJar) {
        String raw = DistributionFiles.readJarProperties(packagedExtensionJar, UPSTREAM_PIN_ENTRY)
                .getProperty(UPSTREAM_PIN_KEY);

        if (raw == null) {
            String message = UPSTREAM_PIN_KEY + " is missing in " + entryOf(packagedExtensionJar, UPSTREAM_PIN_ENTRY);
            throw new GradleException(message);
        }

        String reviewed = requireSha256(UPSTREAM_PIN_KEY + " in " + packagedExtensionJar, raw.strip());
        String actual = DistributionFiles.sha256Hex(upstreamAgentJar);
        if (!reviewed.equals(actual)) {
            String message = "Upstream OpenTelemetry Java agent %s does not match reviewed pin: expected %s, actual %s"
                    .formatted(upstreamAgentJar, reviewed, actual);
            throw new GradleException(message);
        }
        return actual;
    }

    static void requireNoPlatformEntries(Path upstreamAgentJar) {
        List<String> conflicts;
        try (ZipFile zip = new ZipFile(upstreamAgentJar.toFile(), StandardCharsets.UTF_8)) {
            conflicts = zip.stream()
                    .map(ZipEntry::getName)
                    .filter(name -> isPlatformOwned(name.toLowerCase(Locale.ROOT)))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new GradleException("Cannot inspect OTel Java agent " + upstreamAgentJar, exception);
        }

        if (!conflicts.isEmpty()) {
            String message = "OTel Java agent %s already contains platform content:%s"
                    .formatted(upstreamAgentJar, bulletList(conflicts));
            throw new GradleException(message);
        }
    }

    static DistributionIdentity createIdentity(String distributionVersion,
                                               String upstreamCoordinate,
                                               String upstreamSha256,
                                               Path packagedExtensionJar) {
        return new DistributionIdentity(
                distributionVersion,
                upstreamCoordinate,
                upstreamSha256,
                DistributionFiles.sha256Hex(packagedExtensionJar)
        );
    }

    record DistributionIdentity(String distributionVersion,
                                String upstreamCoordinate,
                                String upstreamSha256,
                                String extensionSha256) {

        DistributionIdentity {
            requireValue("distribution.version", distributionVersion);
            requireValue("upstream.coordinate", upstreamCoordinate);
            requireSha256("upstream.sha256", upstreamSha256);
            requireSha256("extension.sha256", extensionSha256);
        }

        void writeTo(Path file) {
            DistributionFiles.writeUtf8(file, toPropertiesText(), "distribution identity");
        }

        private String toPropertiesText() {
            Map<String, String> values = new TreeMap<>(Map.of(
                    "format.version", "1",
                    "distribution.group", "space.br1440.platform.starters.tracing",
                    "distribution.artifact", MAVEN_ARTIFACT_ID,
                    "distribution.version", distributionVersion,
                    "upstream.coordinate", upstreamCoordinate,
                    "upstream.sha256", upstreamSha256,
                    "extension.path", EXTENSION_ENTRY_PATH,
                    "extension.sha256", extensionSha256,
                    "minimum.java", "21")
            );

            StringBuilder content = new StringBuilder();
            values.forEach((key, value) -> content.append(key).append('=').append(value).append('\n'));
            return content.toString();
        }
    }

    private static boolean isPlatformOwned(String lowerCaseName) {
        return EXTENSIONS_DIRECTORY.equals(lowerCaseName)
                || lowerCaseName.startsWith(EXTENSIONS_DIRECTORY + "/")
                || RESERVED_ENTRIES.contains(lowerCaseName);
    }

    private static String requireSha256(String what, String value) {
        if (value == null || !LOWERCASE_SHA256.matcher(value).matches()) {
            throw new GradleException(what + " must be 64 lowercase hex characters, got: " + value);
        }

        return value;
    }

    private static void requireValue(String key, String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\\') >= 0) {

            String message = key + " must be a trimmed non-blank value without line breaks or backslashes, got: " + value;
            throw new GradleException(message);
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static String entryOf(Path jar, String entryName) {
        return jar + "!/" + entryName;
    }

    private static String bulletList(List<String> items) {
        return "\n  " + String.join("\n  ", items);
    }
}
