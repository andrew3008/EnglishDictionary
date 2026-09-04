package space.br1440.platform.tracing.autoconfigure.control.configserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import space.br1440.platform.tracing.autoconfigure.control.jmx.PlatformJmxTracingSettingsClient;
import space.br1440.platform.tracing.autoconfigure.support.FakeAgentPlatformJmxPlane;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class SecretCanaryCoalescerTest {

    private static final String CANARY_ROUTE = "/canary/secret/z9Qx7mK2vL4pR8nW";
    private static final String CANARY_TOKEN = "CANARY_TOKEN_z9Qx7mK2vL4pR8nW";

    @Test
    void runtimeManifestFailureLog_doesNotEchoRouteOrTokenCanary() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(TracingSettingsRefreshCoalescer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try (FakeAgentPlatformJmxPlane agent = FakeAgentPlatformJmxPlane.compliant("agent-" + UUID.randomUUID(), 0L)) {
            PlatformJmxTracingSettingsClient client = agent.client();
            MockEnvironment env = authoritativeEnv();
            env.getPropertySources().addFirst(new MapPropertySource(
                    "configserver:canary-route",
                    Map.of("platform.tracing.policy.sampling.route-ratios." + CANARY_ROUTE,
                            CANARY_TOKEN)));
            ConfigServerSettingsManifestBuilder builder = new ConfigServerSettingsManifestBuilder(
                    env, new ConfigServerSettingsProvenanceClassifier(env));
            TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                    builder, client, client, Runnable::run);

            coalescer.onRefreshEvent();

            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (a, b) -> a + "\n" + b);
            assertThat(logs).contains("Config Server manifest build failed");
            assertThat(logs)
                    .as("the rejection under test is the value parse failure, not a provenance rejection")
                    .contains("Invalid double value for a sampling.route-ratios entry");
            assertThat(logs).doesNotContain(CANARY_ROUTE);
            assertThat(logs).doesNotContain(CANARY_TOKEN);
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static MockEnvironment authoritativeEnv() {
        MockEnvironment env = new MockEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource(
                "configserver:canary",
                Map.of(
                        "platform.tracing.policy.sampling.enabled", "true",
                        "platform.tracing.policy.sampling.ratio", "1.0",
                        "platform.tracing.policy.validation.enabled", "true",
                        "platform.tracing.policy.validation.strict", "false",
                        "platform.tracing.policy.exporter.enabled", "true",
                        "platform.tracing.policy.propagation.trace-control-headers.enabled", "true",
                        "platform.tracing.policy.diagnostics.log-level", "INFO"
                )));
        return env;
    }
}
