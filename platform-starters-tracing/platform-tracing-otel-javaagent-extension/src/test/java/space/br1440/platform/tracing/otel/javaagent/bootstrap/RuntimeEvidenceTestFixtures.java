package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxAssessorTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.PlatformScrubberFactory;

final class RuntimeEvidenceTestFixtures {

    private static final String TEST_AGENT_SHA256 = "a".repeat(64);

    private RuntimeEvidenceTestFixtures() {
    }

    static RuntimeEvidenceAttestor trustedAttestor() {
        return RuntimeEvidenceTestFixtures::evidence;
    }

    static ExtensionRuntimeEvidence evidence(String extensionVersion) {
        return new ExtensionRuntimeEvidence(
                TEST_AGENT_SHA256,
                "2.30.0",
                extensionVersion,
                "test-extension-loader@1",
                "test-sdk-loader@2"
        );
    }

    static BootstrapCoordinator coordinator() {
        return coordinator(new PlatformScrubberFactory());
    }

    static BootstrapCoordinator coordinator(PlatformScrubberFactory scrubberFactory) {
        return new BootstrapCoordinator(
                JvmShutdownHookRegistry.INSTANCE,
                scrubberFactory,
                trustedAttestor(),
                ignored -> RemoteJmxAssessorTestFixtures.allowingAssessor());
    }
}
