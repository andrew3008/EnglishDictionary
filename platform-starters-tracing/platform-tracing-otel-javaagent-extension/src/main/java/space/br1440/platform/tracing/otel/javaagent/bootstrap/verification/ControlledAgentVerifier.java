package space.br1440.platform.tracing.otel.javaagent.bootstrap.verification;

import static space.br1440.platform.tracing.otel.javaagent.bootstrap.verification.VerificationFailures.failure;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;

import io.opentelemetry.sdk.OpenTelemetrySdk;

/**
 * Проверяет, что JVM запущена с контролируемым дистрибутивом агента, и собирает
 * сведения о нём для readiness-снимка.
 *
 * <p>Порядок проверки:
 * <ol>
 *   <li>версия расширения — релизная, а не {@code development};</li>
 *   <li>SHA-256 JAR из {@code -javaagent} совпадает с ожидаемой, переданной через
 *       system property (по аналогии с {@code distributionSha256Sum} в Gradle/Maven Wrapper);</li>
 *   <li>upstream-сборка OpenTelemetry Java agent, записанная в JAR, совпадает с той,
 *       для которой составлен список разрешённых customizer-провайдеров.</li>
 * </ol>
 */
final class ControlledAgentVerifier implements ExtensionRuntimeVerifier {

    private static final String DEVELOPMENT_VERSION = "development";
    private static final String UPSTREAM_SHA256_KEY = "upstream.sha256";
    private static final String UPSTREAM_COORDINATE_KEY = "upstream.coordinate";
    private static final String UPSTREAM_COORDINATE_PREFIX = "io.opentelemetry.javaagent:opentelemetry-javaagent:";
    private static final String BOOTSTRAP_LOADER_LABEL = "bootstrap";

    private final RuntimeEnvironment environment;

    ControlledAgentVerifier(RuntimeEnvironment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    static ControlledAgentVerifier forCurrentJvm() {
        return new ControlledAgentVerifier(CurrentJvm.INSTANCE);
    }

    @Override
    public ExtensionRuntimeEvidence verify(String extensionVersion) {
        String releaseVersion = requireReleaseVersion(extensionVersion);
        AgentDistributionDigest expectedDigest = readExpectedDistributionDigest();

        ControlledAgentArtifact artifact = ControlledAgentArtifactLocator.locate(environment.inputArguments());
        AgentDistributionDigest actualDigest = verifyDistributionDigest(artifact.path(), expectedDigest);
        verifyUpstreamMatchesProviderAllowlist(artifact.identity());

        return new ExtensionRuntimeEvidence(
                actualDigest.hex(),
                readUpstreamVersion(artifact.identity()),
                releaseVersion,
                describeLoader(environment.extensionDefiningLoader()),
                describeLoader(environment.sdkDefiningLoader())
        );
    }

    private static String requireReleaseVersion(String extensionVersion) {
        if (extensionVersion == null || extensionVersion.isBlank()) {
            throw failure(
                    VerificationFailureCode.DISTRIBUTION_EVIDENCE_MISSING,
                    "Extension version is not set"
            );
        }

        String version = extensionVersion.trim();
        if (DEVELOPMENT_VERSION.equalsIgnoreCase(version)) {
            throw failure(
                    VerificationFailureCode.DEVELOPMENT_VERSION_FORBIDDEN,
                    "Extension version 'development' is forbidden in the production readiness profile"
            );
        }
        return version;
    }

    private AgentDistributionDigest readExpectedDistributionDigest() {
        String property = ExtensionPropertyNames.CONTROLLED_AGENT_EXPECTED_SHA256;
        String value = environment.systemProperty(property);
        if (value == null || value.isBlank()) {
            throw failure(
                    VerificationFailureCode.DISTRIBUTION_EVIDENCE_MISSING,
                    "Expected controlled agent SHA-256 is not set: " + property
            );
        }

        return AgentDistributionDigest.parse(value.trim()).orElseThrow(() -> failure(
                VerificationFailureCode.DISTRIBUTION_EVIDENCE_MISSING,
                "Expected controlled agent SHA-256 is not a 64-character hex value: " + property
        ));
    }

    private static AgentDistributionDigest verifyDistributionDigest(Path artifactPath, AgentDistributionDigest expected) {
        AgentDistributionDigest actual;
        try {
            actual = AgentDistributionDigest.of(artifactPath);
        } catch (IOException exception) {
            throw failure(
                    VerificationFailureCode.DISTRIBUTION_EVIDENCE_MISSING,
                    "Unable to read controlled agent JAR to calculate SHA-256",
                    exception
            );
        }

        if (!expected.equals(actual)) {
            throw failure(
                    VerificationFailureCode.DISTRIBUTION_MISMATCH,
                    "Controlled agent SHA-256 does not match the expected value"
            );
        }

        return actual;
    }

    private static void verifyUpstreamMatchesProviderAllowlist(Map<String, String> identity) {
        UpstreamCustomizerProviderAllowlist.Pin pin;
        try {
            pin = UpstreamCustomizerProviderAllowlist.load();
        } catch (IllegalStateException exception) {
            throw failure(
                    VerificationFailureCode.DISTRIBUTION_EVIDENCE_MISSING,
                    exception.getMessage(),
                    exception
            );
        }

        AgentDistributionDigest recordedUpstream = AgentDistributionDigest.parse(identity.get(UPSTREAM_SHA256_KEY))
                .orElseThrow(() -> failure(
                        VerificationFailureCode.DISTRIBUTION_EVIDENCE_MISSING,
                        "Controlled agent identity has no valid " + UPSTREAM_SHA256_KEY
                ));
        if (!pin.otelJavaAgentSha256().equals(recordedUpstream.hex())) {
            throw failure(
                    VerificationFailureCode.DISTRIBUTION_MISMATCH,
                    "Upstream OpenTelemetry Java agent does not match the customizer provider allowlist"
            );
        }
    }

    private static String readUpstreamVersion(Map<String, String> identity) {
        String coordinate = identity.get(UPSTREAM_COORDINATE_KEY);
        if (coordinate == null
                || !coordinate.startsWith(UPSTREAM_COORDINATE_PREFIX)
                || coordinate.length() == UPSTREAM_COORDINATE_PREFIX.length()) {
            throw failure(
                    VerificationFailureCode.DISTRIBUTION_MISMATCH,
                    "Controlled agent identity has an invalid " + UPSTREAM_COORDINATE_KEY
            );
        }
        return coordinate.substring(UPSTREAM_COORDINATE_PREFIX.length());
    }

    /** Диагностическое описание загрузчика; уникально в пределах одного запуска JVM. */
    private static String describeLoader(ClassLoader loader) {
        if (loader == null) {
            return BOOTSTRAP_LOADER_LABEL;
        }

        String name = loader.getName();
        String loaderName = (name == null || name.isBlank()) ? loader.getClass().getName() : name;
        return loaderName + "@" + Integer.toHexString(System.identityHashCode(loader));
    }

    interface RuntimeEnvironment {

        String systemProperty(String name);

        List<String> inputArguments();

        ClassLoader extensionDefiningLoader();

        ClassLoader sdkDefiningLoader();
    }

    private enum CurrentJvm implements RuntimeEnvironment {
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
            return ControlledAgentVerifier.class.getClassLoader();
        }

        @Override
        public ClassLoader sdkDefiningLoader() {
            return OpenTelemetrySdk.class.getClassLoader();
        }
    }
}
