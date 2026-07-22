package space.br1440.platform.tracing.otel.javaagent.processor;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.exception.TracingValidationException;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidatingSpanProcessorTest {

    @Test
    void проставляет_platform_validation_missing_когда_span_атрибуты_отсутствуют() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new ValidatingSpanProcessor(false), Resource.empty())) {
            Tracer tracer = h.tracer("test");
            Span span = tracer.spanBuilder("op").startSpan();
            span.end();

            var data = h.exporter().getFinishedSpanItems().get(0);
            String missing = data.getAttributes().get(AttributeKey.stringKey("platform.validation.missing"));
            // После Фазы 9 валидируются span-specific поля type/result (resource-ключи — на старте).
            assertThat(missing)
                    .contains(PlatformAttributes.PLATFORM_TYPE)
                    .contains(PlatformAttributes.PLATFORM_RESULT);
        }
    }

    @Test
    void не_фиксирует_нарушение_когда_span_атрибуты_type_и_result_заданы() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new ValidatingSpanProcessor(false), Resource.empty())) {
            h.tracer("test").spanBuilder("op")
                    .setAttribute(PlatformAttributes.PLATFORM_TYPE, "HTTP")
                    .setAttribute(PlatformAttributes.PLATFORM_RESULT, "success")
                    .startSpan().end();

            var attrs = h.exporter().getFinishedSpanItems().get(0).getAttributes();
            assertThat(attrs.get(AttributeKey.stringKey("platform.validation.missing"))).isNull();
        }
    }

    @Test
    void throttling_подавляет_повторные_warn_и_не_двигает_метку_времени() {
        // Виртуальное время: clock возвращает фиксированную метку 1000 — это эмулирует «сразу же»
        // 1000 span'ов друг за другом в пределах одного throttle-окна.
        ValidatingSpanProcessor validator = new ValidatingSpanProcessor(() -> 1000L, false);

        try (SpanProcessorHarness h = SpanProcessorHarness.of(validator, Resource.empty())) {
            Tracer tracer = h.tracer("test");
            for (int i = 0; i < 1000; i++) {
                tracer.spanBuilder("op").startSpan().end();
            }

            // Все 1000 span'ов получили атрибут platform.validation.missing — это контракт.
            assertThat(h.exporter().getFinishedSpanItems()).hasSize(1000);
            for (var data : h.exporter().getFinishedSpanItems()) {
                assertThat(data.getAttributes().get(AttributeKey.stringKey("platform.validation.missing"))).isNotBlank();
            }
        }

        // Throttling: метка времени для уникального missing-набора зафиксирована один раз и
        // не двигалась повторно — значит, повторные WARN'ы в логе подавлены.
        String missing = PlatformAttributes.PLATFORM_TYPE + "," + PlatformAttributes.PLATFORM_RESULT;
        assertThat(validator.lastWarnTimestampForTesting(missing)).isEqualTo(1000L);
        assertThat(validator.trackedKeysSizeForTesting()).isEqualTo(1);
    }

    @Test
    void cap_ограничивает_рост_кэша_rate_limiter_single_threaded() {
        // Single-threaded путь: подаём 200 уникальных missing-значений строго последовательно;
        // soft cap при таком сценарии гарантированно равен MAX_TRACKED_KEYS (TOCTOU отсутствует,
        // когда нет конкуренции потоков).
        ValidatingSpanProcessor validator = new ValidatingSpanProcessor(() -> 0L, false);

        for (int i = 0; i < 200; i++) {
            validator.recordMissingForTesting("op-" + i, "missing-key-" + i);
        }

        assertThat(validator.trackedKeysSizeForTesting()).isEqualTo(ValidatingSpanProcessor.MAX_TRACKED_KEYS);
    }

    @Test
    void strict_mode_выбрасывает_исключение_когда_атрибуты_отсутствуют() {
        ValidatingSpanProcessor validator = new ValidatingSpanProcessor(true);
        try (SpanProcessorHarness h = SpanProcessorHarness.of(validator, Resource.empty())) {
            Tracer tracer = h.tracer("test");
            io.opentelemetry.api.trace.SpanBuilder builder = tracer.spanBuilder("op");
            
            assertThatThrownBy(() -> builder.startSpan().end())
                    .isInstanceOf(TracingValidationException.class)
                    .hasMessageContaining("экспортируется без обязательных платформенных атрибутов");
        }
    }

    // -------------------------------------------------------------------------
    // SP-03 — Resource fallback removal characterization
    // -------------------------------------------------------------------------

    /**
     * SP-03 / Test 1.
     * platform.trace.type на Resource не засчитывается как span-атрибут: валидация
     * обнаруживает его как отсутствующий и в STRICT-режиме бросает исключение.
     */
    @Test
    void strictValidationDoesNotUseResourceAttributeAsTraceTypeFallback() {
        // platform.trace.type задан только как resource-атрибут, NOT on span
        Resource resourceWithType = Resource.create(
                Attributes.of(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TYPE), "http_server"));

        ValidatingSpanProcessor validator = new ValidatingSpanProcessor(true);
        try (SpanProcessorHarness h = SpanProcessorHarness.of(validator, resourceWithType)) {
            Tracer tracer = h.tracer("test");
            // platform.trace.result задан на span'е, только type — нет
            io.opentelemetry.api.trace.SpanBuilder builder = tracer.spanBuilder("op")
                    .setAttribute(PlatformAttributes.PLATFORM_RESULT, "success");

            assertThatThrownBy(() -> builder.startSpan().end())
                    .isInstanceOf(TracingValidationException.class)
                    .hasMessageContaining(PlatformAttributes.PLATFORM_TYPE);
        }
    }

    /**
     * SP-03 / Test 2.
     * platform.trace.result на Resource не засчитывается как span-атрибут: валидация
     * обнаруживает его как отсутствующий и в STRICT-режиме бросает исключение.
     */
    @Test
    void strictValidationDoesNotUseResourceAttributeAsTraceResultFallback() {
        // platform.trace.result задан только как resource-атрибут, NOT on span
        Resource resourceWithResult = Resource.create(
                Attributes.of(AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT), "success"));

        ValidatingSpanProcessor validator = new ValidatingSpanProcessor(true);
        try (SpanProcessorHarness h = SpanProcessorHarness.of(validator, resourceWithResult)) {
            Tracer tracer = h.tracer("test");
            // platform.trace.type задан на span'е, только result — нет
            io.opentelemetry.api.trace.SpanBuilder builder = tracer.spanBuilder("op")
                    .setAttribute(PlatformAttributes.PLATFORM_TYPE, "http_server");

            assertThatThrownBy(() -> builder.startSpan().end())
                    .isInstanceOf(TracingValidationException.class)
                    .hasMessageContaining(PlatformAttributes.PLATFORM_RESULT);
        }
    }

    /**
     * SP-03 / Test 3.
     * Span-атрибуты platform.trace.type и platform.trace.result удовлетворяют валидацию
     * в STRICT-режиме — основной положительный сценарий после SP-03.
     */
    @Test
    void strictValidationAcceptsTraceTypeAndTraceResultSpanAttributes() {
        ValidatingSpanProcessor validator = new ValidatingSpanProcessor(true);
        try (SpanProcessorHarness h = SpanProcessorHarness.of(validator, Resource.empty())) {
            Tracer tracer = h.tracer("test");
            // Оба обязательных атрибута заданы на span'е — исключения быть не должно
            tracer.spanBuilder("op")
                    .setAttribute(PlatformAttributes.PLATFORM_TYPE, "http_server")
                    .setAttribute(PlatformAttributes.PLATFORM_RESULT, "success")
                    .startSpan().end();

            var attrs = h.exporter().getFinishedSpanItems().get(0).getAttributes();
            assertThat(attrs.get(AttributeKey.stringKey("platform.validation.missing"))).isNull();
        }
    }

    /**
     * SP-03 / Test 4.
     * LENIENT-режим не бросает исключение, даже когда обязательные атрибуты существуют
     * только как resource-атрибуты (т.е. ресурсный fallback удалён, поле считается
     * пропущенным, но LENIENT не падает — только проставляет диагностический атрибут).
     */
    @Test
    void lenientValidationDoesNotThrowWhenRequiredSpanAttributesExistOnlyOnResource() {
        Resource resourceWithBoth = Resource.create(Attributes.builder()
                .put(PlatformAttributes.PLATFORM_TYPE, "http_server")
                .put(PlatformAttributes.PLATFORM_RESULT, "success")
                .build());

        // LENIENT (strict=false) — не должен бросать
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new ValidatingSpanProcessor(false), resourceWithBoth)) {
            Tracer tracer = h.tracer("test");
            Span span = tracer.spanBuilder("op").startSpan();
            span.end(); // должен завершиться без исключения

            // Атрибуты заданы только на resource, не на span →
            // после SP-03 валидатор считает их отсутствующими и проставляет missing
            var data = h.exporter().getFinishedSpanItems().get(0);
            String missing = data.getAttributes().get(AttributeKey.stringKey("platform.validation.missing"));
            assertThat(missing)
                    .contains(PlatformAttributes.PLATFORM_TYPE)
                    .contains(PlatformAttributes.PLATFORM_RESULT);
        }
    }
}
