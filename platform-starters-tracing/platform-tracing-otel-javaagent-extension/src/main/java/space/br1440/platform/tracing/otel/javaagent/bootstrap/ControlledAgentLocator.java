package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import lombok.experimental.UtilityClass;

import static space.br1440.platform.tracing.otel.javaagent.bootstrap.AttestationFailures.failure;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@UtilityClass
final class ControlledAgentLocator {

    private static final String JAVA_AGENT_PREFIX = "-javaagent:";
    private static final String IDENTITY_ENTRY = "META-INF/platform-tracing/javaagent-distribution.properties";

    static ControlledAgentArtifact locate(List<String> inputArguments) {
        List<ControlledAgentArtifact> matches = new ArrayList<>();
        for (String argument : inputArguments) {
            if (argument == null || !argument.startsWith(JAVA_AGENT_PREFIX)) {
                continue;
            }

            Path path = javaAgentPath(argument);
            Properties identity = readIdentityIfPresent(path);
            if (identity != null) {
                matches.add(new ControlledAgentArtifact(path, identity));
            }
        }

        if (matches.isEmpty()) {
            throw failure(
                    AttestationFailureCode.DISTRIBUTION_EVIDENCE_MISSING,
                    "No -javaagent artifact with the controlled agent identity resource was found"
            );
        }

        if (matches.size() != 1) {
            throw failure(
                    AttestationFailureCode.DISTRIBUTION_MISMATCH,
                    "More than one -javaagent artifact exposes the controlled agent identity resource"
            );
        }

        return matches.get(0);
    }

    private static Path javaAgentPath(String argument) {
        String value = argument.substring(JAVA_AGENT_PREFIX.length()).trim();
        String lower = value.toLowerCase(Locale.ROOT);
        int options = lower.indexOf(".jar=");
        if (options >= 0) {
            value = value.substring(0, options + ".jar".length());
        }

        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }

        try {
            return Path.of(value).toAbsolutePath().normalize().toRealPath();
        } catch (IOException | InvalidPathException exception) {
            throw failure(
                    AttestationFailureCode.DISTRIBUTION_EVIDENCE_MISSING,
                    "Unable to resolve a -javaagent artifact path",
                    exception
            );
        }
    }

    private static Properties readIdentityIfPresent(Path path) {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            ZipEntry entry = zip.getEntry(IDENTITY_ENTRY);
            if (entry == null || entry.isDirectory()) {
                return null;
            }

            Properties identity = new Properties();
            try (InputStream input = zip.getInputStream(entry)) {
                identity.load(input);
            }

            return identity;
        } catch (IOException exception) {
            throw failure(
                    AttestationFailureCode.DISTRIBUTION_EVIDENCE_MISSING,
                    "Unable to inspect a -javaagent artifact",
                    exception
            );
        }
    }
}
