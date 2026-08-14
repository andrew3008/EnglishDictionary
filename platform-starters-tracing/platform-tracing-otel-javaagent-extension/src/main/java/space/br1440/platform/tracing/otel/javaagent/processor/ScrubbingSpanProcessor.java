package space.br1440.platform.tracing.otel.javaagent.processor;

import java.util.List;
import java.util.Objects;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.configuration.MaskedSecret;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntimeSupport;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;

/**
 * Удаляет чувствительные данные из значений атрибутов span'ов до экспорта.
 * <p>
 * Работает на этапе {@code onEnding} (когда {@link ReadWriteSpan} ещё допускает перезапись
 * атрибутов) и делегирует всю политику/HMAC/счётчики в session-scoped {@link ScrubbingRuntime}
 * (ADR-scrubbing-hybrid-processor-export-boundary.md). Должен быть последним делегатом среди
 * всех {@code isOnEndingRequired()} processor'ов в {@code buildDelegates}, чтобы более поздние
 * onEnding-делегаты не могли записать unsanitized span attribute после scrubbing.
 * <p>
 * Конструкторы, принимающие {@code List<SpanAttributeScrubbingRule>} напрямую, сохраняют
 * прежнюю характеризацию (все правила — {@code BUILT_IN}) для существующих unit-тестов и
 * изолированных сценариев; production-путь ({@code PlatformSpanProcessorFactory}) использует
 * {@link #ScrubbingSpanProcessor(ScrubbingRuntime)} с session-shared runtime.
 */
public final class ScrubbingSpanProcessor implements PlatformEndingSpanProcessor, ProcessorDiagnosticIdentity {

    private static final String DIAGNOSTIC_ID = "scrubbing";

    private final ScrubbingRuntime runtime;

    public ScrubbingSpanProcessor(List<SpanAttributeScrubbingRule> rules) {
        this(rules, null, false);
    }

    public ScrubbingSpanProcessor(List<SpanAttributeScrubbingRule> rules, MaskedSecret hmacKey, boolean failFast) {
        this(ScrubbingRuntimeSupport.fromBuiltInRules(rules, hmacKey, failFast));
    }

    public ScrubbingSpanProcessor(ScrubbingRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public String diagnosticId() {
        return DIAGNOSTIC_ID;
    }

    @Override
    public void onStart(@Nonnull Context parentContext, @Nonnull ReadWriteSpan span) {
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public void onEnding(ReadWriteSpan span) {
        runtime.scrubSpanAttributes(span);
    }

    @Override
    public boolean isOnEndingRequired() {
        return true;
    }

    @Override
    public void onEnd(@Nonnull ReadableSpan span) {
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }

    ScrubbingRuntime runtime() {
        return runtime;
    }
}
