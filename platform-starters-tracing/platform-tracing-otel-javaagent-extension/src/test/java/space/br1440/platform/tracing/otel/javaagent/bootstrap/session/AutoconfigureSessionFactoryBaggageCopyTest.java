package space.br1440.platform.tracing.otel.javaagent.bootstrap.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Map;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.configuration.ConfigurationPropertyException;
import space.br1440.platform.tracing.otel.javaagent.configuration.OtelSdkPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.PlatformScrubberFactory;

import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;

class AutoconfigureSessionFactoryBaggageCopyTest {

    @Test
    void create_rejects_non_empty_baggage_attribute_copy() {
        // given
        AutoconfigureSessionFactory factory = new AutoconfigureSessionFactory(
                new PlatformScrubberFactory(),
                AutoconfigureSessionFactory::currentProcessRemoteJmxAssessor
        );
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        String propertyName = OtelSdkPropertyNames.SPAN_ATTRIBUTES_COPY_FROM_BAGGAGE_INCLUDE;

        // when
        Throwable failure = catchThrowable(() -> {
            try (AutoconfigureSession session = factory.create(
                    DefaultConfigProperties.createFromMap(Map.of(
                            "otel.traces.sampler", "always_off",
                            propertyName, "session.id"
                    )),
                    new PlatformTracingMBeanRegistrar(agentInstanceId),
                    agentInstanceId,
                    evidence()
            )) {
                throw new AssertionError("create returned a session: " + session);
            }
        });

        // then
        assertThat(failure).isInstanceOf(ConfigurationPropertyException.class);
        assertThat(((ConfigurationPropertyException) failure).propertyName()).isEqualTo(propertyName);
    }

    private static ExtensionRuntimeEvidence evidence() {
        return new ExtensionRuntimeEvidence(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "2.33.1",
                "test-extension",
                "extension-loader",
                "sdk-loader"
        );
    }
}
