package space.br1440.platform.tracing.otel.javaagent.bootstrap.verification;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import lombok.experimental.UtilityClass;

@UtilityClass
final class ControlledAgentArtifactLocator {

    private static final int MAX_IDENTITY_BYTES = 16 * 1024;
    private static final String JAVA_AGENT_PREFIX = "-javaagent:";
    static final String IDENTITY_ENTRY = "META-INF/platform-tracing/javaagent-distribution.properties";

    static ControlledAgentArtifact locate(List<String> inputArguments) {
        Objects.requireNonNull(inputArguments, "inputArguments");

        ControlledAgentArtifact match = null;
        for (String argument : inputArguments) {
            if (!isJavaAgentArgument(argument)) {
                continue;
            }

            Path artifactPath = resolveArtifactPath(argument);
            Map<String, String> identity = readIdentityIfPresent(artifactPath);
            if (identity == null) {
                continue;
            }

            if (match != null) {
                throw new VerificationException(
                        VerificationFailureCode.DISTRIBUTION_MISMATCH,
                        "More than one -javaagent artifact contains the controlled agent identity resource"
                );
            }

            match = new ControlledAgentArtifact(artifactPath, identity);
        }

        if (match == null) {
            throw new VerificationException(
                    VerificationFailureCode.DISTRIBUTION_EVIDENCE_MISSING,
                    "No -javaagent artifact contains the controlled agent identity resource"
            );
        }

        return match;
    }

    private static boolean isJavaAgentArgument(String argument) {
        return (argument != null) && argument.startsWith(JAVA_AGENT_PREFIX);
    }

    private static Path resolveArtifactPath(String argument) {
        String specification = argument.substring(JAVA_AGENT_PREFIX.length());
        String pathText = extractPath(specification);
        try {
            return Path.of(pathText).toRealPath();
        } catch (IOException | InvalidPathException exception) {
            throw new VerificationException(
                    VerificationFailureCode.DISTRIBUTION_EVIDENCE_MISSING,
                    "Unable to resolve a -javaagent artifact path",
                    exception
            );
        }
    }

    private static String extractPath(String specification) {
        int optionsSeparator = specification.indexOf('=');

        String path = optionsSeparator >= 0
                ? specification.substring(0, optionsSeparator)
                : specification;
        path = path.trim();
        path = removeSurroundingQuotes(path);
        if (path.isEmpty()) {
            throw new VerificationException(
                    VerificationFailureCode.DISTRIBUTION_EVIDENCE_MISSING,
                    "A -javaagent argument contains an empty artifact path"
            );
        }

        return path;
    }

    private static String removeSurroundingQuotes(String value) {
        boolean startsWithQuote = value.startsWith("\"");
        boolean endsWithQuote = value.endsWith("\"");
        if (startsWithQuote != endsWithQuote) {
            throw new VerificationException(
                    VerificationFailureCode.DISTRIBUTION_EVIDENCE_MISSING,
                    "A -javaagent artifact path contains an unmatched quote"
            );
        }

        if (startsWithQuote && value.length() >= 2) {
            return value.substring(1, value.length() - 1);
        }

        return value;
    }

    private static Map<String, String> readIdentityIfPresent(Path artifactPath) {
        try (ZipFile archive = new ZipFile(artifactPath.toFile())) {
            ZipEntry identityEntry = archive.getEntry(IDENTITY_ENTRY);
            if (identityEntry == null || identityEntry.isDirectory()) {
                return null;
            }

            byte[] content = readBounded(archive, identityEntry);
            return loadProperties(content);
        } catch (IOException | IllegalArgumentException exception) {
            throw new VerificationException(
                    VerificationFailureCode.DISTRIBUTION_EVIDENCE_MISSING,
                    "Unable to inspect a -javaagent artifact",
                    exception
            );
        }
    }

    private static byte[] readBounded(ZipFile archive, ZipEntry identityEntry) throws IOException {
        long declaredSize = identityEntry.getSize();
        if (declaredSize > MAX_IDENTITY_BYTES) {
            throw new IOException("Controlled agent identity resource exceeds " + MAX_IDENTITY_BYTES + " bytes");
        }

        try (InputStream input = archive.getInputStream(identityEntry)) {
            byte[] content = input.readNBytes(MAX_IDENTITY_BYTES + 1);
            if (content.length > MAX_IDENTITY_BYTES) {
                throw new IOException("Controlled agent identity resource exceeds " + MAX_IDENTITY_BYTES + " bytes");
            }

            return content;
        }
    }

    private static Map<String, String> loadProperties(byte[] content) throws IOException {
        Properties identity = new Properties();
        try (InputStream input = new ByteArrayInputStream(content)) {
            identity.load(input);
        }

        Map<String, String> values = HashMap.newHashMap(identity.size());
        for (String name : identity.stringPropertyNames()) {
            values.put(name, identity.getProperty(name));
        }

        return values;
    }
}
