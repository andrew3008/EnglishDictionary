package space.br1440.platform.tracing.otel.javaagent.control.settings.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;

class ValidationModeResolverTest {

    @Nested
    class RequestedModeIsEffective {

        @Test
        void requestedStrictIsEffective() {
            // given/when
            ValidationModeResolver.Resolution r = ValidationModeResolver.resolve(ValidationMode.STRICT);

            // then
            assertThat(r.effectiveMode()).isEqualTo(ValidationMode.STRICT);
            assertThat(r.effectiveMode().isStrict()).isTrue();
        }

        @Test
        void requestedLenientIsEffective() {
            // given/when
            ValidationModeResolver.Resolution r = ValidationModeResolver.resolve(ValidationMode.LENIENT);

            // then
            assertThat(r.effectiveMode()).isEqualTo(ValidationMode.LENIENT);
            assertThat(r.effectiveMode().isStrict()).isFalse();
        }

        @Test
        void requestedDisabledIsEffective() {
            // given/when
            ValidationModeResolver.Resolution r = ValidationModeResolver.resolve(ValidationMode.DISABLED);

            // then
            assertThat(r.effectiveMode()).isEqualTo(ValidationMode.DISABLED);
            assertThat(r.effectiveMode().isDisabled()).isTrue();
        }
    }

    @Nested
    class DisabledIsInitialState {

        @Test
        void isolatedDisabledStateStartsWithValidationOff() {
            // given/when
            TracingSettingsSource holder = TracingSettingsSource.isolatedValidation(false, false);

            // then
            assertThat(holder.current().validationEnabled()).isFalse();
            assertThat(holder.current().validationStrict()).isFalse();
        }

        @Test
        void isolatedStrictStateIsNotBlockedByRemovedEnvironmentGate() {
            // given/when
            TracingSettingsSource holder = TracingSettingsSource.isolatedValidation(true, true);

            // then
            assertThat(holder.current().validationEnabled()).isTrue();
            assertThat(holder.current().validationStrict()).isTrue();
        }
    }

    @Nested
    class Parse {

        @Test
        void blankDefaultsToLenient() {
            // given/when/then
            assertThat(ValidationMode.parse(null)).isEqualTo(ValidationMode.LENIENT);
            assertThat(ValidationMode.parse("")).isEqualTo(ValidationMode.LENIENT);
            assertThat(ValidationMode.parse("  ")).isEqualTo(ValidationMode.LENIENT);
        }

        @Test
        void recognizedValuesAreExact() {
            // given/when/then
            assertThat(ValidationMode.parse("LENIENT")).isEqualTo(ValidationMode.LENIENT);
            assertThat(ValidationMode.parse("strict")).isEqualTo(ValidationMode.STRICT);
            assertThat(ValidationMode.parse("DISABLED")).isEqualTo(ValidationMode.DISABLED);
        }
    }
}
