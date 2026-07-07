package space.br1440.platform.tracing.otel.extension.processor;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.internal.ExtendedSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.core.validation.ValidationSnapshot;
import space.br1440.platform.tracing.otel.extension.exception.TracingValidationException;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Валидирует наличие обязательных <b>span-specific</b> атрибутов на span'е перед экспортом (§1/§8).
 * <p>
 * Минимально требуемый набор: {@code platform.trace.type}, {@code platform.trace.result}.
 * <p>
 * <b>Resource-идентичность</b> ({@code service.name}, {@code platform.c_group}, {@code platform.id})
 * здесь <b>не проверяется</b>: эти ключи стабильны per-process и валидируются один раз на старте
 * в {@link space.br1440.platform.tracing.otel.extension.resource.ResourceValidationDiagnostics}
 * (STRICT fail-fast / LENIENT warn). Проверка resource-ключей на каждом span была бы дублированием
 * и hot-path overhead. Если span-атрибут не задан, валидатор пишет предупреждение и проставляет
 * служебный {@code platform.validation.missing}, чтобы команда наблюдаемости отследила нарушение.
 *
 * <h2>Защита от лог-шторма</h2>
 * При misconfigured deploy все span'ы могут попасть на одну и ту же ветку «не задано» — это
 * генерирует один WARN на каждый span (10k WARN/sec при 10k span/sec). Валидатор использует
 * rate-limiter: для каждого уникального набора пропущенных ключей фиксируется первая запись и
 * подавляются последующие в течение {@link #THROTTLE_INTERVAL}. Span сам по себе всегда получает
 * атрибут {@code platform.validation.missing} — throttling касается только лог-вывода.
 * <p>
 * Размер кэша rate-limiter ограничен {@link #MAX_TRACKED_KEYS} (soft cap): защита от
 * unbounded growth при патологическом случае, когда missing-набор содержит динамические значения
 * и каждый span приносит новый ключ. Точная инвариантность {@code size <= MAX_TRACKED_KEYS}
 * не гарантируется (TOCTOU между {@code size()} и {@code computeIfAbsent} в {@link ConcurrentHashMap}),
 * но это допустимо: цель — предотвратить рост до миллионов ключей, а не строгое равенство.
 */
public final class ValidatingSpanProcessor implements ExtendedSpanProcessor {

    private static final Logger log = LoggerFactory.getLogger(ValidatingSpanProcessor.class);

    private static final AttributeKey<String> PLATFORM_TYPE_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_TYPE);
    private static final AttributeKey<String> PLATFORM_RESULT_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT);
    private static final AttributeKey<String> VALIDATION_MISSING_KEY = AttributeKey.stringKey("platform.validation.missing");

    /** Минимальный интервал между WARN-записями для одинакового набора пропущенных атрибутов. */
    static final Duration THROTTLE_INTERVAL = Duration.ofSeconds(60);

    /** Soft cap на размер кэша rate-limiter: защита от unbounded growth. */
    static final int MAX_TRACKED_KEYS = 100;

    /**
     * Значение «WARN никогда не выпускался» — заведомо меньше любой реальной метки
     * {@link System#currentTimeMillis()}. Используется как initial value, чтобы первый WARN
     * всегда проходил сквозь throttling-условие {@code now - previous >= threshold}.
     */
    private static final long NEVER_WARNED = Long.MIN_VALUE / 2;

    private final ConcurrentMap<String, AtomicLong> lastWarnByKey = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    /**
     * Runtime-политика валидации (Фаза 14): {@code enabled} + {@code strict}. Hot-path
     * {@code onEnding} читает {@code policyHolder.current()} lock-free; обновление — через
     * {@link #updateValidationPolicy(boolean, boolean)} (атомарно, last-known-good).
     */
    private final ValidationPolicyHolder policyHolder;

    public ValidatingSpanProcessor(boolean strictMode) {
        this(System::currentTimeMillis, strictMode, false);
    }

    /**
     * @param strictMode startup strict validation mode from {@code platform.tracing.validation.strict}
     * @param strictRuntimeAllowed startup-only guard (PR-9F); when {@code false}, runtime updates
     *                             cannot enable {@code strict=true}
     */
    public ValidatingSpanProcessor(boolean strictMode, boolean strictRuntimeAllowed) {
        this(System::currentTimeMillis, strictMode, strictRuntimeAllowed);
    }

    /**
     * Конструктор для unit-тестов: позволяет управлять виртуальным временем при проверке throttling'а.
     */
    ValidatingSpanProcessor(LongSupplier clock, boolean strictMode) {
        this(clock, strictMode, false);
    }

    ValidatingSpanProcessor(LongSupplier clock, boolean strictMode, boolean strictRuntimeAllowed) {
        this.clock = clock;
        // Стартовый снимок (enabled=true) — startup-init из agent ConfigProperties: фабрика создаёт
        // процессор только при platform.tracing.validation.enabled=true, strict из props.
        this.policyHolder = new ValidationPolicyHolder(
                ValidationSnapshot.fromPolicy(true, strictMode, 1, Instant.now(), "startup"),
                strictRuntimeAllowed);
        if (strictMode) {
            log.warn(
                    "Validation strict mode is enabled at startup. Strict mode is intended for "
                            + "CI/test/pre-prod diagnostics and may throw from Span.end() on the application "
                            + "thread. Do not enable in production unless explicitly approved.");
        }
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        // не используется
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public void onEnding(ReadWriteSpan span) {
        // Снимок политики читается lock-free (Фаза 14). enabled=false → passthrough (без валидации).
        ValidationSnapshot snapshot = policyHolder.current();
        if (!snapshot.enabled()) {
            return;
        }

        StringBuilder missing = null;

        missing = appendIfMissing(missing, span, PLATFORM_TYPE_KEY, PlatformAttributes.PLATFORM_TYPE);
        missing = appendIfMissing(missing, span, PLATFORM_RESULT_KEY, PlatformAttributes.PLATFORM_RESULT);

        if (missing != null) {
            String missingValue = missing.toString();
            span.setAttribute(VALIDATION_MISSING_KEY, missingValue);
            if (snapshot.strict()) {
                throw new TracingValidationException("Span '" + span.getName() + "' экспортируется без обязательных платформенных атрибутов: " + missingValue);
            } else {
                warnThrottled(span.getName(), missingValue);
            }
        }
    }

    /**
     * Логирует WARN не чаще одного раза в {@link #THROTTLE_INTERVAL} на уникальный
     * {@code missingValue}. Если кэш достиг {@link #MAX_TRACKED_KEYS}, новые наборы
     * выводятся одной строкой без сохранения метки (cap reached) — это исключает рост кэша.
     */
    private void warnThrottled(String spanName, String missingValue) {
        long now = clock.getAsLong();
        // Soft cap: при конкуренции size() может превысить MAX_TRACKED_KEYS на число потоков —
        // это допустимо для diagnostic-фичи, цель — не пустить рост до миллионов ключей.
        if (lastWarnByKey.size() < MAX_TRACKED_KEYS) {
            lastWarnByKey.computeIfAbsent(missingValue, k -> new AtomicLong(NEVER_WARNED));
        }
        AtomicLong lastWarn = lastWarnByKey.get(missingValue);
        if (lastWarn == null) {
            // Кэш переполнен: не сохраняем ключ, но один WARN всё равно выпускаем (с пометкой).
            log.warn("Span '{}' экспортируется без обязательных платформенных атрибутов: {} (rate-limiter cap reached)",
                    spanName, missingValue);
            return;
        }
        long previous = lastWarn.get();
        long thresholdMs = THROTTLE_INTERVAL.toMillis();
        if (now - previous >= thresholdMs && lastWarn.compareAndSet(previous, now)) {
            log.warn("Span '{}' экспортируется без обязательных платформенных атрибутов: {}",
                    spanName, missingValue);
        }
    }

    @Override
    public boolean isOnEndingRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        // не используется
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode forceFlush() {
        return CompletableResultCode.ofSuccess();
    }

    // -- Runtime-политика (Фаза 14) ---------------------------------------------------------------

    /** Текущий runtime-флаг валидации (snapshot). */
    public boolean isEnabled() {
        return policyHolder.current().enabled();
    }

    /** Текущий runtime-флаг строгого режима (snapshot). */
    public boolean isStrict() {
        return policyHolder.current().strict();
    }

    /**
     * Startup-only guard (PR-9F): {@code true} if runtime updates may enable strict mode.
     * Not part of the mutable policy snapshot.
     */
    public boolean isStrictRuntimeAllowed() {
        return policyHolder.isStrictRuntimeAllowed();
    }

    /** Версия текущего снимка политики валидации. */
    public long getPolicyVersion() {
        return policyHolder.version();
    }

    /** Источник текущего снимка политики (startup / JMX / spring-runtime-config). */
    public String getPolicySource() {
        return policyHolder.current().source();
    }

    /**
     * Атомарно обновляет политику валидации одним вызовом (Фаза 14, один домен = один апдейт):
     * runtime enable/disable + strict/warn.
     *
     * @return {@code true}, если применено; {@code false} — сохранён last-known-good
     */
    public boolean updateValidationPolicy(boolean enabled, boolean strict) {
        return tryApplyPolicyUpdate(enabled, strict, "JMX");
    }

    /**
     * Атомарно публикует полную политику валидации (PR-8A): validate → build snapshot → CAS.
     *
     * @return {@code true} if published; {@code false} if last-known-good retained
     */
    public boolean tryApplyPolicyUpdate(boolean enabled, boolean strict, String source) {
        return policyHolder.tryApplyPolicyUpdate(enabled, strict, source);
    }

    /** Размер кэша rate-limiter — package-private accessor для тестов. */
    int trackedKeysSizeForTesting() {
        return lastWarnByKey.size();
    }

    /**
     * Метка времени последнего WARN'а для конкретного набора пропущенных атрибутов
     * (или {@code -1L}, если такой набор ни разу не отметился) — package-private accessor для тестов.
     */
    long lastWarnTimestampForTesting(String missingValue) {
        AtomicLong v = lastWarnByKey.get(missingValue);
        return v == null ? -1L : v.get();
    }

    /**
     * Прямой вызов throttled-WARN с произвольным {@code missingValue} — package-private,
     * используется только тестами для точечной проверки {@link #MAX_TRACKED_KEYS}-cap'а
     * без необходимости конструировать 100+ разных Resource'ов через реальный SDK.
     */
    void recordMissingForTesting(String spanName, String missingValue) {
        warnThrottled(spanName, missingValue);
    }

    private static StringBuilder appendIfMissing(StringBuilder accumulator, ReadWriteSpan span,
                                                 AttributeKey<String> key, String name) {
        if (isPresent(span, key)) {
            return accumulator;
        }
        // Атрибут не задан на span'е — записываем как пропущенный.
        StringBuilder out = accumulator == null ? new StringBuilder() : accumulator.append(',');
        out.append(name);
        return out;
    }

    private static boolean isPresent(ReadWriteSpan span, AttributeKey<String> key) {
        // SP-03: валидируются только span-атрибуты. Resource-атрибуты не заменяют span-specific
        // поля platform.trace.type / platform.trace.result: они имеют разную семантику
        // (service-level vs per-request) и их присутствие на Resource не означает корректной
        // инструментации span'а. Проверка ресурсных ключей (service.name и т.д.) производится
        // отдельно на старте через ResourceValidationDiagnostics, а не в hot-path onEnding.
        return span.getAttribute(key) != null;
    }
}
