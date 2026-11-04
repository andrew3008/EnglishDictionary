package space.br1440.platform.tracing.runtime.sampling.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.runtime.sampling.model.SamplingStartupRules;

class SamplingStartupRulesValidationTest {

    @Test
    void acceptsValidProductionInput() {
        // given
        List<String> droppedRoutes = List.of(" /metrics ", "/health");
        List<String> forceRecordValues = List.of(" on ", "custom");

        // when
        // then
        assertThatCode(() -> SamplingStartupRules.create(droppedRoutes, forceRecordValues))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNullCollections() {
        // given
        List<String> droppedRoutes = List.of();
        List<String> forceRecordValues = List.of();

        // when
        // then
        assertThatThrownBy(() -> SamplingStartupRules.create(null, forceRecordValues))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("droppedRoutes");

        assertThatThrownBy(() -> SamplingStartupRules.create(droppedRoutes, null))
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
        assertThatThrownBy(() -> SamplingStartupRules.create(nullPath, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Drop path must not be null or blank");

        assertThatThrownBy(() -> SamplingStartupRules.create(List.of("   "), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Drop path must not be null or blank");
    }

    @Test
    void rejectsRelativeDropPath() {
        // given
        List<String> droppedRoutes = List.of("health");

        // when
        // then
        assertThatThrownBy(() -> SamplingStartupRules.create(droppedRoutes, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Drop path must start with /");
    }

    @Test
    void rejectsTooManyDropPaths() {
        // given
        List<String> droppedRoutes = IntStream.rangeClosed(
                        0,
                        SamplingStartupRules.MAX_DROP_PATHS
                )
                .mapToObj(index -> "/path-" + index)
                .toList();

        // when
        // then
        assertThatThrownBy(() -> SamplingStartupRules.create(droppedRoutes, List.of()))
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
        assertThatThrownBy(() -> SamplingStartupRules.create(List.of(), nullValue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Force record value must not be null or blank");

        assertThatThrownBy(() -> SamplingStartupRules.create(List.of(), List.of("   ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Force record value must not be null or blank");
    }

    @Test
    void rejectsTooLongForceRecordValue() {
        // given
        String value = "x".repeat(SamplingStartupRules.MAX_FORCE_VALUE_LENGTH + 1);

        // when
        // then
        assertThatThrownBy(() -> SamplingStartupRules.create(List.of(), List.of(value)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Force record value too long");
    }

    @Test
    void rejectsTooManyForceRecordValues() {
        // given
        List<String> forceRecordValues = IntStream.rangeClosed(
                        0,
                        SamplingStartupRules.MAX_FORCE_VALUES
                )
                .mapToObj(index -> "value-" + index)
                .toList();

        // when
        // then
        assertThatThrownBy(() -> SamplingStartupRules.create(List.of(), forceRecordValues))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Too many force record values");
    }
}
