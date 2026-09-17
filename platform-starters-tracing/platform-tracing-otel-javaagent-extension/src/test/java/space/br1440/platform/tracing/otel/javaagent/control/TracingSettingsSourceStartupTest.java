package space.br1440.platform.tracing.otel.javaagent.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;

class TracingSettingsSourceStartupTest {

    @Test
    void processStrictStartsEnabledAndStrict() {
        // given/when
        TracingSettingsSource source = TracingSettingsSource.fromStartup(config(Map.of(
                ExtensionPropertyNames.VALIDATION_MODE,
                "STRICT"
        )));

        // then
        assertThat(source.current().validationEnabled()).isTrue();
        assertThat(source.current().validationStrict()).isTrue();
    }

    @Test
    void processDisabledStartsWithValidationOff() {
        // given/when
        TracingSettingsSource source = TracingSettingsSource.fromStartup(config(Map.of(
                ExtensionPropertyNames.VALIDATION_MODE,
                "DISABLED"
        )));

        // then
        assertThat(source.current().validationEnabled()).isFalse();
        assertThat(source.current().validationStrict()).isFalse();
    }

    @Test
    void leftoverPlatformEnvironmentDoesNotDowngradeStrict() {
        // given/when
        TracingSettingsSource source = TracingSettingsSource.fromStartup(config(Map.of(
                ExtensionPropertyNames.VALIDATION_MODE,
                "STRICT",
                "platform.environment",
                "production"
        )));

        // then
        assertThat(source.current().validationEnabled()).isTrue();
        assertThat(source.current().validationStrict()).isTrue();
    }

    @Test
    void processDisabledCanBeEnabledLaterWithoutLifetimeBan() {
        // given
        TracingSettingsSource source = TracingSettingsSource.fromStartup(config(Map.of(
                ExtensionPropertyNames.VALIDATION_MODE,
                "DISABLED"
        )));

        // when
        SettingsUpdateStatus outcome = AcceptedPolicyMutationAccess.applyValidation(source, true, true);

        // then
        assertThat(outcome).isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(source.current().validationEnabled()).isTrue();
        assertThat(source.current().validationStrict()).isTrue();
    }

    private static ExtensionConfig config(Map<String, String> values) {
        return new ExtensionConfig(DefaultConfigProperties.createFromMap(values));
    }
}
