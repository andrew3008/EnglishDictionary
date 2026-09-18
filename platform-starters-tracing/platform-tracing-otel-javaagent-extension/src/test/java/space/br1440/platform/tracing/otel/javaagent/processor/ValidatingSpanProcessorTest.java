package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyMutationAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.SpanData;

class ValidatingSpanProcessorTest {

    private static ValidatingSpanProcessor warnLevel() {
        return new ValidatingSpanProcessor(TracingSettingsSource.isolatedValidation(SpanAttributeValidationLevel.WARNING));
    }

    private static ValidatingSpanProcessor errorLevel() {
        return new ValidatingSpanProcessor(TracingSettingsSource.isolatedValidation(SpanAttributeValidationLevel.ERROR));
    }

    @Test
    void sets_platform_validation_missing_when_span_attributes_absent() {
        // given
        try (SpanProcessorHarness h = SpanProcessorHarness.of(warnLevel(), Resource.empty())) {
            Tracer tracer = h.tracer("test");
            Span span = tracer.spanBuilder("op").startSpan();
            // when
            span.end();

            SpanData data = h.exporter().getFinishedSpanItems().getFirst();
            String missing = data.getAttributes().get(AttributeKey.stringKey("platform.validation.missing"));
            // then
            assertThat(missing)
                    .contains(PlatformAttributes.PLATFORM_TYPE)
                    .contains(PlatformAttributes.PLATFORM_RESULT);
        }
    }

    @Test
    void does_not_record_violation_when_span_type_and_result_attributes_are_set() {
        // given
        try (SpanProcessorHarness h = SpanProcessorHarness.of(warnLevel(), Resource.empty())) {
            h.tracer("test").spanBuilder("op")
                    .setAttribute(PlatformAttributes.PLATFORM_TYPE, "HTTP")
                    .setAttribute(PlatformAttributes.PLATFORM_RESULT, "success")
                    // when
                    .startSpan().end();

            Attributes attrs = h.exporter().getFinishedSpanItems().getFirst().getAttributes();
            // then
            assertThat(attrs.get(AttributeKey.stringKey("platform.validation.missing"))).isNull();
        }
    }

    @Test
    void throttling_suppresses_repeated_warn_and_does_not_move_timestamp() {
        // given
        ValidatingSpanProcessor validator = new ValidatingSpanProcessor(
                () -> 1000L,
                TracingSettingsSource.isolatedValidation(SpanAttributeValidationLevel.WARNING)
        );

        try (SpanProcessorHarness h = SpanProcessorHarness.of(validator, Resource.empty())) {
            Tracer tracer = h.tracer("test");
            for (int i = 0; i < 1000; i++) {
                // when
                tracer.spanBuilder("op").startSpan().end();
            }

            // Ð’ÑÐµ 1000 span'Ð¾Ð² Ð¿Ð¾Ð»ÑƒÑ‡Ð¸Ð»Ð¸ Ð°Ñ‚Ñ€Ð¸Ð±ÑƒÑ‚ platform.validation.missing â€” ÑÑ‚Ð¾ ÐºÐ¾Ð½Ñ‚Ñ€Ð°ÐºÑ‚.
            // then
            assertThat(h.exporter().getFinishedSpanItems()).hasSize(1000);
            for (SpanData data : h.exporter().getFinishedSpanItems()) {
                assertThat(data.getAttributes().get(AttributeKey.stringKey("platform.validation.missing"))).isNotBlank();
            }
        }

        // Throttling: Ð¼ÐµÑ‚ÐºÐ° Ð²Ñ€ÐµÐ¼ÐµÐ½Ð¸ Ð´Ð»Ñ ÑƒÐ½Ð¸ÐºÐ°Ð»ÑŒÐ½Ð¾Ð³Ð¾ missing-Ð½Ð°Ð±Ð¾Ñ€Ð° Ð·Ð°Ñ„Ð¸ÐºÑÐ¸Ñ€Ð¾Ð²Ð°Ð½Ð° Ð¾Ð´Ð¸Ð½ Ñ€Ð°Ð· Ð¸
        // Ð½Ðµ Ð´Ð²Ð¸Ð³Ð°Ð»Ð°ÑÑŒ Ð¿Ð¾Ð²Ñ‚Ð¾Ñ€Ð½Ð¾ â€” Ð·Ð½Ð°Ñ‡Ð¸Ñ‚, Ð¿Ð¾Ð²Ñ‚Ð¾Ñ€Ð½Ñ‹Ðµ WARN'Ñ‹ Ð² Ð»Ð¾Ð³Ðµ Ð¿Ð¾Ð´Ð°Ð²Ð»ÐµÐ½Ñ‹.
        assertThat(validator.lastDiagnosticTimestampForTesting(SpanAttributeValidationLevel.WARNING)).isEqualTo(1000L);
        assertThat(validator.getValidationViolationsTotal()).isEqualTo(1000L);
    }

    @Test
    void warn_and_error_diagnostics_have_independent_throttling() {
        // given
        TracingSettingsSource access = TracingSettingsSource.isolatedValidation(SpanAttributeValidationLevel.WARNING);
        ValidatingSpanProcessor validator = new ValidatingSpanProcessor(() -> 1000L, access);

        try (SpanProcessorHarness h = SpanProcessorHarness.of(validator, Resource.empty())) {
        // when
            h.tracer("test").spanBuilder("warn").startSpan().end();
            // then
            assertThat(AcceptedPolicyMutationAccess.applyValidation(access, SpanAttributeValidationLevel.ERROR))
                    .isEqualTo(SettingsUpdateStatus.APPLIED);
            h.tracer("test").spanBuilder("error").startSpan().end();
        }

        assertThat(validator.lastDiagnosticTimestampForTesting(SpanAttributeValidationLevel.WARNING)).isEqualTo(1000L);
        assertThat(validator.lastDiagnosticTimestampForTesting(SpanAttributeValidationLevel.ERROR)).isEqualTo(1000L);
        assertThat(validator.getValidationViolationsTotal()).isEqualTo(2L);
    }

    @Test
    void error_level_records_violation_without_interrupting_span_end() {
        // given
        ValidatingSpanProcessor validator = errorLevel();
        try (SpanProcessorHarness h = SpanProcessorHarness.of(validator, Resource.empty())) {
            Tracer tracer = h.tracer("test");
            // when
            tracer.spanBuilder("op").startSpan().end();

            // then
            assertThat(h.exporter().getFinishedSpanItems()).hasSize(1);
            assertThat(validator.getValidationViolationsTotal()).isEqualTo(1L);
        }
    }

    @Test
    void strictValidationDoesNotUseResourceAttributeAsTraceTypeFallback() {
        // given
        // platform.trace.type Ð·Ð°Ð´Ð°Ð½ Ñ‚Ð¾Ð»ÑŒÐºÐ¾ ÐºÐ°Ðº resource-Ð°Ñ‚Ñ€Ð¸Ð±ÑƒÑ‚, NOT on span
        Resource resourceWithType = Resource.create(
                Attributes.of(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TYPE), "http_server"));

        ValidatingSpanProcessor validator = errorLevel();
        try (SpanProcessorHarness h = SpanProcessorHarness.of(validator, resourceWithType)) {
            Tracer tracer = h.tracer("test");
            // platform.trace.result Ð·Ð°Ð´Ð°Ð½ Ð½Ð° span'Ðµ, Ñ‚Ð¾Ð»ÑŒÐºÐ¾ type â€” Ð½ÐµÑ‚
            io.opentelemetry.api.trace.SpanBuilder builder = tracer.spanBuilder("op")
                    .setAttribute(PlatformAttributes.PLATFORM_RESULT, "success");

            // when
            builder.startSpan().end();

            String missing = h.exporter().getFinishedSpanItems().getFirst().getAttributes()
                    .get(AttributeKey.stringKey("platform.validation.missing"));
            // then
            assertThat(missing).isEqualTo(PlatformAttributes.PLATFORM_TYPE);
            assertThat(validator.getValidationViolationsTotal()).isEqualTo(1L);
        }
    }

    @Test
    void strictValidationDoesNotUseResourceAttributeAsTraceResultFallback() {
        // given
        // platform.trace.result Ð·Ð°Ð´Ð°Ð½ Ñ‚Ð¾Ð»ÑŒÐºÐ¾ ÐºÐ°Ðº resource-Ð°Ñ‚Ñ€Ð¸Ð±ÑƒÑ‚, NOT on span
        Resource resourceWithResult = Resource.create(
                Attributes.of(AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT), "success"));

        ValidatingSpanProcessor validator = errorLevel();
        try (SpanProcessorHarness h = SpanProcessorHarness.of(validator, resourceWithResult)) {
            Tracer tracer = h.tracer("test");
            // platform.trace.type Ð·Ð°Ð´Ð°Ð½ Ð½Ð° span'Ðµ, Ñ‚Ð¾Ð»ÑŒÐºÐ¾ result â€” Ð½ÐµÑ‚
            io.opentelemetry.api.trace.SpanBuilder builder = tracer.spanBuilder("op")
                    .setAttribute(PlatformAttributes.PLATFORM_TYPE, "http_server");

            // when
            builder.startSpan().end();

            String missing = h.exporter().getFinishedSpanItems().getFirst().getAttributes()
                    .get(AttributeKey.stringKey("platform.validation.missing"));
            // then
            assertThat(missing).isEqualTo(PlatformAttributes.PLATFORM_RESULT);
            assertThat(validator.getValidationViolationsTotal()).isEqualTo(1L);
        }
    }

    @Test
    void strictValidationAcceptsTraceTypeAndTraceResultSpanAttributes() {
        // given
        ValidatingSpanProcessor validator = errorLevel();
        try (SpanProcessorHarness h = SpanProcessorHarness.of(validator, Resource.empty())) {
            Tracer tracer = h.tracer("test");
            // ÐžÐ±Ð° Ð¾Ð±ÑÐ·Ð°Ñ‚ÐµÐ»ÑŒÐ½Ñ‹Ñ… Ð°Ñ‚Ñ€Ð¸Ð±ÑƒÑ‚Ð° Ð·Ð°Ð´Ð°Ð½Ñ‹ Ð½Ð° span'Ðµ â€” Ð¸ÑÐºÐ»ÑŽÑ‡ÐµÐ½Ð¸Ñ Ð±Ñ‹Ñ‚ÑŒ Ð½Ðµ Ð´Ð¾Ð»Ð¶Ð½Ð¾
            tracer.spanBuilder("op")
                    .setAttribute(PlatformAttributes.PLATFORM_TYPE, "http_server")
                    .setAttribute(PlatformAttributes.PLATFORM_RESULT, "success")
                    // when
                    .startSpan().end();

            Attributes attrs = h.exporter().getFinishedSpanItems().getFirst().getAttributes();
            // then
            assertThat(attrs.get(AttributeKey.stringKey("platform.validation.missing"))).isNull();
        }
    }

    @Test
    void warnLevelValidationDoesNotThrowWhenRequiredSpanAttributesExistOnlyOnResource() {
        // given
        Resource resourceWithBoth = Resource.create(Attributes.builder()
                .put(PlatformAttributes.PLATFORM_TYPE, "http_server")
                .put(PlatformAttributes.PLATFORM_RESULT, "success")
                .build());

        // WARN (strict=false) â€” Ð½Ðµ Ð´Ð¾Ð»Ð¶ÐµÐ½ Ð±Ñ€Ð¾ÑÐ°Ñ‚ÑŒ
        try (SpanProcessorHarness h = SpanProcessorHarness.of(warnLevel(), resourceWithBoth)) {
            Tracer tracer = h.tracer("test");
            Span span = tracer.spanBuilder("op").startSpan();
            // when
            span.end();

            SpanData data = h.exporter().getFinishedSpanItems().getFirst();
            String missing = data.getAttributes().get(AttributeKey.stringKey("platform.validation.missing"));
            // then
            assertThat(missing)
                    .contains(PlatformAttributes.PLATFORM_TYPE)
                    .contains(PlatformAttributes.PLATFORM_RESULT);
        }
    }
}
