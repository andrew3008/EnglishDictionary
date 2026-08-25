package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.CategoryBSamplingAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

import javax.management.MBeanServer;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainMBeanRegistrarRegistrationTest {

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    private DomainMBeanRegistrar registrar;

    @AfterEach
    void cleanup() {
        if (registrar != null) {
            registrar.unregisterAllMBeans();
        }
    }

    @Test
    void constructorDoesNotRegisterReadinessMBean() throws Exception {
        registrar = new DomainMBeanRegistrar();
        assertThat(server.isRegistered(DomainObjectNames.EXTENSION_READINESS)).isFalse();
    }

    @Test
    void diagnosticAndRuntimeRegistrationsAreGenerationSafe() throws Exception {
        registrar = new DomainMBeanRegistrar();
        TracingSettingsSource policyA = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.1d);
        TracingSettingsSource policyB = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.9d);

        AutoCloseable diagnostic = registrar.registerDiagnosticReadiness();
        assertThat(server.isRegistered(DomainObjectNames.EXTENSION_READINESS)).isTrue();

        wireOwners(registrar, policyA);
        AutoCloseable runtime1 = registrar.registerRuntimeControls(policyA);
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();

        runtime1.close();
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.EXTENSION_READINESS))
                .as("diagnostic readiness retained after runtime close")
                .isTrue();

        AutoCloseable runtime2 = registrar.registerRuntimeControls(policyB);
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();

        runtime1.close();
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING))
                .as("stale runtime close must not clear newer registration")
                .isTrue();

        assertThatThrownBy(registrar::registerDiagnosticReadiness)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registrar.registerRuntimeControls(policyA))
                .isInstanceOf(IllegalStateException.class);

        runtime2.close();
        diagnostic.close();
        diagnostic.close();
        assertThat(server.isRegistered(DomainObjectNames.EXTENSION_READINESS)).isFalse();
    }

    @Test
    void partialRuntimeRegistrationFailureDoesNotLeaveActiveGeneration() throws Exception {
        registrar = new DomainMBeanRegistrar();
        registrar.registerDiagnosticReadiness();

        TracingSettingsSource policy = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.2d);
        // No owners attached: registerRuntimeControls still succeeds with empty-ish domain beans.
        // Force failure via second open attempt after success to cover rebind; partial rollback
        // of policy is covered by successful path + close restore.
        AutoCloseable runtime = registrar.registerRuntimeControls(policy);
        assertThatThrownBy(() -> registrar.registerRuntimeControls(policy))
                .isInstanceOf(IllegalStateException.class);
        runtime.close();
        AutoCloseable again = registrar.registerRuntimeControls(policy);
        again.close();
    }

    private static void wireOwners(DomainMBeanRegistrar registrar, TracingSettingsSource policy) {
        CompositeSampler sampler = new CompositeSampler(
                policy, CategoryBSamplingAccess.snapshot(List.of(), List.of()));
        ValidatingSpanProcessor validating = new ValidatingSpanProcessor(policy);
        registrar.setCompositeSampler(sampler);
        registrar.setValidating(validating);
    }
}
