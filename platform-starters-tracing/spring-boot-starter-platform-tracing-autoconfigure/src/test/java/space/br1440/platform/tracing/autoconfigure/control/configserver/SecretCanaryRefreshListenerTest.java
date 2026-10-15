package space.br1440.platform.tracing.autoconfigure.control.configserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsWriter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class SecretCanaryRefreshListenerTest {

    private static final String CANARY_ROUTE = "/canary/secret/z9Qx7mK2vL4pR8nW";
    private static final String CANARY_TOKEN = "CANARY_TOKEN_z9Qx7mK2vL4pR8nW";
    private static final String ROUTE_KEY =
            "platform.tracing.policy.sampling.route-ratios." + CANARY_ROUTE;

    @Test
    void runtimeManifestFailureLogIncludesKeyAndOmitsValue() {
        // given
        Logger logger = (Logger) LoggerFactory.getLogger(ConfigServerTracingSettingsAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            MockEnvironment environment = authoritativeEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource(
                    "configserver:canary-route",
                    Map.of(ROUTE_KEY, CANARY_TOKEN)
            ));
            ConfigServerSettingsManifestBuilder manifestBuilder =
                    new ConfigServerSettingsManifestBuilder(environment);
            ConfigServerTracingSettingsSynchronizer synchronizer =
                    new ConfigServerTracingSettingsSynchronizer(
                            manifestBuilder,
                            mock(TracingSettingsWriter.class)
                    );
            ConfigServerTracingSettingsAdapter adapter = new ConfigServerTracingSettingsAdapter(synchronizer);

            // when
            adapter.onEnvironmentChange(new EnvironmentChangeEvent(this, Set.of(ROUTE_KEY)));

            // then
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage())
                        .contains("Config Server manifest rejected locally; LKG retained")
                        .contains(ROUTE_KEY)
                        .contains("not convertible to Double")
                        .doesNotContain(CANARY_TOKEN);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static MockEnvironment authoritativeEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "configserver:canary",
                Map.of(
                        "platform.tracing.policy.sampling.enabled", "true",
                        "platform.tracing.policy.sampling.ratio", "1.0",
                        "platform.tracing.policy.validation.level", "WARNING",
                        "platform.tracing.policy.exporter.enabled", "true",
                        "platform.tracing.policy.propagation.trace-control-headers.enabled", "true",
                        "platform.tracing.policy.diagnostics.log-level", "INFO"
                )
        ));
        return environment;
    }
}
