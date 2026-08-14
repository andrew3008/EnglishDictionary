package space.br1440.platform.tracing.autoconfigure.control.jmx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;
import javax.management.MBeanException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.ExpectedSettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsContractViolationException;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliverySource;

class PrivateJmxTracingSettingsClientMBeanExceptionTest {

    private MBeanServer server;
    private ObjectName writerName;
    private PrivateJmxTracingSettingsClient client;

    @BeforeEach
    void setUp() throws Exception {
        String runtimeInstanceId = UUID.randomUUID().toString();
        server = mock(MBeanServer.class);
        writerName = new ObjectName(PrivateControlLocator.WRITER_NAME);
        PrivateControlLocator locator = mock(PrivateControlLocator.class);
        when(locator.locate()).thenReturn(new PrivateControlLocator.LocatedPrivateControl(
                server,
                writerName,
                runtimeInstanceId));
        client = new PrivateJmxTracingSettingsClient(locator);
        client.initialize();
    }

    @Test
    void contractCauseFromAgentIsEscalatedWithoutRetry() throws Exception {
        IllegalStateException contractFailure = new IllegalStateException("response encoding failed");
        when(server.invoke(
                eq(writerName),
                eq("applySnapshot"),
                any(Object[].class),
                any(String[].class)))
                .thenThrow(new MBeanException(contractFailure));

        assertThatThrownBy(() -> client.applySnapshot(
                expectedBase(),
                settings(),
                TracingSettingsDeliverySource.CONFIG_SERVER))
                .isInstanceOf(TracingSettingsContractViolationException.class)
                .hasCause(contractFailure);
        verify(server, times(1)).invoke(
                eq(writerName),
                eq("applySnapshot"),
                any(Object[].class),
                any(String[].class));
    }

    @Test
    void nonContractCauseFromAgentReturnsFailedWithoutRetry() throws Exception {
        Exception transportFailure = new Exception("response transport failed");
        when(server.invoke(
                eq(writerName),
                eq("applySnapshot"),
                any(Object[].class),
                any(String[].class)))
                .thenThrow(new MBeanException(transportFailure));

        TracingSettingsDeliveryResult result = client.applySnapshot(
                expectedBase(),
                settings(),
                TracingSettingsDeliverySource.CONFIG_SERVER);

        assertThat(result).isEqualTo(TracingSettingsDeliveryResult.FAILED);
        verify(server, times(1)).invoke(
                eq(writerName),
                eq("applySnapshot"),
                any(Object[].class),
                any(String[].class));
    }

    private ExpectedSettingsRevision expectedBase() {
        return new ExpectedSettingsRevision(
                UUID.randomUUID().toString(),
                0L);
    }

    private static CompleteTracingSettings settings() {
        return CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(0.5d)
                .samplingRouteRatios(Map.of())
                .validationEnabled(true)
                .validationStrict(false)
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO")
                .build();
    }
}
