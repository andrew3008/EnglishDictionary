package space.br1440.platform.tracing.autoconfigure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;

import space.br1440.platform.tracing.autoconfigure.control.authority.TracingConfigurationAuthorityAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет корректный bind вложенных свойств {@link TracingProperties} из {@code application.properties}.
 */
@ExtendWith(OutputCaptureExtension.class)
class TracingPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void deletedCategoryASpringMirrorsAreNotBound() {
        // sampling / validation / log-level removed from TracingProperties; values must not bind.
        contextRunner
                .withPropertyValues(
                        "platform.tracing.sampling.enabled=false",
                        "platform.tracing.sampling.ratio=0.42",
                        "platform.tracing.validation.enabled=false",
                        "platform.tracing.validation.strict=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    TracingProperties properties = ctx.getBean(TracingProperties.class);
                    assertThat(properties.getSemantic()).isNotNull();
                });
    }

    /**
     * {@code platform.tracing.log-level} has no Agent-bootstrap replacement (deleted
     * {@code OtelAgentLogLevel}); the startup guard fails fast instead of silently ignoring it.
     */
    @Test
    void obsoleteSpringOnlyLogLevelKeyFailsStartupFast() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(TracingConfigurationAuthorityAutoConfiguration.class))
                .withPropertyValues(
                        "platform.tracing.configuration.authority=HELM",
                        "platform.environment=LOCAL",
                        "platform.tracing.log-level=warn")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    Throwable failure = ctx.getStartupFailure();
                    assertThat(failure).isNotNull();
                    String message = failure.getMessage()
                            + (failure.getCause() == null ? "" : failure.getCause().getMessage());
                    assertThat(message).contains("platform.tracing.log-level");
                });
    }

    /**
     * Agent-bootstrap KEEP keys (sampling/validation) may legally appear in the shared Spring
     * Environment — the Agent extension reads them directly — so startup must still succeed, but
     * the guard must make their presence explicit instead of a silent no-op success claim.
     */
    @Test
    void agentBootstrapKeepKeysInSpringEnvironmentSucceedButAreNotSilent(CapturedOutput output) {
        contextRunner
                .withConfiguration(AutoConfigurations.of(TracingConfigurationAuthorityAutoConfiguration.class))
                .withPropertyValues(
                        "platform.tracing.configuration.authority=HELM",
                        "platform.environment=LOCAL",
                        "platform.tracing.sampling.enabled=false",
                        "platform.tracing.sampling.ratio=0.42",
                        "platform.tracing.validation.enabled=false",
                        "platform.tracing.validation.strict=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(TracingBootstrapSettings.class).mode()).isEqualTo(TracingMode.AGENT);
                });
        assertThat(output).contains("platform.tracing.sampling.enabled");
        assertThat(output).contains("platform.tracing.sampling.ratio");
        assertThat(output).contains("platform.tracing.validation.enabled");
        assertThat(output).contains("platform.tracing.validation.strict");
    }

    @Test
    void bindsRequestIdHeader() {
        contextRunner
                .withPropertyValues(
                        "platform.tracing.request-id-header.enabled=false",
                        "platform.tracing.request-id-header.name=X-Custom-Request-Id")
                .run(ctx -> {
                    TracingProperties properties = ctx.getBean(TracingProperties.class);
                    assertThat(properties.getRequestIdHeader().isEnabled()).isFalse();
                    assertThat(properties.getRequestIdHeader().getName()).isEqualTo("X-Custom-Request-Id");
                });
    }

    @Configuration
    @EnableConfigurationProperties(TracingProperties.class)
    static class TestConfig {
    }
}
