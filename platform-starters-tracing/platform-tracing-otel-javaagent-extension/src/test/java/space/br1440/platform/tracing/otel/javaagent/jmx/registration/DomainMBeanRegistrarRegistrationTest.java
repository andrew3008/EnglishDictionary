package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static space.br1440.platform.tracing.otel.javaagent.jmx.registration.JmxRegistrationTestFixtures.initializingReadinessProjection;
import static space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxAuthorizationTestFixtures.mutationRegistrationPermit;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.management.MBeanServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.CategoryBSamplingAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

class DomainMBeanRegistrarRegistrationTest {

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    private final List<AutoCloseable> registrations = new ArrayList<>();
    private DomainMBeanRegistrar registrar;

    @AfterEach
    void cleanup() {
        for (int index = registrations.size() - 1; index >= 0; index--) {
            try {
                registrations.get(index).close();
            } catch (Exception ignored) {
                // Последующие handles всё равно должны получить возможность освободить свои MBeans.
            }
        }
    }

    @Test
    void constructorDoesNotRegisterReadinessMBean() throws Exception {
        registrar = new DomainMBeanRegistrar();
        assertThat(server.isRegistered(DomainObjectNames.EXTENSION_READINESS)).isFalse();
    }

    @Test
    void runtimeRegistrationRejectsMissingPermitBeforeAnyMBeanIsRegistered() {
        // given
        registrar = new DomainMBeanRegistrar();
        TracingSettingsSource policy = TracingSettingsSource.isolatedSampling(
                true,
                Map.of(),
                0.5d);

        // when
        // then
        assertThatThrownBy(() -> registrar.registerRuntimeControls(policy, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Mutation registration permit must not be null");
        assertThat(server.isRegistered(DomainObjectNames.SETTINGS_STATUS)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.SETTINGS_MUTATION)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isFalse();
    }

    @Test
    void diagnosticAndRuntimeRegistrationsAreGenerationSafe() throws Exception {
        registrar = new DomainMBeanRegistrar();
        TracingSettingsSource policyA = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.1d);
        TracingSettingsSource policyB = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.9d);

        AutoCloseable diagnostic = trackRegistration(
                registrar.registerDiagnosticReadiness(initializingReadinessProjection()));
        assertThat(server.isRegistered(DomainObjectNames.EXTENSION_READINESS)).isTrue();

        wireOwners(registrar, policyA);
        AutoCloseable runtime1 = trackRegistration(registrar.registerRuntimeControls(
                policyA,
                mutationRegistrationPermit()));
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();

        runtime1.close();
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.EXTENSION_READINESS))
                .as("diagnostic readiness retained after runtime close")
                .isTrue();

        AutoCloseable runtime2 = trackRegistration(registrar.registerRuntimeControls(
                policyB,
                mutationRegistrationPermit()));
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();

        runtime1.close();
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING))
                .as("stale runtime close must not clear newer registration")
                .isTrue();

        assertThatThrownBy(() -> registrar.registerDiagnosticReadiness(initializingReadinessProjection()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registrar.registerRuntimeControls(
                policyA,
                mutationRegistrationPermit()))
                .isInstanceOf(IllegalStateException.class);

        runtime2.close();
        diagnostic.close();
        diagnostic.close();
        assertThat(server.isRegistered(DomainObjectNames.EXTENSION_READINESS)).isFalse();
    }

    @Test
    void closedRuntimeGenerationAllowsNewRegistrationWithoutOwners() throws Exception {
        registrar = new DomainMBeanRegistrar();
        trackRegistration(registrar.registerDiagnosticReadiness(initializingReadinessProjection()));

        TracingSettingsSource policy = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.2d);
        // Владельцы ещё не подключены, поэтому снимки содержат явное отсутствие owner evidence.
        // Повторный вызов проверяет запрет rebind, а close восстанавливает предыдущую policy.
        AutoCloseable runtime = trackRegistration(registrar.registerRuntimeControls(
                policy,
                mutationRegistrationPermit()));
        assertThatThrownBy(() -> registrar.registerRuntimeControls(
                policy,
                mutationRegistrationPermit()))
                .isInstanceOf(IllegalStateException.class);
        runtime.close();
        AutoCloseable again = trackRegistration(registrar.registerRuntimeControls(
                policy,
                mutationRegistrationPermit()));
        again.close();
    }

    private <T extends AutoCloseable> T trackRegistration(T registration) {
        registrations.add(registration);
        return registration;
    }

    private static void wireOwners(DomainMBeanRegistrar registrar, TracingSettingsSource policy) {
        CompositeSampler sampler = new CompositeSampler(
                policy, CategoryBSamplingAccess.snapshot(List.of(), List.of()));
        ValidatingSpanProcessor validating = new ValidatingSpanProcessor(policy);
        registrar.setCompositeSampler(sampler);
        registrar.setValidating(validating);
    }
}
