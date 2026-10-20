package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures.boundSampling;
import static space.br1440.platform.tracing.otel.javaagent.jmx.registration.JmxRegistrationTestFixtures.initializingReadinessProjection;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.CategoryBSamplingAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

@SuppressWarnings("resource")
class PlatformTracingMBeanRegistrarRegistrationTest {

    private final MBeanServer server = MBeanServerFactory.newMBeanServer();
    private final List<AutoCloseable> registrations = new ArrayList<>();
    private PlatformTracingMBeanRegistrar registrar;

    @AfterEach
    void cleanup() {
        for (int index = registrations.size() - 1; index >= 0; index--) {
            try {
                registrations.get(index).close();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void constructorDoesNotRegisterReadinessMBean() {
        // given
        MBeanServer platformServer = ManagementFactory.getPlatformMBeanServer();
        boolean registeredBefore =
                platformServer.isRegistered(DomainObjectNames.EXTENSION_READINESS);

        // when
        registrar = new PlatformTracingMBeanRegistrar(AgentInstanceId.random());

        // then
        assertThat(platformServer.isRegistered(DomainObjectNames.EXTENSION_READINESS))
                .isEqualTo(registeredBefore);
    }

    @Test
    void diagnosticAndRuntimeRegistrationsAreGenerationSafe() throws Exception {
        // given
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        registrar = new PlatformTracingMBeanRegistrar(server, agentInstanceId);
        TracingSettingsSource policyA = boundSampling(agentInstanceId, true, Map.of(), 0.1d);
        TracingSettingsSource policyB = boundSampling(agentInstanceId, true, Map.of(), 0.9d);

        AutoCloseable diagnostic = trackRegistration(
                registrar.registerDiagnosticReadiness(initializingReadinessProjection(agentInstanceId)));
        assertThat(server.isRegistered(DomainObjectNames.EXTENSION_READINESS)).isTrue();

        wireOwners(registrar, policyA);
        // when
        AutoCloseable runtime1 = trackRegistration(registrar.registerRuntimeControls(policyA));
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();

        runtime1.close();
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.EXTENSION_READINESS))
                .as("diagnostic readiness retained after runtime close")
                .isTrue();

        AutoCloseable runtime2 = trackRegistration(registrar.registerRuntimeControls(policyB));
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();

        runtime1.close();
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING))
                .as("stale runtime close must not clear newer registration")
                .isTrue();

        // then
        assertThatThrownBy(() -> registrar.registerDiagnosticReadiness(
                initializingReadinessProjection(agentInstanceId)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registrar.registerRuntimeControls(policyA))
                .isInstanceOf(IllegalStateException.class);

        runtime2.close();
        diagnostic.close();
        diagnostic.close();
        assertThat(server.isRegistered(DomainObjectNames.EXTENSION_READINESS)).isFalse();
    }

    @Test
    void closedRuntimeGenerationAllowsNewRegistrationWithoutOwners() throws Exception {
        // given
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        registrar = new PlatformTracingMBeanRegistrar(server, agentInstanceId);
        trackRegistration(registrar.registerDiagnosticReadiness(
                initializingReadinessProjection(agentInstanceId)));

        TracingSettingsSource policy = boundSampling(agentInstanceId, true, Map.of(), 0.2d);
        AutoCloseable runtime = trackRegistration(registrar.registerRuntimeControls(policy));
        // when/then
        assertThatThrownBy(() -> registrar.registerRuntimeControls(policy))
                .isInstanceOf(IllegalStateException.class);
        runtime.close();

        AutoCloseable again = trackRegistration(registrar.registerRuntimeControls(policy));
        again.close();
    }

    private <T extends AutoCloseable> T trackRegistration(T registration) {
        registrations.add(registration);
        return registration;
    }

    private static void wireOwners(PlatformTracingMBeanRegistrar registrar, TracingSettingsSource policy) {
        CompositeSampler sampler = new CompositeSampler(policy, CategoryBSamplingAccess.snapshot(List.of(), List.of()));
        ValidatingSpanProcessor validating = new ValidatingSpanProcessor(policy);
        registrar.setCompositeSampler(sampler);
        registrar.setValidating(validating);
    }
}
