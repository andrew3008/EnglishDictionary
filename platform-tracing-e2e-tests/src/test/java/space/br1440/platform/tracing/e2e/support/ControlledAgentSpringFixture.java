package space.br1440.platform.tracing.e2e.support;

import java.util.List;

/** Общие настройки Spring fixture, использующих platform extension поверх Java Agent. */
final class ControlledAgentSpringFixture {

    private static final String BOOT_OTEL_SDK_AUTO_CONFIGURATIONS = String.join(",",
            "org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.logging.OpenTelemetryLoggingAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.logging.otlp.OtlpLoggingAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration");

    private ControlledAgentSpringFixture() {
    }

    static void addSdkAutoConfigurationExclusion(List<String> jvmProperties, String extensionLocation) {
        if (extensionLocation != null && !extensionLocation.isBlank()) {
            jvmProperties.add("spring.autoconfigure.exclude=" + BOOT_OTEL_SDK_AUTO_CONFIGURATIONS);
        }
    }

    static boolean containsOutputLine(CharSequence output, String expectedLine) {
        return output.toString().lines().anyMatch(expectedLine::equals);
    }
}
