package space.br1440.platform.tracing.runtime.sampling.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class SamplingStartupRulesTest {

    @Test
    void normalizesAndDefensivelyCopiesInputs() {
        // given
        List<String> paths = new ArrayList<>(List.of(" /health ", "/ready"));
        List<String> values = new ArrayList<>(List.of(" On ", "ON", "CUSTOM"));

        // when
        SamplingStartupRules rules = SamplingStartupRules.create(paths, values);
        paths.clear();
        values.clear();

        // then
        assertThat(rules.droppedRoutes()).containsExactly("/health", "/ready");
        assertThat(rules.forceRecordValues()).containsExactlyInAnyOrder("on", "custom");
        assertThatThrownBy(() -> rules.droppedRoutes().add("/new"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> rules.forceRecordValues().add("new"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void acceptsExactLimitsAndRejectsDuplicatesBeyondLimitBeforeDeduplication() {
        // given
        List<String> paths = Collections.nCopies(100, "/health");
        List<String> values = Collections.nCopies(50, "x".repeat(255));

        // when
        SamplingStartupRules rules = SamplingStartupRules.create(paths, values);

        // then
        assertThat(rules.droppedRoutes()).hasSize(100);
        assertThat(rules.forceRecordValues()).containsExactly("x".repeat(255));
        assertThatThrownBy(() -> SamplingStartupRules.create(Collections.nCopies(101, "/health"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SamplingStartupRules.create(List.of(), Collections.nCopies(51, "on")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
