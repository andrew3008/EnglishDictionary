package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.verification.ExtensionRuntimeVerifier;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxAssessorTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.PlatformScrubberFactory;

import lombok.experimental.UtilityClass;

@UtilityClass
final class RuntimeEvidenceTestFixtures {

    private static final String TEST_AGENT_SHA256 = "a".repeat(64);

    static ExtensionRuntimeVerifier trustedVerifier() {
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
        return coordinator(new PlatformScrubberFactory(), MBeanServerFactory.newMBeanServer());
    }

    static BootstrapCoordinator coordinator(MBeanServer mbeanServer) {
        return coordinator(new PlatformScrubberFactory(), mbeanServer);
    }

    private static BootstrapCoordinator coordinator(PlatformScrubberFactory scrubberFactory,
                                                    MBeanServer mbeanServer) {
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        return new BootstrapCoordinator(
                new BootstrapCoordinatorTestSupport.CapturingShutdownHookRegistry(),
                scrubberFactory,
                trustedVerifier(),
                ignored -> RemoteJmxAssessorTestFixtures.allowingAssessor(),
                new PlatformTracingMBeanRegistrar(mbeanServer, agentInstanceId),
                new DiagnosticReadinessHandle()
        );
    }
}
