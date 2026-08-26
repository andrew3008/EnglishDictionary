package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;

import io.opentelemetry.sdk.OpenTelemetrySdk;

final class ControlledAgentRuntimeAttestor implements RuntimeEvidenceAttestor {

    static final String DISTRIBUTION_EVIDENCE_MISSING = "DISTRIBUTION_EVIDENCE_MISSING";
    static final String DISTRIBUTION_MISMATCH = "DISTRIBUTION_MISMATCH";
    static final String DEVELOPMENT_VERSION_FORBIDDEN = "DEVELOPMENT_VERSION_FORBIDDEN";

    /**
     * Классифицирует настоящий {@link LinkageError} при активации: разрыв ABI между
     * агентом и расширением. Гейта, штампующего этот код без измерения, больше нет.
     */
    static final String EXTENSION_LINKAGE_ERROR = "EXTENSION_LINKAGE_ERROR";

    /** Метка null-загрузчика в описании топологии; понятием wire-контракта не является. */
    private static final String BOOTSTRAP_LOADER_LABEL = "bootstrap";
    private static final String JAVA_AGENT_PREFIX = "-javaagent:";
    private static final String IDENTITY_ENTRY =
            "META-INF/platform-tracing/javaagent-distribution.properties";
    private static final String EXPECTED_DISTRIBUTION_GROUP =
            "space.br1440.platform.starters.tracing";
    private static final String EXPECTED_DISTRIBUTION_ARTIFACT = "platform-tracing-javaagent";
    private static final String EXPECTED_EXTENSION_PATH =
            "extensions/platform-tracing-otel-javaagent-extension.jar";
    private static final String UPSTREAM_COORDINATE_PREFIX =
            "io.opentelemetry.javaagent:opentelemetry-javaagent:";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Set<String> IDENTITY_KEYS = Set.of(
            "format.version",
            "distribution.group",
            "distribution.artifact",
            "distribution.version",
            "upstream.coordinate",
            "upstream.sha256",
            "extension.path",
            "extension.sha256",
            "minimum.java"
    );

    private final RuntimeEnvironment environment;

    private ControlledAgentRuntimeAttestor(RuntimeEnvironment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    static ControlledAgentRuntimeAttestor production() {
        return new ControlledAgentRuntimeAttestor(SystemRuntimeEnvironment.INSTANCE);
    }

    static ControlledAgentRuntimeAttestor forTesting(RuntimeEnvironment environment) {
        return new ControlledAgentRuntimeAttestor(environment);
    }

    @Override
    public ExtensionRuntimeEvidence attest(String extensionVersion) {
        String normalizedExtensionVersion = requireReleaseVersion(extensionVersion);
        String expectedSha256 = requireExpectedDistributionSha256();
        ControlledAgentArtifact artifact = locateControlledAgent();
        String actualSha256 = sha256(artifact.path(), DISTRIBUTION_MISMATCH,
                "Unable to calculate controlled agent SHA-256");
        if (!expectedSha256.equals(actualSha256)) {
            throw failure(
                    DISTRIBUTION_MISMATCH,
                    "Controlled agent SHA-256 does not match the externally supplied expected digest"
            );
        }

        DistributionIdentity identity = validateIdentity(
                artifact.path(), artifact.identity(), normalizedExtensionVersion);

        LoaderDescriptions loaders = captureLoaderDescriptions();
        return new ExtensionRuntimeEvidence(
                actualSha256,
                identity.upstreamVersion(),
                normalizedExtensionVersion,
                loaders.extensionDefiningLoader(),
                loaders.sdkDefiningLoader()
        );
    }

    private String requireReleaseVersion(String extensionVersion) {
        if (extensionVersion == null || extensionVersion.isBlank()
                || "development".equalsIgnoreCase(extensionVersion.trim())) {
            throw failure(
                    DEVELOPMENT_VERSION_FORBIDDEN,
                    "Extension version development is forbidden in the production readiness profile"
            );
        }
        return extensionVersion.trim();
    }

    private String requireExpectedDistributionSha256() {
        String expected = environment.systemProperty(ExtensionPropertyNames.CONTROLLED_AGENT_EXPECTED_SHA256);
        if (expected == null || expected.isBlank()) {
            throw failure(
                    DISTRIBUTION_EVIDENCE_MISSING,
                    "Expected controlled agent SHA-256 system property is missing: "
                            + ExtensionPropertyNames.CONTROLLED_AGENT_EXPECTED_SHA256
            );
        }
        String normalized = expected.trim().toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(normalized).matches()) {
            throw failure(
                    DISTRIBUTION_EVIDENCE_MISSING,
                    "Expected controlled agent SHA-256 system property is not a 64-character hexadecimal digest"
            );
        }
        return normalized;
    }

    private ControlledAgentArtifact locateControlledAgent() {
        List<ControlledAgentArtifact> matches = new ArrayList<>();
        for (String argument : environment.inputArguments()) {
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
                    DISTRIBUTION_EVIDENCE_MISSING,
                    "No -javaagent artifact with the controlled agent identity resource was found"
            );
        }
        if (matches.size() != 1) {
            throw failure(
                    DISTRIBUTION_MISMATCH,
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
                    DISTRIBUTION_EVIDENCE_MISSING,
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
                    DISTRIBUTION_EVIDENCE_MISSING,
                    "Unable to inspect a -javaagent artifact",
                    exception
            );
        }
    }

    private static DistributionIdentity validateIdentity(Path artifact,
                                                         Properties identity,
                                                         String extensionVersion) {
        if (!identity.stringPropertyNames().equals(IDENTITY_KEYS)) {
            throw failure(
                    DISTRIBUTION_MISMATCH,
                    "Controlled agent identity resource has an unexpected key set"
            );
        }
        requireIdentity(identity, "format.version", "1");
        requireIdentity(identity, "distribution.group", EXPECTED_DISTRIBUTION_GROUP);
        requireIdentity(identity, "distribution.artifact", EXPECTED_DISTRIBUTION_ARTIFACT);
        requireIdentity(identity, "distribution.version", extensionVersion);
        requireIdentity(identity, "extension.path", EXPECTED_EXTENSION_PATH);

        String upstreamCoordinate = requireIdentityValue(identity, "upstream.coordinate");
        if (!upstreamCoordinate.startsWith(UPSTREAM_COORDINATE_PREFIX)
                || upstreamCoordinate.length() == UPSTREAM_COORDINATE_PREFIX.length()) {
            throw failure(DISTRIBUTION_MISMATCH, "Controlled agent upstream coordinate is invalid");
        }
        requireIdentity(
                identity,
                "upstream.sha256",
                PinnedOtelJavaAgentAcpProviders.OTEL_JAVA_AGENT_SHA256
        );

        String expectedExtensionSha256 = requireSha256(identity, "extension.sha256");
        String actualExtensionSha256 = sha256ZipEntry(artifact, EXPECTED_EXTENSION_PATH);
        if (!expectedExtensionSha256.equals(actualExtensionSha256)) {
            throw failure(
                    DISTRIBUTION_MISMATCH,
                    "Packaged extension SHA-256 does not match the controlled agent identity resource"
            );
        }

        int minimumJava;
        try {
            minimumJava = Integer.parseInt(requireIdentityValue(identity, "minimum.java"));
        } catch (NumberFormatException exception) {
            throw failure(DISTRIBUTION_MISMATCH, "Controlled agent minimum.java is invalid", exception);
        }
        if (minimumJava <= 0 || Runtime.version().feature() < minimumJava) {
            throw failure(
                    DISTRIBUTION_MISMATCH,
                    "Current JVM does not satisfy the controlled agent minimum.java identity"
            );
        }

        return new DistributionIdentity(upstreamCoordinate.substring(UPSTREAM_COORDINATE_PREFIX.length()));
    }

    /**
     * Снимает описания определяющих загрузчиков. Метод ничего не аттестует: он собирает
     * измеренные факты, а решение об изоляции планов принимает Spring, сравнивая описания
     * между собой.
     */
    private LoaderDescriptions captureLoaderDescriptions() {
        return new LoaderDescriptions(
                describeLoader(environment.extensionDefiningLoader()),
                describeLoader(environment.sdkDefiningLoader())
        );
    }

    private static String describeLoader(ClassLoader loader) {
        if (loader == null) {
            return BOOTSTRAP_LOADER_LABEL;
        }
        String name = loader.getName();
        String stableName = (name == null || name.isBlank()) ? loader.getClass().getName() : name;
        return stableName + "@" + Integer.toHexString(System.identityHashCode(loader));
    }

    private static void requireIdentity(Properties identity, String name, String expected) {
        String actual = requireIdentityValue(identity, name);
        if (!expected.equals(actual)) {
            throw failure(DISTRIBUTION_MISMATCH, "Controlled agent identity mismatch for " + name);
        }
    }

    private static String requireIdentityValue(Properties identity, String name) {
        String value = identity.getProperty(name);
        if (value == null || value.isBlank()) {
            throw failure(DISTRIBUTION_MISMATCH, "Controlled agent identity is missing " + name);
        }
        return value.trim();
    }

    private static String requireSha256(Properties identity, String name) {
        String value = requireIdentityValue(identity, name).toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(value).matches()) {
            throw failure(DISTRIBUTION_MISMATCH, "Controlled agent identity has an invalid " + name);
        }
        return value;
    }

    private static String sha256ZipEntry(Path path, String entryName) {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null || entry.isDirectory()) {
                throw failure(DISTRIBUTION_MISMATCH, "Controlled agent packaged extension is missing");
            }
            try (InputStream input = zip.getInputStream(entry)) {
                return sha256(input);
            }
        } catch (IOException exception) {
            throw failure(DISTRIBUTION_MISMATCH, "Unable to hash the packaged extension", exception);
        }
    }

    private static String sha256(Path path, String failureCode, String failureMessage) {
        try (InputStream input = Files.newInputStream(path)) {
            return sha256(input);
        } catch (IOException exception) {
            throw failure(failureCode, failureMessage, exception);
        }
    }

    private static String sha256(InputStream input) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new ExceptionInInitializerError(exception);
        }
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static RuntimeEvidenceAttestationException failure(String code, String message) {
        return new RuntimeEvidenceAttestationException(code, message);
    }

    private static RuntimeEvidenceAttestationException failure(String code,
                                                               String message,
                                                               Throwable cause) {
        return new RuntimeEvidenceAttestationException(code, message, cause);
    }

    interface RuntimeEnvironment {

        String systemProperty(String name);

        List<String> inputArguments();

        ClassLoader extensionDefiningLoader();

        ClassLoader sdkDefiningLoader();
    }

    private enum SystemRuntimeEnvironment implements RuntimeEnvironment {
        INSTANCE;

        @Override
        public String systemProperty(String name) {
            return System.getProperty(name);
        }

        @Override
        public List<String> inputArguments() {
            return ManagementFactory.getRuntimeMXBean().getInputArguments();
        }

        @Override
        public ClassLoader extensionDefiningLoader() {
            return BootstrapAutoConfigurationCustomizerProvider.class.getClassLoader();
        }

        @Override
        public ClassLoader sdkDefiningLoader() {
            return OpenTelemetrySdk.class.getClassLoader();
        }
    }

    private record ControlledAgentArtifact(Path path, Properties identity) {
    }

    private record DistributionIdentity(String upstreamVersion) {
    }

    private record LoaderDescriptions(String extensionDefiningLoader,
                                      String sdkDefiningLoader) {
    }
}
