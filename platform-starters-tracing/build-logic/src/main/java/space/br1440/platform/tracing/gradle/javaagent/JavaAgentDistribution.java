package space.br1440.platform.tracing.gradle.javaagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.gradle.api.GradleException;

public final class JavaAgentDistribution {

    static final String MAVEN_ARTIFACT_ID = "platform-tracing-javaagent";
    static final String EXTENSIONS_DIRECTORY = "extensions";
    static final String IDENTITY_DIRECTORY = "META-INF/platform-tracing";
    static final String IDENTITY_FILE_NAME = "javaagent-distribution.properties";
    static final String IDENTITY_ENTRY_PATH = IDENTITY_DIRECTORY + "/" + IDENTITY_FILE_NAME;

    private static final String UPSTREAM_PIN_ENTRY = "META-INF/platform-tracing/otel-javaagent-acp-pin.properties";
    private static final String UPSTREAM_PIN_KEY = "otel.javaagent.sha256";
    private static final Pattern LOWERCASE_SHA256 = Pattern.compile("[0-9a-f]{64}");

    private JavaAgentDistribution() {
    }

    public static String requireReviewedUpstreamSha256(Path upstreamAgentJar, Path packagedExtensionJar) {
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

    public static void requireNoPlatformEntries(Path upstreamAgentJar, Set<String> reservedEntries) {
        Set<String> reserved = reservedEntries.stream()
                .map(entry -> entry.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());

        List<String> conflicts;
        try (ZipFile zip = new ZipFile(upstreamAgentJar.toFile(), StandardCharsets.UTF_8)) {
            conflicts = zip.stream()
                    .map(ZipEntry::getName)
                    .filter(name -> isPlatformOwned(name.toLowerCase(Locale.ROOT), reserved))
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

    public static DistributionIdentity createIdentity(String distributionVersion,
                                                      String upstreamCoordinate,
                                                      String upstreamSha256,
                                                      String extensionPath,
                                                      Path packagedExtensionJar) {
        return new DistributionIdentity(
                distributionVersion,
                upstreamCoordinate,
                upstreamSha256,
                extensionPath,
                DistributionFiles.sha256Hex(packagedExtensionJar)
        );
    }

    public record DistributionIdentity(String distributionVersion,
                                       String upstreamCoordinate,
                                       String upstreamSha256,
                                       String extensionPath,
                                       String extensionSha256) {

        public DistributionIdentity {
            requireValue("distribution.version", distributionVersion);
            requireValue("upstream.coordinate", upstreamCoordinate);
            requireSha256("upstream.sha256", upstreamSha256);
            requireValue("extension.path", extensionPath);
            requireSha256("extension.sha256", extensionSha256);
        }

        public void writeTo(Path file) {
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
                    "extension.path", extensionPath,
                    "extension.sha256", extensionSha256,
                    "minimum.java", "21")
            );

            StringBuilder content = new StringBuilder();
            values.forEach((key, value) -> content.append(key).append('=').append(value).append('\n'));
            return content.toString();
        }
    }

    private static boolean isPlatformOwned(String lowerCaseName, Set<String> reservedEntries) {
        return EXTENSIONS_DIRECTORY.equals(lowerCaseName)
                || lowerCaseName.startsWith(EXTENSIONS_DIRECTORY + "/")
                || reservedEntries.contains(lowerCaseName);
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
