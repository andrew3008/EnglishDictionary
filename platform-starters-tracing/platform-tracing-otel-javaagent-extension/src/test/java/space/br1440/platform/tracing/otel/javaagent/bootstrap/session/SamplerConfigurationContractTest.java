package space.br1440.platform.tracing.otel.javaagent.bootstrap.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.otel.javaagent.configuration.ConfigurationPropertyException;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class SamplerConfigurationContractTest {

    private static final String SAMPLER = "otel.traces.sampler";
    private static final String SAMPLER_ARG = "otel.traces.sampler.arg";
    private static final String SDK_DISABLED = "otel.sdk.disabled";
    private static final String JAVAAGENT_EXTENSIONS = "otel.javaagent.extensions";
    private static final String CANARY = "sampler-arg-must-not-appear-7f31";

    @RegisterExtension
    final AgentLogCapture logs = new AgentLogCapture();

    @ParameterizedTest
    @MethodSource("normalizable_sampler_properties")
    void normalize_supplies_the_placeholder_for_an_absent_or_empty_sampler(Map<String, String> properties) {
        // given
        var config = DefaultConfigProperties.createFromMap(properties);

        // when
        Map<String, String> overrides = SamplerConfigurationContract.normalize(config);

        // then
        assertThat(overrides).containsExactlyEntriesOf(Map.of(SAMPLER, "always_off"));
    }

    @Test
    void normalize_preserves_an_explicit_sampler_for_final_validation() {
        // given
        var config = DefaultConfigProperties.createFromMap(Map.of(SAMPLER, "always_on"));

        // when
        Map<String, String> overrides = SamplerConfigurationContract.normalize(config);

        // then
        assertThat(overrides).isEmpty();
    }

    @Test
    void require_final_accepts_the_controlled_agent_configuration() {
        // given
        var config = DefaultConfigProperties.createFromMap(Map.of(
                SAMPLER, "always_off",
                SAMPLER_ARG, "",
                SDK_DISABLED, "false",
                JAVAAGENT_EXTENSIONS, ""
        ));

        // when
        Throwable failure = catchThrowable(() -> SamplerConfigurationContract.requireFinal(config));

        // then
        assertThat(failure).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected_final_properties")
    void require_final_rejects_values_outside_the_controlled_agent_contract(String scenario,
                                                                             Map<String, String> properties,
                                                                             String propertyName,
                                                                             String message) {
        // given
        var config = DefaultConfigProperties.createFromMap(properties);

        // when
        Throwable failure = catchThrowable(() -> SamplerConfigurationContract.requireFinal(config));

        // then
        assertThat(failure)
                .isInstanceOf(ConfigurationPropertyException.class)
                .hasCauseInstanceOf(ConfigurationException.class);
        ConfigurationPropertyException propertyFailure = (ConfigurationPropertyException) failure;
        assertThat(propertyFailure.propertyName()).isEqualTo(propertyName);
        assertThat(propertyFailure.failure()).hasMessage(message);
    }

    @Test
    void sampler_argument_value_is_not_exposed_in_the_failure_or_logs() {
        // given
        var config = DefaultConfigProperties.createFromMap(finalProperties(SAMPLER_ARG, CANARY));

        // when
        Throwable failure = catchThrowable(() -> SamplerConfigurationContract.requireFinal(config));

        // then
        assertThat(failure).isInstanceOf(ConfigurationPropertyException.class);
        assertThat(causeMessages(failure)).noneMatch(message -> message.contains(CANARY));
        assertThat(logs.rendered()).doesNotContain(CANARY);
    }

    private static Stream<Map<String, String>> normalizable_sampler_properties() {
        return Stream.of(Map.of(), Map.of(SAMPLER, ""));
    }

    private static Stream<Arguments> rejected_final_properties() {
        return Stream.of(
                Arguments.of(
                        "foreign sampler",
                        finalProperties(SAMPLER, "always_on"),
                        SAMPLER,
                        "otel.traces.sampler is managed by the controlled agent and must be unset"
                ),
                Arguments.of(
                        "whitespace sampler",
                        finalProperties(SAMPLER, " "),
                        SAMPLER,
                        "otel.traces.sampler is managed by the controlled agent and must be unset"
                ),
                Arguments.of(
                        "sampler argument",
                        finalProperties(SAMPLER_ARG, "0.5"),
                        SAMPLER_ARG,
                        "otel.traces.sampler.arg is not supported; use platform.tracing.sampling.ratio"
                ),
                Arguments.of(
                        "disabled sdk",
                        finalProperties(SDK_DISABLED, "true"),
                        SDK_DISABLED,
                        "otel.sdk.disabled is not supported by the controlled agent profile"
                ),
                Arguments.of(
                        "non-boolean disabled sdk",
                        finalProperties(SDK_DISABLED, "yes"),
                        SDK_DISABLED,
                        "otel.sdk.disabled is not supported by the controlled agent profile"
                ),
                Arguments.of(
                        "external extension",
                        finalProperties(JAVAAGENT_EXTENSIONS, "extension.jar"),
                        JAVAAGENT_EXTENSIONS,
                        "otel.javaagent.extensions is not supported by the controlled agent profile"
                ),
                Arguments.of(
                        "whitespace extension",
                        finalProperties(JAVAAGENT_EXTENSIONS, " "),
                        JAVAAGENT_EXTENSIONS,
                        "otel.javaagent.extensions is not supported by the controlled agent profile"
                )
        );
    }

    private static Map<String, String> finalProperties(String propertyName, String value) {
        if (SAMPLER.equals(propertyName)) {
            return Map.of(SAMPLER, value);
        }

        return Map.of(SAMPLER, "always_off", propertyName, value);
    }

    private static List<String> causeMessages(Throwable failure) {
        List<String> messages = new ArrayList<>();
        Throwable current = failure;
        while (current != null) {
            messages.add(String.valueOf(current.getMessage()));
            current = current.getCause();
        }
        return List.copyOf(messages);
    }
}
