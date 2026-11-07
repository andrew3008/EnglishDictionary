package space.br1440.platform.tracing.otel.javaagent.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures.boundSampling;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import com.fasterxml.jackson.databind.JsonNode;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.processor.ClassificationThresholdOrderException;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntimeTestFixtures;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class PlatformSpanProcessorFactoryThresholdRejectionTest {

    private MBeanServer server;
    private PlatformTracingMBeanRegistrar registrar;
    private TracingSettingsSource samplerHolder;

    @BeforeEach
    void setUp() {
        server = MBeanServerFactory.createMBeanServer("space.br1440.platform.tracing.threshold.test");
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        samplerHolder = boundSampling(agentInstanceId, true, Collections.emptyMap(), 0.1d);
        registrar = new PlatformTracingMBeanRegistrar(server, agentInstanceId);
    }

    @AfterEach
    void tearDown() {
        MBeanServerFactory.releaseMBeanServer(server);
    }

    @Test
    void equalThresholdsEmitClassificationThresholdOrder() {
        // given
        PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(
                registrar, ScrubbingRuntimeTestFixtures.disabled());
        Duration threshold = Duration.ofMillis(200);

        try (AgentLogCapture capture = AgentLogCapture.installed()) {
            // when
            Throwable thrown = catchThrowable(() -> factory.registerSpanProcessors(
                    SdkTracerProvider.builder(),
                    new ExtensionConfig(equalThresholds(threshold)),
                    samplerHolder
            ));

            // then
            assertThat(thrown).isInstanceOf(ClassificationThresholdOrderException.class);
            ClassificationThresholdOrderException order = (ClassificationThresholdOrderException) thrown;
            assertThat(order.reason()).isEqualTo(ClassificationThresholdOrderException.REASON);
            assertThat(order.normalThreshold()).isEqualTo(threshold);
            assertThat(order.slowThreshold()).isEqualTo(threshold);

            JsonNode event = capture.jsonEvents().stream()
                    .filter(node -> "PTA-BOOT-008".equals(node.path("event_code").asText()))
                    .findFirst()
                    .orElseThrow();
            assertThat(event.path("fields").path("reason").asText())
                    .isEqualTo(ClassificationThresholdOrderException.REASON);
            assertThat(event.path("fields").path("propertyName").asText())
                    .isEqualTo(ExtensionPropertyNames.CLASSIFICATION_SLOW_THRESHOLD);
            assertThat(event.path("fields").path("failureContext").asText())
                    .isEqualTo("normal=" + threshold + ",slow=" + threshold);
            assertThat(event.path("error_stacktrace").asText()).doesNotContain("strictly greater");
        }
    }

    private static ConfigProperties equalThresholds(Duration threshold) {
        return new ConfigProperties() {
            @Override
            public String getString(String name) {
                return null;
            }

            @Override
            public Boolean getBoolean(String name) {
                return null;
            }

            @Override
            public Integer getInt(String name) {
                return null;
            }

            @Override
            public Long getLong(String name) {
                return null;
            }

            @Override
            public Double getDouble(String name) {
                return null;
            }

            @Override
            public Duration getDuration(String name) {
                if (ExtensionPropertyNames.CLASSIFICATION_NORMAL_THRESHOLD.equals(name)
                        || ExtensionPropertyNames.CLASSIFICATION_SLOW_THRESHOLD.equals(name)) {
                    return threshold;
                }
                return null;
            }

            @Override
            public List<String> getList(String name) {
                return List.of();
            }

            @Override
            public Map<String, String> getMap(String name) {
                return null;
            }
        };
    }
}
