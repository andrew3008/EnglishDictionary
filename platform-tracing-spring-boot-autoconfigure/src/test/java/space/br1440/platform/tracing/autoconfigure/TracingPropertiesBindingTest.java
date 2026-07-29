package space.br1440.platform.tracing.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import space.br1440.platform.tracing.autoconfigure.diagnostics.OtelAgentLogLevel;
import space.br1440.platform.tracing.autoconfigure.support.SdkMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет корректный bind вложенных свойств {@link TracingProperties} из {@code application.properties}.
 */
class TracingPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void defaultRuntimeModeIsAgent() {
        contextRunner.run(ctx -> {
            TracingProperties properties = ctx.getBean(TracingProperties.class);
            assertThat(properties.getMode()).isEqualTo(SdkMode.AGENT);
        });
    }

    @Test
    void bindsRuntimeModeAsEnum() {
        contextRunner
                .withPropertyValues("platform.tracing.mode=disabled")
                .run(ctx -> {
                    TracingProperties properties = ctx.getBean(TracingProperties.class);
                    assertThat(properties.getMode()).isEqualTo(SdkMode.DISABLED);
                });
    }

    @Test
    void bindsSamplingEnabled() {
        contextRunner
                .withPropertyValues("platform.tracing.sampling.enabled=false")
                .run(ctx -> {
                    TracingProperties properties = ctx.getBean(TracingProperties.class);
                    assertThat(properties.getSampling().isEnabled()).isFalse();
                });
    }

    @Test
    void bindsSamplingRatio() {
        contextRunner
                .withPropertyValues("platform.tracing.sampling.ratio=0.42")
                .run(ctx -> {
                    TracingProperties properties = ctx.getBean(TracingProperties.class);
                    assertThat(properties.getSampling().getRatio()).isEqualTo(0.42d);
                });
    }

    @Test
    void doesNotBindCategoryBSamplingDropPathsOrForceRecordValues() {
        // P6.1: these properties moved to Agent javaagent.sampling.*; Spring must not bind them.
        contextRunner
                .withPropertyValues(
                        "platform.tracing.sampling.drop-paths[0]=/health",
                        "platform.tracing.sampling.force-record-header-values[0]=on")
                .run(ctx -> {
                    TracingProperties properties = ctx.getBean(TracingProperties.class);
                    assertThat(properties.getSampling().getRouteRatios()).isEmpty();
                });
    }

    @Test
    void bindsSamplingRouteRatios() {
        contextRunner
                .withPropertyValues(
                        "platform.tracing.sampling.route-ratios[/api]=0.5",
                        "platform.tracing.sampling.route-ratios[/checkout]=1.0")
                .run(ctx -> {
                    TracingProperties properties = ctx.getBean(TracingProperties.class);
                    assertThat(properties.getSampling().getRouteRatios())
                            .containsEntry("/api", 0.5d)
                            .containsEntry("/checkout", 1.0d);
                });
    }

    @Test
    void bindsScrubbingEnabled() {
        contextRunner
                .withPropertyValues("platform.tracing.scrubbing.enabled=false")
                .run(ctx -> {
                    TracingProperties properties = ctx.getBean(TracingProperties.class);
                    assertThat(properties.getScrubbing().isEnabled()).isFalse();
                });
    }

    @Test
    void bindsScrubbingBuiltInRules() {
        contextRunner
                .withPropertyValues(
                        "platform.tracing.scrubbing.built-in-rules[0]=password",
                        "platform.tracing.scrubbing.built-in-rules[1]=jwt",
                        "platform.tracing.scrubbing.built-in-rules[2]=email")
                .run(ctx -> {
                    TracingProperties properties = ctx.getBean(TracingProperties.class);
                    assertThat(properties.getScrubbing().getBuiltInRules())
                            .containsExactly("password", "jwt", "email");
                });
    }

    @Test
    void bindsValidationEnabled() {
        contextRunner
                .withPropertyValues("platform.tracing.validation.enabled=false")
                .run(ctx -> {
                    TracingProperties properties = ctx.getBean(TracingProperties.class);
                    assertThat(properties.getValidation().isEnabled()).isFalse();
                });
    }

    @Test
    void bindsValidationStrict() {
        contextRunner
                .withPropertyValues("platform.tracing.validation.strict=true")
                .run(ctx -> {
                    TracingProperties properties = ctx.getBean(TracingProperties.class);
                    assertThat(properties.getValidation().isStrict()).isTrue();
                });
    }

    @Test
    void bindsPlatformLogLevelAsEnum() {
        contextRunner
                .withPropertyValues("platform.tracing.log-level=warn")
                .run(ctx -> {
                    TracingProperties properties = ctx.getBean(TracingProperties.class);
                    assertThat(properties.getLogLevel()).isEqualTo(OtelAgentLogLevel.WARN);
                });
    }

    @Test
    void defaultPlatformLogLevelIsInfo() {
        contextRunner.run(ctx -> {
            TracingProperties properties = ctx.getBean(TracingProperties.class);
            assertThat(properties.getLogLevel()).isEqualTo(OtelAgentLogLevel.INFO);
        });
    }

    @Test
    void rejectsUnknownPlatformLogLevel() {
        contextRunner
                .withPropertyValues("platform.tracing.log-level=verbose")
                .run(ctx -> assertThat(ctx.getStartupFailure()).isNotNull());
    }

    @Test
    void defaultValidationValues_preserved() {
        contextRunner
                .run(ctx -> {
                    TracingProperties properties = ctx.getBean(TracingProperties.class);
                    assertThat(properties.getValidation().isEnabled()).isTrue();
                    assertThat(properties.getValidation().isStrict()).isFalse();
                });
    }

    @Test
    void defaultScrubbingBuiltInRules_matchesUmsList() {
        contextRunner
                .run(ctx -> {
                    TracingProperties properties = ctx.getBean(TracingProperties.class);
                    assertThat(properties.getScrubbing().getBuiltInRules())
                            .containsExactly(
                                    "password", "jwt", "email", "oauth-header", "x-auth-header",
                                    "infra-credential", "webhook-token", "ssh-credential",
                                    "user-identity", "hardware-identity", "location", "ip-address"
                            );
                });
    }

    @Configuration
    @EnableConfigurationProperties(TracingProperties.class)
    static class TestConfig {
    }
}
