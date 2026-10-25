package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.assertBootstrapFailure;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.config;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.coordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.emptyBuiltInRulesConfig;

import java.util.List;
import java.util.Map;
import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.ScrubberFactoryTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.SanitizerBootstrapException;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
class BootstrapCoordinatorSanitizerPreparationTest {

    @Test
    void empty_built_in_rule_set_preserves_SANITIZER_RULE_SET_EMPTY() {
        // given
        try (BootstrapCoordinator coordinator = coordinator()
                .scrubberFactory(ScrubberFactoryTestFixtures.factory(List::of))
                .build()) {
            // when/then
            assertBootstrapFailure(
                    () -> coordinator.prepare(emptyBuiltInRulesConfig()),
                    SanitizerBootstrapException.RULE_SET_EMPTY
            );
        }
    }

    @Test
    void fail_fast_missing_hmac_key_preserves_SANITIZER_HMAC_INITIALIZATION_FAILED() {
        // given
        try (BootstrapCoordinator coordinator = coordinator().build()) {
            // when/then
            assertBootstrapFailure(
                    () -> coordinator.prepare(config(Map.of(
                            ExtensionPropertyNames.SCRUBBING_MISSING_KEY_POLICY,
                            "fail_fast"
                    ))),
                    SanitizerBootstrapException.HMAC_INITIALIZATION_FAILED
            );
        }
    }

    @Test
    void invalid_bundled_rule_metadata_preserves_SANITIZER_RULE_METADATA_INVALID() {
        // given
        SpanAttributeScrubbingRule brokenMetadata = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override
            public String name() {
                throw new IllegalStateException("metadata accessor blew up");
            }

            @Nonnull
            @Override
            public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
                return ScrubbingDecision.keep();
            }
        };

        try (BootstrapCoordinator coordinator = coordinator()
                .scrubberFactory(ScrubberFactoryTestFixtures.factory(() -> List.of(brokenMetadata)))
                .build()) {
            // when/then
            assertBootstrapFailure(
                    () -> coordinator.prepare(config()),
                    SanitizerBootstrapException.RULE_METADATA_INVALID
            );
        }
    }

    @Test
    void linkage_error_from_rule_source_is_not_classified_as_a_sanitizer_failure() {
        // given
        try (BootstrapCoordinator coordinator = coordinator()
                .scrubberFactory(ScrubberFactoryTestFixtures.factory(() -> {
                    throw new NoClassDefFoundError("simulated missing SPI class");
                }))
                .build()) {
            // when/then
            assertThatThrownBy(() -> coordinator.prepare(config()))
                    .isInstanceOf(NoClassDefFoundError.class)
                    .hasMessage("simulated missing SPI class");
        }
    }
}
