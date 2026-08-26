package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;

class ControlledAgentRuntimeAttestorTest {

    private static final String VERSION = "1.2.0";
    private static final String BOOTSTRAP_LOADER_LABEL = "bootstrap";

    @TempDir
    Path temporaryDirectory;

    @Test
    void attestsActualAgentIdentityAndPublishesMeasuredLoaderDescriptions() throws IOException {
        // given
        Path agent = controlledAgent("extension".getBytes(StandardCharsets.UTF_8), null);
        ClassLoader extensionLoader = isolatedLoader();
        ClassLoader sdkLoader = isolatedLoader();
        ControlledAgentRuntimeAttestor attestor = ControlledAgentRuntimeAttestor.forTesting(environment(
                sha256(agent), agent, extensionLoader, sdkLoader));

        // when
        ExtensionRuntimeEvidence evidence = attestor.attest(VERSION);

        // then
        assertThat(evidence.agentDistributionSha256()).isEqualTo(sha256(agent));
        assertThat(evidence.upstreamAgentVersion()).isEqualTo("2.30.0");
        assertThat(evidence.extensionVersion()).isEqualTo(VERSION);
        assertThat(evidence.extensionDefiningLoader()).isEqualTo(loaderDescription(extensionLoader));
        assertThat(evidence.sdkDefiningLoader()).isEqualTo(loaderDescription(sdkLoader));
        assertThat(evidence.complete()).isTrue();
    }

    @Test
    void isolatedExtensionAndSdkLoadersAreDistinguishable() throws IOException {
        // given
        Path agent = controlledAgent("extension".getBytes(StandardCharsets.UTF_8), null);
        ControlledAgentRuntimeAttestor attestor = ControlledAgentRuntimeAttestor.forTesting(environment(
                sha256(agent), agent, isolatedLoader(), isolatedLoader()));

        // when
        ExtensionRuntimeEvidence evidence = attestor.attest(VERSION);

        // then
        assertThat(evidence.extensionDefiningLoader()).isNotEqualTo(evidence.sdkDefiningLoader());
    }

    @Test
    void bootstrapLoaderIsDescribedWithoutIdentityHash() throws IOException {
        // given
        Path agent = controlledAgent("extension".getBytes(StandardCharsets.UTF_8), null);
        ControlledAgentRuntimeAttestor attestor = ControlledAgentRuntimeAttestor.forTesting(environment(
                sha256(agent), agent, null, isolatedLoader()));

        // when
        ExtensionRuntimeEvidence evidence = attestor.attest(VERSION);

        // then
        assertThat(evidence.extensionDefiningLoader()).isEqualTo(BOOTSTRAP_LOADER_LABEL);
    }

    @Test
    void digestMismatchFailsWithStableDistributionCode() throws IOException {
        // given
        Path agent = controlledAgent("extension".getBytes(StandardCharsets.UTF_8), null);
        ControlledAgentRuntimeAttestor attestor = ControlledAgentRuntimeAttestor.forTesting(environment(
                "0".repeat(64), agent, isolatedLoader(), isolatedLoader()));

        // when
        RuntimeEvidenceAttestationException failure = attestFailure(attestor, VERSION);

        // then
        assertThat(failure.code()).isEqualTo(ControlledAgentRuntimeAttestor.DISTRIBUTION_MISMATCH);
    }

    @Test
    void missingExpectedDigestFailsClosed() throws IOException {
        // given
        Path agent = controlledAgent("extension".getBytes(StandardCharsets.UTF_8), null);
        ControlledAgentRuntimeAttestor attestor = ControlledAgentRuntimeAttestor.forTesting(environment(
                null, agent, isolatedLoader(), isolatedLoader()));

        // when
        RuntimeEvidenceAttestationException failure = attestFailure(attestor, VERSION);

        // then
        assertThat(failure.code()).isEqualTo(ControlledAgentRuntimeAttestor.DISTRIBUTION_EVIDENCE_MISSING);
    }

    @Test
    void developmentVersionIsForbiddenBeforeReadyEvidenceCanBeBuilt() throws IOException {
        // given
        Path agent = controlledAgent("extension".getBytes(StandardCharsets.UTF_8), null);
        ControlledAgentRuntimeAttestor attestor = ControlledAgentRuntimeAttestor.forTesting(environment(
                sha256(agent), agent, isolatedLoader(), isolatedLoader()));

        // when
        RuntimeEvidenceAttestationException failure = attestFailure(attestor, "development");

        // then
        assertThat(failure.code()).isEqualTo(ControlledAgentRuntimeAttestor.DEVELOPMENT_VERSION_FORBIDDEN);
    }

    @Test
    void systemLoaderIsDescribedFromTheMeasuredSdkClassLiteral() throws IOException {
        // given
        Path agent = controlledAgent("extension".getBytes(StandardCharsets.UTF_8), null);
        ClassLoader sdkLoader = ClassLoader.getSystemClassLoader();
        ControlledAgentRuntimeAttestor attestor = ControlledAgentRuntimeAttestor.forTesting(environment(
                sha256(agent), agent, isolatedLoader(), sdkLoader));

        // when
        ExtensionRuntimeEvidence evidence = attestor.attest(VERSION);

        // then
        assertThat(evidence.sdkDefiningLoader()).isEqualTo(loaderDescription(sdkLoader));
    }

    @Test
    void packagedExtensionDigestMismatchFailsClosed() throws IOException {
        // given
        Path agent = controlledAgent(
                "extension".getBytes(StandardCharsets.UTF_8),
                "0".repeat(64)
        );
        ControlledAgentRuntimeAttestor attestor = ControlledAgentRuntimeAttestor.forTesting(environment(
                sha256(agent), agent, isolatedLoader(), isolatedLoader()));

        // when
        RuntimeEvidenceAttestationException failure = attestFailure(attestor, VERSION);

        // then
        assertThat(failure.code()).isEqualTo(ControlledAgentRuntimeAttestor.DISTRIBUTION_MISMATCH);
    }

    private Path controlledAgent(byte[] extension, String declaredExtensionSha256) throws IOException {
        Properties identity = new Properties();
        identity.setProperty("format.version", "1");
        identity.setProperty("distribution.group", "space.br1440.platform.starters.tracing");
        identity.setProperty("distribution.artifact", "platform-tracing-javaagent");
        identity.setProperty("distribution.version", VERSION);
        identity.setProperty(
                "upstream.coordinate",
                "io.opentelemetry.javaagent:opentelemetry-javaagent:2.30.0"
        );
        identity.setProperty(
                "upstream.sha256",
                PinnedOtelJavaAgentAcpProviders.OTEL_JAVA_AGENT_SHA256
        );
        identity.setProperty(
                "extension.path",
                "extensions/platform-tracing-otel-javaagent-extension.jar"
        );
        identity.setProperty(
                "extension.sha256",
                declaredExtensionSha256 == null ? sha256(extension) : declaredExtensionSha256
        );
        identity.setProperty("minimum.java", "21");

        ByteArrayOutputStream identityBytes = new ByteArrayOutputStream();
        identity.store(identityBytes, null);
        Path agent = temporaryDirectory.resolve("controlled-agent-" + System.nanoTime() + ".jar");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(agent))) {
            writeEntry(output, "extensions/platform-tracing-otel-javaagent-extension.jar", extension);
            writeEntry(
                    output,
                    "META-INF/platform-tracing/javaagent-distribution.properties",
                    identityBytes.toByteArray()
            );
        }
        return agent;
    }

    private static RuntimeEvidenceAttestationException attestFailure(
            ControlledAgentRuntimeAttestor attestor,
            String version
    ) {
        Throwable failure = catchThrowable(() -> attestor.attest(version));
        assertThat(failure).isInstanceOf(RuntimeEvidenceAttestationException.class);
        return (RuntimeEvidenceAttestationException) failure;
    }

    private static void writeEntry(ZipOutputStream output, String name, byte[] bytes) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    private static ControlledAgentRuntimeAttestor.RuntimeEnvironment environment(
            String expectedSha256,
            Path agent,
            ClassLoader extensionLoader,
            ClassLoader sdkLoader
    ) {
        return new TestRuntimeEnvironment(
                expectedSha256,
                List.of("-javaagent:" + agent),
                extensionLoader,
                sdkLoader
        );
    }

    private static ClassLoader isolatedLoader() {
        return new ClassLoader(null) {
        };
    }

    private static String loaderDescription(ClassLoader loader) {
        if (loader == null) {
            return BOOTSTRAP_LOADER_LABEL;
        }
        String name = loader.getName();
        String stableName = (name == null || name.isBlank()) ? loader.getClass().getName() : name;
        return stableName + "@" + Integer.toHexString(System.identityHashCode(loader));
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return sha256(input);
        }
    }

    private static String sha256(byte[] bytes) {
        MessageDigest digest = newDigest();
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private static String sha256(InputStream input) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private record TestRuntimeEnvironment(String expectedSha256,
                                          List<String> inputArguments,
                                          ClassLoader extensionDefiningLoader,
                                          ClassLoader sdkDefiningLoader)
            implements ControlledAgentRuntimeAttestor.RuntimeEnvironment {

        @Override
        public String systemProperty(String name) {
            if (!ExtensionPropertyNames.CONTROLLED_AGENT_EXPECTED_SHA256.equals(name)) {
                throw new IllegalArgumentException("Unexpected system property: " + name);
            }
            return expectedSha256;
        }
    }
}
