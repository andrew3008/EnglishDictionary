package space.br1440.platform.tracing.autoconfigure.actuator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import space.br1440.platform.tracing.autoconfigure.diagnostics.ManualTracingDiagnostics;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.core.NoOpPlatformTracing;
import space.br1440.platform.tracing.core.impl.NoOpTracingImplementation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Тест секции {@code processors} в выводе {@link TracingActuatorEndpoint#tracing()}.
 */
class TracingActuatorEndpointProcessorErrorsTest {

    private TracingProperties properties;
    private PlatformTracingJmxClient jmxClient;
    private TracingActuatorEndpoint endpoint;

    @BeforeEach
    void setUp() {
        properties = new TracingProperties();
        jmxClient = Mockito.mock(PlatformTracingJmxClient.class);
        when(jmxClient.isAvailable()).thenReturn(true);
        when(jmxClient.getCurrentRatio()).thenReturn(Optional.of(0.1d));
        endpoint = new TracingActuatorEndpoint(
                NoOpPlatformTracing.INSTANCE,
                properties,
                jmxClient,
                new ManualTracingDiagnostics(NoOpTracingImplementation.noop()));
    }

    @Test
    void processorsSectionPresentEvenWhenAgentMissing() {
        when(jmxClient.isAvailable()).thenReturn(false);
        when(jmxClient.getProcessorErrorsTotal()).thenReturn(Optional.empty());
        when(jmxClient.getProcessorErrorsByName()).thenReturn(java.util.Collections.emptyMap());

        Map<String, Object> info = endpoint.tracing();
        assertThat(info).containsKey("processors");

        @SuppressWarnings("unchecked")
        Map<String, Object> processors = (Map<String, Object>) info.get("processors");
        assertThat(processors).containsEntry("errorsTotal", null);
        assertThat(processors).containsEntry("errorsByName", java.util.Collections.emptyMap());
    }

    @Test
    void processorsSectionAggregatesErrorsByNameAndTotalFromJmx() {
        Map<String, Long> byName = new LinkedHashMap<>();
        byName.put("EnrichingSpanProcessor", 2L);
        byName.put("ScrubbingSpanProcessor", 5L);
        byName.put("ValidatingSpanProcessor", 0L);
        byName.put("SpanWatchdogProcessor", 1L);

        when(jmxClient.getProcessorErrorsTotal()).thenReturn(Optional.of(8L));
        when(jmxClient.getProcessorErrorsByName()).thenReturn(byName);

        Map<String, Object> info = endpoint.tracing();

        @SuppressWarnings("unchecked")
        Map<String, Object> processors = (Map<String, Object>) info.get("processors");

        assertThat(processors).containsEntry("errorsTotal", 8L);

        @SuppressWarnings("unchecked")
        Map<String, Long> actualByName = (Map<String, Long>) processors.get("errorsByName");
        assertThat(actualByName).containsAllEntriesOf(byName);
    }

    @Test
    void processorsErrorsByNameNeverNull() {
        when(jmxClient.getProcessorErrorsTotal()).thenReturn(Optional.empty());
        when(jmxClient.getProcessorErrorsByName()).thenReturn(java.util.Collections.emptyMap());

        Map<String, Object> info = endpoint.tracing();
        @SuppressWarnings("unchecked")
        Map<String, Object> processors = (Map<String, Object>) info.get("processors");

        assertThat(processors.get("errorsByName")).isInstanceOf(Map.class);
    }
}
