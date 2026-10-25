package space.br1440.platform.tracing.runtime.sampling.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.runtime.sampling.properties.SamplingPolicyPropertiesValidator;

class SamplingPolicyPropertiesValidatorTest {

    @Test
    void acceptsValidProductionInput() {
        // given
        List<String> droppedRoutes = List.of(" /metrics ", "/health");
        List<String> forceRecordValues = List.of(" on ", "custom");

        // when
        // then
        assertThatCode(() -> SamplingPolicyPropertiesValidator.validate(droppedRoutes, forceRecordValues))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNullCollections() {
        // given
        List<String> droppedRoutes = List.of();
        List<String> forceRecordValues = List.of();

        // when
        // then
        assertThatThrownBy(() -> SamplingPolicyPropertiesValidator.validate(null, forceRecordValues))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("droppedRoutes");

        assertThatThrownBy(() -> SamplingPolicyPropertiesValidator.validate(droppedRoutes, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("forceRecordValues");
    }

    @Test
    void rejectsNullOrBlankDropPath() {
        // given
        List<String> nullPath = new ArrayList<>();
        nullPath.add(null);

        // when
        // then
        assertThatThrownBy(() -> SamplingPolicyPropertiesValidator.validate(nullPath, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Drop path must not be null or blank");

        assertThatThrownBy(() -> SamplingPolicyPropertiesValidator.validate(List.of("   "), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Drop path must not be null or blank");
    }

    @Test
    void rejectsRelativeDropPath() {
        // given
        List<String> droppedRoutes = List.of("health");

        // when
        // then
        assertThatThrownBy(() -> SamplingPolicyPropertiesValidator.validate(droppedRoutes, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Drop path must start with /");
    }

    @Test
    void rejectsTooManyDropPaths() {
        // given
        List<String> droppedRoutes = IntStream.rangeClosed(
                        0,
                        SamplingPolicyPropertiesValidator.MAX_DROP_PATHS
                )
                .mapToObj(index -> "/path-" + index)
                .toList();

        // when
        // then
        assertThatThrownBy(() -> SamplingPolicyPropertiesValidator.validate(droppedRoutes, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Too many drop paths configured");
    }

    @Test
    void rejectsNullOrBlankForceRecordValue() {
        // given
        List<String> nullValue = new ArrayList<>();
        nullValue.add(null);

        // when
        // then
        assertThatThrownBy(() -> SamplingPolicyPropertiesValidator.validate(List.of(), nullValue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Force record value must not be null or blank");

        assertThatThrownBy(() -> SamplingPolicyPropertiesValidator.validate(List.of(), List.of("   ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Force record value must not be null or blank");
    }

    @Test
    void rejectsTooLongForceRecordValue() {
        // given
        String value = "x".repeat(SamplingPolicyPropertiesValidator.MAX_FORCE_VALUE_LENGTH + 1);

        // when
        // then
        assertThatThrownBy(() -> SamplingPolicyPropertiesValidator.validate(List.of(), List.of(value)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Force record value too long");
    }

    @Test
    void rejectsTooManyForceRecordValues() {
        // given
        List<String> forceRecordValues = IntStream.rangeClosed(
                        0,
                        SamplingPolicyPropertiesValidator.MAX_FORCE_VALUES
                )
                .mapToObj(index -> "value-" + index)
                .toList();

        // when
        // then
        assertThatThrownBy(() -> SamplingPolicyPropertiesValidator.validate(List.of(), forceRecordValues))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Too many force record values");
    }
}
