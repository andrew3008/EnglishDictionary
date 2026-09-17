package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyMutationAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.SpanData;

class ValidatingSpanProcessorTest {

    private static ValidatingSpanProcessor warnLevel() {
        return new ValidatingSpanProcessor(TracingSettingsSource.isolatedValidation(true, false));
    }

    private static ValidatingSpanProcessor errorLevel() {
        return new ValidatingSpanProcessor(TracingSettingsSource.isolatedValidation(true, true));
    }

    @Test
    void sets_platform_validation_missing_when_span_attributes_absent() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(warnLevel(), Resource.empty())) {
            Tracer tracer = h.tracer("test");
            Span span = tracer.spanBuilder("op").startSpan();
            span.end();

            SpanData data = h.exporter().getFinishedSpanItems().getFirst();
            String missing = data.getAttributes().get(AttributeKey.stringKey("platform.validation.missing"));
            assertThat(missing)
                    .contains(PlatformAttributes.PLATFORM_TYPE)
                    .contains(PlatformAttributes.PLATFORM_RESULT);
        }
    }

    @Test
    void does_not_record_violation_when_span_type_and_result_attributes_are_set() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(warnLevel(), Resource.empty())) {
            h.tracer("test").spanBuilder("op")
                    .setAttribute(PlatformAttributes.PLATFORM_TYPE, "HTTP")
                    .setAttribute(PlatformAttributes.PLATFORM_RESULT, "success")
                    .startSpan().end();

            Attributes attrs = h.exporter().getFinishedSpanItems().getFirst().getAttributes();
            assertThat(attrs.get(AttributeKey.stringKey("platform.validation.missing"))).isNull();
        }
    }

    @Test
    void throttling_suppresses_repeated_warn_and_does_not_move_timestamp() {
        ValidatingSpanProcessor validator = new ValidatingSpanProcessor(
                () -> 1000L,
                TracingSettingsSource.isolatedValidation(true, false)
        );

        try (SpanProcessorHarness h = SpanProcessorHarness.of(validator, Resource.empty())) {
            Tracer tracer = h.tracer("test");
            for (int i = 0; i < 1000; i++) {
                tracer.spanBuilder("op").startSpan().end();
            }

            // Все 1000 span'ов получили атрибут platform.validation.missing — это контракт.
            assertThat(h.exporter().getFinishedSpanItems()).hasSize(1000);
            for (SpanData data : h.exporter().getFinishedSpanItems()) {
                assertThat(data.getAttributes().get(AttributeKey.stringKey("platform.validation.missing"))).isNotBlank();
            }
        }

        // Throttling: метка времени для уникального missing-набора зафиксирована один раз и
        // не двигалась повторно — значит, повторные WARN'ы в логе подавлены.
        assertThat(validator.lastDiagnosticTimestampForTesting(false)).isEqualTo(1000L);
        assertThat(validator.getValidationViolationsTotal()).isEqualTo(1000L);
    }

    @Test
    void warn_and_error_diagnostics_have_independent_throttling() {
        TracingSettingsSource access = TracingSettingsSource.isolatedValidation(true, false);
        ValidatingSpanProcessor validator = new ValidatingSpanProcessor(() -> 1000L, access);

        try (SpanProcessorHarness h = SpanProcessorHarness.of(validator, Resource.empty())) {
            h.tracer("test").spanBuilder("warn").startSpan().end();
            assertThat(AcceptedPolicyMutationAccess.applyValidation(access, true, true))
                    .isEqualTo(SettingsUpdateStatus.APPLIED);
            h.tracer("test").spanBuilder("error").startSpan().end();
        }

        assertThat(validator.lastDiagnosticTimestampForTesting(false)).isEqualTo(1000L);
        assertThat(validator.lastDiagnosticTimestampForTesting(true)).isEqualTo(1000L);
        assertThat(validator.getValidationViolationsTotal()).isEqualTo(2L);
    }

    @Test
    void error_level_records_violation_without_interrupting_span_end() {
        ValidatingSpanProcessor validator = errorLevel();
        try (SpanProcessorHarness h = SpanProcessorHarness.of(validator, Resource.empty())) {
            Tracer tracer = h.tracer("test");
            tracer.spanBuilder("op").startSpan().end();

            assertThat(h.exporter().getFinishedSpanItems()).hasSize(1);
            assertThat(validator.getValidationViolationsTotal()).isEqualTo(1L);
        }
    }

    @Test
    void strictValidationDoesNotUseResourceAttributeAsTraceTypeFallback() {
        // platform.trace.type задан только как resource-атрибут, NOT on span
        Resource resourceWithType = Resource.create(
                Attributes.of(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TYPE), "http_server"));

        ValidatingSpanProcessor validator = errorLevel();
        try (SpanProcessorHarness h = SpanProcessorHarness.of(validator, resourceWithType)) {
            Tracer tracer = h.tracer("test");
            // platform.trace.result задан на span'е, только type — нет
            io.opentelemetry.api.trace.SpanBuilder builder = tracer.spanBuilder("op")
                    .setAttribute(PlatformAttributes.PLATFORM_RESULT, "success");

            builder.startSpan().end();

            String missing = h.exporter().getFinishedSpanItems().getFirst().getAttributes()
                    .get(AttributeKey.stringKey("platform.validation.missing"));
            assertThat(missing).isEqualTo(PlatformAttributes.PLATFORM_TYPE);
            assertThat(validator.getValidationViolationsTotal()).isEqualTo(1L);
        }
    }

    @Test
    void strictValidationDoesNotUseResourceAttributeAsTraceResultFallback() {
        // platform.trace.result задан только как resource-атрибут, NOT on span
        Resource resourceWithResult = Resource.create(
                Attributes.of(AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT), "success"));

        ValidatingSpanProcessor validator = errorLevel();
        try (SpanProcessorHarness h = SpanProcessorHarness.of(validator, resourceWithResult)) {
            Tracer tracer = h.tracer("test");
            // platform.trace.type задан на span'е, только result — нет
            io.opentelemetry.api.trace.SpanBuilder builder = tracer.spanBuilder("op")
                    .setAttribute(PlatformAttributes.PLATFORM_TYPE, "http_server");

            builder.startSpan().end();

            String missing = h.exporter().getFinishedSpanItems().getFirst().getAttributes()
                    .get(AttributeKey.stringKey("platform.validation.missing"));
            assertThat(missing).isEqualTo(PlatformAttributes.PLATFORM_RESULT);
            assertThat(validator.getValidationViolationsTotal()).isEqualTo(1L);
        }
    }

    @Test
    void strictValidationAcceptsTraceTypeAndTraceResultSpanAttributes() {
        ValidatingSpanProcessor validator = errorLevel();
        try (SpanProcessorHarness h = SpanProcessorHarness.of(validator, Resource.empty())) {
            Tracer tracer = h.tracer("test");
            // Оба обязательных атрибута заданы на span'е — исключения быть не должно
            tracer.spanBuilder("op")
                    .setAttribute(PlatformAttributes.PLATFORM_TYPE, "http_server")
                    .setAttribute(PlatformAttributes.PLATFORM_RESULT, "success")
                    .startSpan().end();

            Attributes attrs = h.exporter().getFinishedSpanItems().getFirst().getAttributes();
            assertThat(attrs.get(AttributeKey.stringKey("platform.validation.missing"))).isNull();
        }
    }

    @Test
    void warnLevelValidationDoesNotThrowWhenRequiredSpanAttributesExistOnlyOnResource() {
        Resource resourceWithBoth = Resource.create(Attributes.builder()
                .put(PlatformAttributes.PLATFORM_TYPE, "http_server")
                .put(PlatformAttributes.PLATFORM_RESULT, "success")
                .build());

        // WARN (strict=false) — не должен бросать
        try (SpanProcessorHarness h = SpanProcessorHarness.of(warnLevel(), resourceWithBoth)) {
            Tracer tracer = h.tracer("test");
            Span span = tracer.spanBuilder("op").startSpan();
            span.end();

            SpanData data = h.exporter().getFinishedSpanItems().getFirst();
            String missing = data.getAttributes().get(AttributeKey.stringKey("platform.validation.missing"));
            assertThat(missing)
                    .contains(PlatformAttributes.PLATFORM_TYPE)
                    .contains(PlatformAttributes.PLATFORM_RESULT);
        }
    }
}
