package space.br1440.platform.tracing.autoconfigure.control.jmx;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServer;
import javax.management.ReflectionException;
import javax.management.RuntimeMBeanException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceTestSupport;
import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsWriter;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsContractViolationException;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.autoconfigure.support.ReadinessJmxTestFixtures;
import space.br1440.platform.tracing.autoconfigure.support.SettingsJmxTestFixtures;

final class PlatformJmxTracingSettingsClientMBeanExceptionTest {

    private MBeanServer server;
    private PlatformJmxTracingSettingsClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = mock(MBeanServer.class);

        when(server.getMBeanInfo(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME))
                .thenReturn(ReadinessJmxTestFixtures.metadata());

        when(server.getAttribute(
                JmxWireContract.EXTENSION_READINESS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT))
                .thenReturn(ReadinessJmxTestFixtures.readySnapshot());

        when(server.getMBeanInfo(JmxWireContract.SETTINGS_STATUS_OBJECT_NAME))
                .thenReturn(SettingsJmxTestFixtures.statusInfo());

        when(server.getMBeanInfo(JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME))
                .thenReturn(SettingsJmxTestFixtures.mutationInfo());

        AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard(server);
        AgentReadinessCoherenceTestSupport.pinCurrent(coherenceGuard);
        client = new PlatformJmxTracingSettingsClient(server, coherenceGuard);
        client.initialize();
    }

    @ParameterizedTest(name = "{0} #{index}")
    @MethodSource("invokeFailures")
    void mutationInvokeFailureTerminallyInvalidatesTrustWithoutRetry(JmxClientFailureCode failureCode,
                                                                     Exception invokeFailure,
                                                                     Exception expectedCause,
                                                                     Class<? extends RuntimeException> expectedExceptionType) throws Exception {
        when(server.invoke(
                eq(JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME),
                eq(JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT),
                any(Object[].class),
                any(String[].class)))
                .thenThrow(invokeFailure);

        assertThatThrownBy(() -> client.applySnapshot(
                expectedBase(),
                settings(),
                SettingsWriter.CONFIG_SERVER))
                .isInstanceOf(expectedExceptionType)
                .hasMessageStartingWith(failureCode.token() + ":")
                .hasCause(expectedCause);

        assertThatThrownBy(() -> client.applySnapshot(
                expectedBase(),
                settings(),
                SettingsWriter.CONFIG_SERVER))
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessageContaining(JmxClientFailureCode.TERMINALLY_INVALIDATED.token())
                .hasMessageContaining(failureCode.token());

        verify(server, times(1)).invoke(
                eq(JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME),
                eq(JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT),
                any(Object[].class),
                any(String[].class)
        );
    }

    private static Stream<Arguments> invokeFailures() {
        IllegalStateException providerFailure = new IllegalStateException("response encoding failed");
        InstanceNotFoundException disappeared = new InstanceNotFoundException("removed by test");
        IllegalStateException runtimeProviderFailure = new IllegalStateException("provider raised a runtime failure");
        ReflectionException signatureDrift = new ReflectionException(new NoSuchMethodException("applySnapshot"), "operation is unavailable");
        SecurityException transportDenied = new SecurityException("denied by test policy");
        IllegalStateException unexpectedTransportFailure = new IllegalStateException("simulated transport failure");
        return Stream.of(
                Arguments.of(
                        JmxClientFailureCode.MUTATION_PROVIDER_INVOCATION_FAILED,
                        new MBeanException(providerFailure),
                        providerFailure,
                        TracingConfigurationGuardException.class
                ),

                Arguments.of(
                        JmxClientFailureCode.MUTATION_ENDPOINT_DISAPPEARED,
                        disappeared,
                        disappeared,
                        TracingConfigurationGuardException.class
                ),

                Arguments.of(
                        JmxClientFailureCode.MUTATION_PROVIDER_INVOCATION_FAILED,
                        new RuntimeMBeanException(runtimeProviderFailure, "runtime provider failure"),
                        runtimeProviderFailure,
                        TracingConfigurationGuardException.class
                ),

                Arguments.of(
                        JmxClientFailureCode.MUTATION_SIGNATURE_DRIFT,
                        signatureDrift,
                        signatureDrift,
                        TracingSettingsContractViolationException.class
                ),

                Arguments.of(
                        JmxClientFailureCode.MUTATION_TRANSPORT_SECURITY_FAILED,
                        transportDenied,
                        transportDenied,
                        TracingConfigurationGuardException.class
                ),

                Arguments.of(
                        JmxClientFailureCode.MUTATION_TRANSPORT_FAILED,
                        unexpectedTransportFailure,
                        unexpectedTransportFailure,
                        TracingConfigurationGuardException.class
                )
        );
    }

    private static SettingsRevision expectedBase() {
        return new SettingsRevision(UUID.randomUUID().toString(), 0L);
    }

    private static CompleteTracingSettings settings() {
        return CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(0.5d)
                .samplingRouteRatios(Map.of())
                .validationLevel("WARNING")
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO")
                .build();
    }
}
