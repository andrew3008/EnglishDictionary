package space.br1440.platform.tracing.otel.extension.sampler;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import org.slf4j.LoggerFactory;
import space.br1440.platform.tracing.otel.extension.safety.CircuitBreaker;
import space.br1440.platform.tracing.otel.extension.safety.DegradedModeController;
import space.br1440.platform.tracing.otel.extension.safety.PlatformThrowables;
import space.br1440.platform.tracing.otel.extension.safety.RateLimitedLogger;
import space.br1440.platform.tracing.otel.extension.safety.TracingDiagnostics;

import java.util.List;
import java.util.Objects;

/**
 * Safe-обёртка вокруг платформенного {@link Sampler} (как правило — {@link CompositeSampler}).
 * <p>
 * {@code Sampler.shouldSample(...)} находится на hot-path создания span'а и выполняется на
 * application thread. OTel SDK намеренно <b>не</b> оборачивает его в try/catch — полагается на
 * контракт «should not throw». Поскольку платформенный sampler содержит кастомные правила
 * (force/QA/drop-paths/ratio), любое их падение ушло бы прямо в бизнес-поток при создании span'а.
 * Эта обёртка закрывает дыру (Фаза 11, §36/1 — неблокирующее поведение).
 *
 * <h2>Стратегия fallback (рекомендация Фаза 11.md)</h2>
 * <ol>
 *   <li><b>last-known-good</b> — основной путь: {@link CompositeSampler} уже принимает решения на
 *       базе последнего валидного {@link SamplerState} из {@link SamplerStateHolder}; пока делегат
 *       работает, действует именно он;</li>
 *   <li><b>fail-closed fallback</b> — если делегат бросил исключение или вернул {@code null},
 *       возвращается {@link SamplingDecision#DROP}; policy failure не может увеличить sampling;</li>
 * </ol>
 *
 * <p><b>Наблюдаемость:</b> каждое падение делегата инкрементит {@code sampler.failures} в
 * {@link TracingDiagnostics}; лог — rate-limited (без «log storm» при стабильно падающем правиле).
 *
 * <p><b>Pattern, not source copy</b> (ADR-otel-direct-integration).
 */
public final class SafeSampler implements Sampler, PlatformManagedSampler {

    private final Sampler delegate;
    private final TracingDiagnostics diagnostics;
    private final DegradedModeController degradedController;
    private final RateLimitedLogger log;

    SafeSampler(Sampler delegate) {
        this(delegate, TracingDiagnostics.shared());
    }

    SafeSampler(Sampler delegate, TracingDiagnostics diagnostics) {
        this(delegate, diagnostics, new DegradedModeController(diagnostics));
    }

    SafeSampler(Sampler delegate, TracingDiagnostics diagnostics,
                DegradedModeController degradedController) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.degradedController = Objects.requireNonNull(degradedController, "degradedController");
        this.log = new RateLimitedLogger(LoggerFactory.getLogger(SafeSampler.class));
    }

    @Override
    public SamplingResult shouldSample(Context parentContext, String traceId, String name,
                                       SpanKind spanKind, Attributes attributes,
                                       List<LinkData> parentLinks) {
        // Degraded mode: если делегат стабильно падает, breaker открывается и мы перестаём его
        // дёргать на каждом span'е (экономия CPU + защита от log storm), уходя сразу в fallback.
        // В HALF_OPEN ровно один поток делает пробный вызов делегата.
        CircuitBreaker.State execState = degradedController.acquireExecutionState(DegradedModeController.Component.SAMPLER);
        if (execState != CircuitBreaker.State.OPEN) {
            try {
                SamplingResult result = delegate.shouldSample(
                        parentContext, traceId, name, spanKind, attributes, parentLinks);
                if (result != null) {
                    degradedController.recordSuccess(DegradedModeController.Component.SAMPLER);
                    return result;
                }
                // Контракт Sampler запрещает null, но делегату не доверяем — трактуем как сбой.
                degradedController.recordFailure(DegradedModeController.Component.SAMPLER);
                diagnostics.recordSamplerFailure();
                log.warn("SafeSampler: делегат вернул null SamplingResult — применяю fallback");
            } catch (Throwable ex) {
                PlatformThrowables.propagateIfFatal(ex);
                degradedController.recordFailure(DegradedModeController.Component.SAMPLER);
                diagnostics.recordSamplerFailure();
                log.warn("SafeSampler: делегат shouldSample упал ({}) — применяю fallback", ex.toString());
            }
        }
        // Ошибка policy не должна приводить к permissive sampling или покидать application hot-path.
        return SamplingResult.create(SamplingDecision.DROP);
    }

    @Override
    public String getDescription() {
        try {
            return "SafeSampler{" + delegate.getDescription() + "}";
        } catch (Throwable ex) {
            PlatformThrowables.propagateIfFatal(ex);
            return "SafeSampler{description_unavailable}";
        }
    }

    // toString == getDescription (конвенция OTel Sampler): платформенный sampler виден в
    // SdkTracerProvider.toString()/диагностике, а не как "SafeSampler@hash".
    @Override
    public String toString() {
        return getDescription();
    }

    /**
     * Разворачивает делегат: если внутри — платформенный {@link PlatformManagedSampler}
     * (обычно {@link CompositeSampler}), возвращает его композит; иначе {@code null}.
     * Используется idempotency-guard'ом для перепривязки JMX без повторной обёртки.
     */
    @Override
    public CompositeSampler platformCompositeSampler() {
        if (delegate instanceof PlatformManagedSampler managed) {
            return managed.platformCompositeSampler();
        }
        return null;
    }
}
