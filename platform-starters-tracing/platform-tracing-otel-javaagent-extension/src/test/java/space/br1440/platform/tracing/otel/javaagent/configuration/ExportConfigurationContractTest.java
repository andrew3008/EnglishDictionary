package space.br1440.platform.tracing.otel.javaagent.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;

class ExportConfigurationContractTest {

    @ParameterizedTest
    @MethodSource("supportedConfigurations")
    void acceptsSupportedConfiguration(Map<String, String> properties) {
        // given
        ConfigProperties config = DefaultConfigProperties.createFromMap(properties);

        // when
        Throwable failure = catchThrowable(() -> ExportConfigurationContract.requireSupported(config));

        // then
        assertThat(failure).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "logging", "console", "logging-otlp", "otlp-stdout", "zipkin", "otlp,logging",
            "otlp,otlp", "otlp ,", "custom-spi", "OTLP", " "})
    void rejectsOtherTraceExporters(String value) {
        // given
        ConfigProperties config = DefaultConfigProperties.createFromMap(Map.of("otel.traces.exporter", value));

        // when
        Throwable failure = catchThrowable(() -> ExportConfigurationContract.requireSupported(config));

        // then
        assertProperty(failure, "otel.traces.exporter");
        assertThat(failure).hasMessageContaining("otel.traces.exporter=" + value);
    }

    @ParameterizedTest
    @MethodSource("invalidConfigurations")
    void rejectsInvalidConfigurationWithPropertyName(String propertyName, String value) {
        // given
        ConfigProperties config = DefaultConfigProperties.createFromMap(Map.of(propertyName, value));

        // when
        Throwable failure = catchThrowable(() -> ExportConfigurationContract.requireSupported(config));

        // then
        assertProperty(failure, propertyName);
    }

    @Test
    void emptyTraceProtocolDoesNotFallBackToGeneralProtocol() {
        // given
        ConfigProperties config = DefaultConfigProperties.createFromMap(Map.of(
                "otel.exporter.otlp.traces.protocol", "",
                "otel.exporter.otlp.protocol", "grpc"
        ));

        // when
        Throwable failure = catchThrowable(() -> ExportConfigurationContract.requireSupported(config));

        // then
        assertProperty(failure, "otel.exporter.otlp.traces.protocol");
    }

    @Test
    void effectiveTraceTimeoutMustNotExceedBspTimeout() {
        // given
        ConfigProperties config = DefaultConfigProperties.createFromMap(Map.of(
                "otel.exporter.otlp.timeout", "1s",
                "otel.exporter.otlp.traces.timeout", "11s",
                "otel.bsp.export.timeout", "10s"
        ));

        // when
        Throwable failure = catchThrowable(() -> ExportConfigurationContract.requireSupported(config));

        // then
        assertProperty(failure, "otel.exporter.otlp.traces.timeout");
    }

    @Test
    void defaultOtlpTimeoutMustFitBspTimeout() {
        // given
        ConfigProperties config = DefaultConfigProperties.createFromMap(Map.of("otel.bsp.export.timeout", "9s"));

        // when
        Throwable failure = catchThrowable(() -> ExportConfigurationContract.requireSupported(config));

        // then
        assertProperty(failure, "otel.exporter.otlp.timeout");
    }

    @Test
    void emptyTraceTimeoutStillValidatesTheGeneralTimeout() {
        // given
        ConfigProperties config = DefaultConfigProperties.createFromMap(Map.of(
                "otel.exporter.otlp.traces.timeout", "",
                "otel.exporter.otlp.timeout", "31s"
        ));

        // when
        Throwable failure = catchThrowable(() -> ExportConfigurationContract.requireSupported(config));

        // then
        assertProperty(failure, "otel.exporter.otlp.timeout");
    }

    private static Stream<Map<String, String>> supportedConfigurations() {
        return Stream.of(
                Map.of(),
                Map.of("otel.traces.exporter", ""),
                Map.of("otel.traces.exporter", "otlp", "otel.metrics.exporter", "none", "otel.logs.exporter", "none"),
                Map.of("otel.exporter.otlp.protocol", "http/protobuf"),
                Map.of("otel.exporter.otlp.protocol", "grpc"),
                Map.of("otel.exporter.otlp.protocol", "invalid", "otel.exporter.otlp.traces.protocol", "grpc"),
                Map.of("otel.exporter.otlp.timeout", "0", "otel.exporter.otlp.traces.timeout", "10s"),
                Map.of("otel.exporter.otlp.timeout", "30s", "otel.bsp.export.timeout", "30s"),
                Map.of("otel.bsp.max.queue.size", "8", "otel.bsp.max.export.batch.size", "8")
        );
    }

    private static Stream<Arguments> invalidConfigurations() {
        return Stream.of(
                Arguments.of("otel.metrics.exporter", "otlp"),
                Arguments.of("otel.logs.exporter", "otlp"),
                Arguments.of("otel.metrics.exporter", ""),
                Arguments.of("otel.logs.exporter", "none,otlp"),
                Arguments.of("otel.exporter.otlp.protocol", "http/json"),
                Arguments.of("otel.exporter.otlp.traces.protocol", "http/json"),
                Arguments.of("otel.exporter.otlp.timeout", "0"),
                Arguments.of("otel.exporter.otlp.traces.timeout", "-1ms"),
                Arguments.of("otel.exporter.otlp.timeout", "31s"),
                Arguments.of("otel.exporter.otlp.timeout", "invalid"),
                Arguments.of("otel.bsp.export.timeout", "0"),
                Arguments.of("otel.bsp.export.timeout", "invalid"),
                Arguments.of("otel.bsp.max.export.batch.size", "2049"),
                Arguments.of("otel.bsp.max.export.batch.size", "0"),
                Arguments.of("otel.bsp.max.queue.size", "0"),
                Arguments.of("otel.bsp.max.queue.size", "invalid")
        );
    }

    private static void assertProperty(Throwable failure, String propertyName) {
        assertThat(failure).isInstanceOf(ConfigurationPropertyException.class);
        assertThat(((ConfigurationPropertyException) failure).propertyName()).isEqualTo(propertyName);
    }
}
