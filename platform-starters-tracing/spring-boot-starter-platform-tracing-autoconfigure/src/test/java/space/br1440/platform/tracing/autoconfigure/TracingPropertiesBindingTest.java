package space.br1440.platform.tracing.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;

import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.control.channel.TracingControlChannelAutoConfiguration;

@ExtendWith(OutputCaptureExtension.class)
class TracingPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void deletedCategoryASpringMirrorsAreNotBound() {
        contextRunner
                .withPropertyValues(
                        "platform.tracing.sampling.enabled=false",
                        "platform.tracing.sampling.ratio=0.42")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    TracingProperties properties = ctx.getBean(TracingProperties.class);
                    assertThat(properties.getSpanAttributes()).isNotNull();
                    assertThat(properties.getExceptionRecording()).isNotNull();
                });
    }

    @Test
    void obsoleteSpringOnlyLogLevelKeyFailsStartupFast() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(TracingControlChannelAutoConfiguration.class))
                .withPropertyValues(
                        "platform.tracing.control.channel=NONE",
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

    @Test
    void agentBootstrapKeepKeysFromTestPropertySourceDoNotWarn(CapturedOutput output) {
        // given
        contextRunner
                .withConfiguration(AutoConfigurations.of(TracingControlChannelAutoConfiguration.class))
                .withPropertyValues(
                        "platform.tracing.control.channel=NONE",
                        "platform.tracing.sampling.enabled=false",
                        "platform.tracing.sampling.ratio=0.42",
                        "platform.tracing.javaagent.span-attributes.validation.level=ERROR")
                // when
                .run(ctx -> {
                    // then
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(TracingBootstrapSettings.class).tracingEnabled()).isTrue();
                });
        assertThat(output).doesNotContain("Java-agent bootstrap-only properties");
    }

    @Test
    void bindsSpanAttributeValidationAndExceptionRecording() {
        contextRunner
                .withPropertyValues(
                        "platform.tracing.span-attributes.validation.level=DISABLED",
                        "platform.tracing.span-attributes.validation.disabled-reason=emergency",
                        "platform.tracing.exception-recording.include-message=true",
                        "platform.tracing.exception-recording.include-stacktrace=true")
                .run(ctx -> {
                    TracingProperties properties = ctx.getBean(TracingProperties.class);
                    assertThat(properties.getSpanAttributes().getValidation().getLevel().name())
                            .isEqualTo("DISABLED");
                    assertThat(properties.getSpanAttributes().getValidation().getDisabledReason())
                            .isEqualTo("emergency");
                    assertThat(properties.getExceptionRecording().isIncludeMessage()).isTrue();
                    assertThat(properties.getExceptionRecording().isIncludeStacktrace()).isTrue();
                });
    }

    @Test
    void bindsRequestIdHeader() {
        contextRunner
                .withPropertyValues("platform.tracing.request-id-header.enabled=false")
                .run(ctx -> {
                    TracingProperties properties = ctx.getBean(TracingProperties.class);
                    assertThat(properties.getRequestIdHeader().isEnabled()).isFalse();
                });
    }

    @Test
    void requestIdHeaderNameComesOnlyFromPlatformHeaders() {
        contextRunner
                .withPropertyValues("platform.tracing.propagation.platform-headers.request-id-header=X-Custom-Request-Id")
                .run(ctx -> {
                    TracingProperties properties = ctx.getBean(TracingProperties.class);
                    assertThat(properties.getPropagation().getPlatformHeaders().getRequestIdHeader())
                            .isEqualTo("X-Custom-Request-Id");
                });
    }

    @Configuration
    @EnableConfigurationProperties(TracingProperties.class)
    static class TestConfig {
    }
}
