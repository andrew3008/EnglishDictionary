package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static space.br1440.platform.tracing.otel.javaagent.bootstrap.AttestationFailures.failure;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;

import io.opentelemetry.sdk.OpenTelemetrySdk;

/**
 * Собирает runtime-доказательства контролируемого агента: дистрибутив и описания
 * определяющих загрузчиков.
 *
 * <p>После совпадения SHA-256 JAR identity не сверяется повторно: содержимое уже
 * зафиксировано сборкой и pin'ом. Из identity читается только версия upstream.</p>
 */
final class ControlledAgentRuntimeAttestor implements RuntimeEvidenceAttestor {

    /** Метка null-загрузчика в описании топологии; понятием wire-контракта не является. */
    private static final String BOOTSTRAP_LOADER_LABEL = "bootstrap";
    private static final String UPSTREAM_COORDINATE_PREFIX =
            "io.opentelemetry.javaagent:opentelemetry-javaagent:";
    private static final String UPSTREAM_COORDINATE_KEY = "upstream.coordinate";

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
        ControlledAgentArtifact artifact = ControlledAgentLocator.locate(environment.inputArguments());
        String actualSha256 = AgentDistributionHasher.sha256(
                artifact.path(),
                AttestationFailureCode.DISTRIBUTION_MISMATCH,
                "Unable to calculate controlled agent SHA-256");
        if (!expectedSha256.equals(actualSha256)) {
            throw failure(
                    AttestationFailureCode.DISTRIBUTION_MISMATCH,
                    "Controlled agent SHA-256 does not match the externally supplied expected digest"
            );
        }

        String upstreamVersion = readUpstreamVersion(artifact.identity());

        return new ExtensionRuntimeEvidence(
                actualSha256,
                upstreamVersion,
                normalizedExtensionVersion,
                describeLoader(environment.extensionDefiningLoader()),
                describeLoader(environment.sdkDefiningLoader())
        );
    }

    private static String readUpstreamVersion(Properties identity) {
        String upstreamCoordinate = identity.getProperty(UPSTREAM_COORDINATE_KEY);
        if (upstreamCoordinate == null
                || !upstreamCoordinate.startsWith(UPSTREAM_COORDINATE_PREFIX)
                || upstreamCoordinate.length() == UPSTREAM_COORDINATE_PREFIX.length()) {
            throw failure(
                    AttestationFailureCode.DISTRIBUTION_MISMATCH,
                    "Controlled agent upstream coordinate is invalid");
        }
        return upstreamCoordinate.substring(UPSTREAM_COORDINATE_PREFIX.length());
    }

    private static String requireReleaseVersion(String extensionVersion) {
        if (extensionVersion == null || extensionVersion.isBlank()
                || "development".equalsIgnoreCase(extensionVersion.trim())) {
            throw failure(
                    AttestationFailureCode.DEVELOPMENT_VERSION_FORBIDDEN,
                    "Extension version development is forbidden in the production readiness profile"
            );
        }
        return extensionVersion.trim();
    }

    private String requireExpectedDistributionSha256() {
        String expected = environment.systemProperty(ExtensionPropertyNames.CONTROLLED_AGENT_EXPECTED_SHA256);
        if (expected == null || expected.isBlank()) {
            throw failure(
                    AttestationFailureCode.DISTRIBUTION_EVIDENCE_MISSING,
                    "Expected controlled agent SHA-256 system property is missing: "
                            + ExtensionPropertyNames.CONTROLLED_AGENT_EXPECTED_SHA256
            );
        }
        String normalized = expected.trim().toLowerCase(Locale.ROOT);
        if (!AgentDistributionHasher.isSha256Digest(normalized)) {
            throw failure(
                    AttestationFailureCode.DISTRIBUTION_EVIDENCE_MISSING,
                    "Expected controlled agent SHA-256 system property is not a 64-character hexadecimal digest"
            );
        }
        return normalized;
    }

    /**
     * Описывает определяющий загрузчик. Метод ничего не аттестует: он фиксирует измеренный факт,
     * а решение об изоляции планов принимает Spring, сравнивая описания между собой.
     */
    private static String describeLoader(ClassLoader loader) {
        if (loader == null) {
            return BOOTSTRAP_LOADER_LABEL;
        }
        String name = loader.getName();
        String stableName = (name == null || name.isBlank()) ? loader.getClass().getName() : name;
        return stableName + "@" + Integer.toHexString(System.identityHashCode(loader));
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
}
