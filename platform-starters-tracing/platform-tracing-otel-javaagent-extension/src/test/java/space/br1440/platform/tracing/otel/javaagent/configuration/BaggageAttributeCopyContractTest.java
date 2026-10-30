package space.br1440.platform.tracing.otel.javaagent.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;

class BaggageAttributeCopyContractTest {

    @ParameterizedTest
    @MethodSource("includeProperties")
    void rejects_non_empty_include(String propertyName) {
        // given
        var config = DefaultConfigProperties.createFromMap(Map.of(propertyName, "session.id"));

        // when
        Throwable failure = catchThrowable(() -> BaggageAttributeCopyContract.requireDisabled(config));

        // then
        assertThat(failure)
                .isInstanceOf(ConfigurationPropertyException.class)
                .hasCauseInstanceOf(ConfigurationException.class);
        ConfigurationPropertyException propertyFailure = (ConfigurationPropertyException) failure;
        assertThat(propertyFailure.propertyName()).isEqualTo(propertyName);
        assertThat(propertyFailure.failure()).hasMessage(
                propertyName
                        + " must be empty; non-empty values copy baggage into attributes"
                        + " outside the platform baggage policy"
        );
    }

    @ParameterizedTest
    @MethodSource("emptyIncludes")
    void accepts_empty_include(String propertyName, String value) {
        // given
        var config = DefaultConfigProperties.createFromMap(Map.of(propertyName, value));

        // when
        Throwable failure = catchThrowable(() -> BaggageAttributeCopyContract.requireDisabled(config));

        // then
        assertThat(failure).isNull();
    }

    @Test
    void accepts_absent_include() {
        // given
        var config = DefaultConfigProperties.createFromMap(Map.of());

        // when
        Throwable failure = catchThrowable(() -> BaggageAttributeCopyContract.requireDisabled(config));

        // then
        assertThat(failure).isNull();
    }

    private static Stream<String> includeProperties() {
        return Stream.of(
                OtelSdkPropertyNames.SPAN_ATTRIBUTES_COPY_FROM_BAGGAGE_INCLUDE,
                OtelSdkPropertyNames.LOG_ATTRIBUTES_COPY_FROM_BAGGAGE_INCLUDE
        );
    }

    private static Stream<Arguments> emptyIncludes() {
        return includeProperties().flatMap(propertyName -> Stream.of("", " ", ",")
                .map(value -> Arguments.of(propertyName, value)));
    }
}
