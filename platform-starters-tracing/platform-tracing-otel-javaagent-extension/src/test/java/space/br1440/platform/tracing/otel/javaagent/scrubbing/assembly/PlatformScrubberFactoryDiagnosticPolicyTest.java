package space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeEngine;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeOutcome;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;

class PlatformScrubberFactoryDiagnosticPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"password", "diagnostic-text,password", "password,diagnostic-text,diagnostic-text"})
    void configuredRulesCannotRemoveOrDuplicateMandatoryUrlPolicy(String configuredRules) {
        // given
        ExtensionConfig config = new ExtensionConfig(DefaultConfigProperties.createFromMap(Map.of(
                ExtensionPropertyNames.SCRUBBING_BUILT_IN_RULES, configuredRules))
        );
        String input = "Failed https://user:password@service.example/?token=secret";
        try (ScrubbingRuntime runtime = new PlatformScrubberFactory(List::of).buildRuntime(config)) {
            // when
            MergeOutcome message = MergeEngine.evaluateDetailed(
                    runtime.attributeExecutableRules(),
                    "exception.message",
                    input
            );
            MergeOutcome status = MergeEngine.evaluateDetailed(
                    runtime.builtInTextRules(),
                    "status.description",
                    input
            );

            // then
            assertThat(runtime.attributeExecutableRules())
                    .extracting(rule -> rule.getRule().name())
                    .containsExactly("diagnostic-text", "password");
            for (MergeOutcome outcome : List.of(message, status)) {
                assertThat(outcome.decision().action()).isEqualTo(ScrubbingAction.KEEP);
                assertThat(outcome.maskedValue()).isEqualTo("Failed https://***@service.example/?token=***");
            }
        }
    }

    @Test
    void customOnlyConfigurationStillIncludesMandatoryUrlPolicy() {
        // given
        SpanAttributeScrubbingRule custom = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override
            public String name() {
                return "custom-keep";
            }

            @Nonnull
            @Override
            public ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
                return ScrubbingDecision.keep();
            }
        };
        ExtensionConfig config = new ExtensionConfig(DefaultConfigProperties.createFromMap(Map.of(
                ExtensionPropertyNames.SCRUBBING_BUILT_IN_RULES, "")));

        // when
        try (ScrubbingRuntime runtime = new PlatformScrubberFactory(() -> List.of(custom)).buildRuntime(config)) {
            // then
            assertThat(runtime.attributeExecutableRules())
                    .extracting(rule -> rule.getRule().name())
                    .containsExactly("diagnostic-text", "custom-keep");
            assertThat(runtime.builtInTextRules())
                    .extracting(rule -> rule.getRule().name())
                    .containsExactly("diagnostic-text");
        }
    }
}
