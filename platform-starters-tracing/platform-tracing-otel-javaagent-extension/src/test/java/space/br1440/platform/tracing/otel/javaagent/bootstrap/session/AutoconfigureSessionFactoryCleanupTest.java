package space.br1440.platform.tracing.otel.javaagent.bootstrap.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;

class AutoconfigureSessionFactoryCleanupTest {

    @Test
    void create_promotes_cleanup_error_over_preparation_failure() {
        // given
        RuntimeException preparationFailure = new IllegalStateException("Preparation failed");
        Error cleanupFailure = new AssertionError("Cleanup failed");
        PlatformScrubberFactory scrubberFactory = mock(PlatformScrubberFactory.class);
        ScrubbingRuntime scrubbingRuntime = mock(ScrubbingRuntime.class);
        when(scrubberFactory.buildRuntime(any(ExtensionConfig.class))).thenReturn(scrubbingRuntime);
        doThrow(cleanupFailure).when(scrubbingRuntime).close();
        AutoconfigureSessionFactory factory = new AutoconfigureSessionFactory(
                scrubberFactory,
                ignored -> {
                    throw preparationFailure;
                }
        );
        AgentInstanceId agentInstanceId = AgentInstanceId.random();

        // when
        Throwable thrown = catchThrowable(() -> {
            try (AutoconfigureSession session = factory.create(
                    DefaultConfigProperties.createFromMap(Map.of("otel.traces.sampler", "always_off")),
                    new PlatformTracingMBeanRegistrar(agentInstanceId),
                    agentInstanceId,
                    evidence()
            )) {
                throw new AssertionError("create returned a session: " + session);
            }
        });

        // then
        assertThat(thrown).isSameAs(cleanupFailure);
        assertThat(thrown.getSuppressed()).containsExactly(preparationFailure);
        verify(scrubbingRuntime).close();
    }

    @Test
    void create_does_not_self_suppress_shared_failure() {
        // given
        Error sharedFailure = new AssertionError("Shared failure");
        PlatformScrubberFactory scrubberFactory = mock(PlatformScrubberFactory.class);
        ScrubbingRuntime scrubbingRuntime = mock(ScrubbingRuntime.class);
        when(scrubberFactory.buildRuntime(any(ExtensionConfig.class))).thenReturn(scrubbingRuntime);
        doThrow(sharedFailure).when(scrubbingRuntime).close();
        AutoconfigureSessionFactory factory = new AutoconfigureSessionFactory(
                scrubberFactory,
                ignored -> {
                    throw sharedFailure;
                }
        );
        AgentInstanceId agentInstanceId = AgentInstanceId.random();

        // when
        Throwable thrown = catchThrowable(() -> {
            try (AutoconfigureSession session = factory.create(
                    DefaultConfigProperties.createFromMap(Map.of("otel.traces.sampler", "always_off")),
                    new PlatformTracingMBeanRegistrar(agentInstanceId),
                    agentInstanceId,
                    evidence()
            )) {
                throw new AssertionError("create returned a session: " + session);
            }
        });

        // then
        assertThat(thrown).isSameAs(sharedFailure);
        assertThat(thrown.getSuppressed()).isEmpty();
        verify(scrubbingRuntime).close();
    }

    private static ExtensionRuntimeEvidence evidence() {
        return new ExtensionRuntimeEvidence(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "2.32.0",
                "test-extension",
                "extension-loader",
                "sdk-loader"
        );
    }
}
