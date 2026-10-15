package space.br1440.platform.tracing.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import space.br1440.platform.tracing.autoconfigure.control.channel.TracingControlChannelAutoConfiguration;

class TracingPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

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
                    assertThat(properties.getSpanAttributes().getValidation().getLevel().name()).isEqualTo("DISABLED");
                    assertThat(properties.getSpanAttributes().getValidation().getDisabledReason()).isEqualTo("emergency");
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
    void platformHeaderNameInApplicationPropertiesIsRejected() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(TracingControlChannelAutoConfiguration.class))
                .withPropertyValues(
                        "platform.tracing.control.channel=NONE",
                        "platform.tracing.propagation.platform-headers.request-id-header=X-Custom-Request-Id"
                )
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    String message = failureText(ctx.getStartupFailure());
                    assertThat(message).contains("owned by AGENT");
                    assertThat(message).contains("platform.tracing.propagation.platform-headers.request-id-header");
                    assertThat(message).doesNotContain("X-Custom-Request-Id");
                });
    }

    private static String failureText(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                text.append(current.getMessage());
            }

            current = current.getCause();
        }

        return text.toString();
    }

    @Configuration
    @EnableConfigurationProperties(TracingProperties.class)
    static class TestConfig {
    }
}
