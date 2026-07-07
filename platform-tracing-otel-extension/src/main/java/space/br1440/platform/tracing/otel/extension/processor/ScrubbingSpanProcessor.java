package space.br1440.platform.tracing.otel.extension.processor;


import io.opentelemetry.api.common.AttributeType;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.internal.ExtendedSpanProcessor;
import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SensitiveDataRule;
import space.br1440.platform.tracing.otel.extension.scrubbing.IpPrefixTruncator;
import space.br1440.platform.tracing.otel.extension.scrubbing.IpAddressRule;
import space.br1440.platform.tracing.otel.extension.scrubbing.ScrubbingPolicyHolder;
import space.br1440.platform.tracing.otel.extension.scrubbing.ScrubbingSnapshot;
import space.br1440.platform.tracing.otel.extension.scrubbing.engine.MergeEngine;
import space.br1440.platform.tracing.otel.extension.scrubbing.engine.RuleExecutionWrapper;
import space.br1440.platform.tracing.otel.extension.utils.Strings;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

/**
 * Вычищает чувствительные данные из значений атрибутов span'ов до экспорта.
 * <p>
 * Работает на этапе {@code onEnding} (когда {@link ReadWriteSpan} ещё допускает перезапись
 * атрибутов, в т.ч. добавленных обогатителями). Для каждого атрибута применяется первое
 * сработавшее правило в порядке возрастания {@link SensitiveDataRule#priority()} — поэтому при
 * конфликте побеждает правило с меньшим приоритетом (как правило, DROP).
 * <p>
 * <b>Type-aware.</b> Обходятся атрибуты всех типов (STRING, массивы, LONG/DOUBLE/BOOLEAN), иначе
 * нестроковые sensitive-значения (geo {@code DOUBLE}, {@code imei} {@code LONG}) молча обходили бы
 * scrubbing. Правило отдаёт только {@link ScrubbingDecision} (намерение); новое значение
 * вычисляет процессор с учётом типа:
 * <table>
 *   <tr><th>Тип</th><th>MASK</th><th>DROP</th><th>HASH</th><th>TRUNCATE</th></tr>
 *   <tr><td>STRING</td><td>{@code ***}</td><td>{@code ""}</td><td>HMAC-hex</td><td>substring / IP-prefix</td></tr>
 *   <tr><td>numeric/boolean</td><td>→DROP</td><td>type-neutral sentinel</td><td>→DROP</td><td>→KEEP</td></tr>
 * </table>
 * <p>
 * <b>Контракт DROP.</b> У {@link ReadWriteSpan} нет remove-API, поэтому для уже записанного
 * атрибута DROP реализуется как overwrite пустой строкой {@code ""} (STRING) или type-neutral
 * sentinel ({@code 0}/{@code 0.0}/{@code false}). Это implementation fallback, а не семантическое
 * значение; sentinel нельзя интерпретировать как реальные данные. <b>Никогда {@code null}</b> —
 * иначе {@code setAttribute} игнорируется SDK и секрет остаётся в span.
 * <p>
 * Процессор мутирует <b>только span attributes</b>. Events/links (включая {@code exception.message})
 * через {@link ReadWriteSpan} не модифицируются — это safety net на стороне Collector / guardrail
 * platform API.
 */
@Slf4j
public final class ScrubbingSpanProcessor implements ExtendedSpanProcessor {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String MASK = "***";
    /** Заполнитель fail-closed: используется, когда правило упало / circuit breaker открыт. */
    private static final String FAILED = ScrubbingDecision.SCRUBBING_FAILED_REASON;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /**
     * Runtime-политика scrubbing'а (Фаза 14 / PR-7A–7B): enabled + скомпилированные обёртки правил.
     * Hot-path {@code onEnding} читает {@code policyHolder.current()} lock-free; обновление —
     * через {@link #tryApplyPolicyUpdate(boolean, String[], String)} (атомарно, last-known-good).
     */
    private final ScrubbingPolicyHolder policyHolder;
    private final byte[] hmacKey;
    private final boolean hasHmacKey;

    /** {@code Mac} не потокобезопасен → отдельный экземпляр на поток, инициализируется ключом один раз. */
    private final ThreadLocal<Mac> threadLocalMac;

    // Low-cardinality счётчики для JMX (см. PlatformTracingJmxRegistrar).
    private final LongAdder droppedActions = new LongAdder();
    private final LongAdder hashedActions = new LongAdder();
    private final LongAdder maskedActions = new LongAdder();
    private final LongAdder truncatedActions = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder hashMissingKey = new LongAdder();

    /**
     * Конструктор без HMAC-ключа: HASH деградирует до MASK (политика {@code mask}).
     * Удобен для тестов и окружений, где корреляция по хэшу не нужна.
     */
    public ScrubbingSpanProcessor(List<SensitiveDataRule> rules) {
        this(rules, null, false);
    }

    /**
     * @param rules      правила scrubbing'а (будут отсортированы по {@code priority()})
     * @param hmacKey    секретный ключ HMAC-SHA256 (UTF-8 строка); {@code null}/blank — ключа нет
     * @param failFast   если {@code true} и ключ отсутствует — отказ на старте (политика {@code fail-fast})
     */
    public ScrubbingSpanProcessor(List<SensitiveDataRule> rules, String hmacKey, boolean failFast) {
        // Каждое правило оборачивается собственным circuit breaker'ом (изоляция ошибок, PR-4),
        // затем список clamp'ится и детерминированно сортируется (PR-3). Стартовый снимок
        // (enabled=true) — startup-init из agent ConfigProperties (фабрика создаёт процессор только
        // при platform.tracing.scrubbing.enabled=true).
        // Стартовый снимок (enabled=true) — startup-init из agent ConfigProperties.
        this.policyHolder = new ScrubbingPolicyHolder(
                ScrubbingSnapshot.fromRules(true, rules, 1, Instant.now(), "startup"));

        this.hasHmacKey = Strings.isNotBlank(hmacKey);
        if (!hasHmacKey && failFast) {
            throw new IllegalStateException(
                    "HMAC-ключ scrubbing не задан, а missing-key-policy=fail-fast: "
                            + "задайте platform.tracing.scrubbing.hmac-key");
        }
        this.hmacKey = hasHmacKey ? hmacKey.getBytes(StandardCharsets.UTF_8) : null;
        this.threadLocalMac = hasHmacKey ? ThreadLocal.withInitial(this::newMac) : null;
    }

    private Mac newMac() {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(hmacKey, HMAC_ALGORITHM));
            return mac;
        } catch (GeneralSecurityException e) {
            // HmacSHA256 гарантирован JCA-спецификацией; сюда попасть в норме невозможно.
            throw new IllegalStateException("Не удалось инициализировать HMAC-SHA256", e);
        }
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        // Scrubbing выполняется на onEnding, чтобы захватить атрибуты, добавленные обогатителями.
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public void onEnding(ReadWriteSpan span) {
        try {
            // Снимок политики читается lock-free (Фаза 14). enabled=false → passthrough.
            ScrubbingSnapshot snapshot = policyHolder.current();
            if (!snapshot.enabled()) {
                return;
            }
            List<RuleExecutionWrapper> wrappers = snapshot.wrappers();
            if (wrappers.isEmpty()) {
                return;
            }
            Attributes attributes = span.toSpanData().getAttributes();
            attributes.forEach((key, value) -> {
                // Нормализация ключа выполняется РОВНО ОДИН РАЗ перед циклом правил и передаётся
                // и в supports(), и в evaluate() (контракт SPI).
                String normalizedKey = normalizeForSpi(key.getKey());
                ScrubbingDecision decision = MergeEngine.evaluate(wrappers, normalizedKey, value);
                if (decision.action() == ScrubbingAction.KEEP) {
                    return;
                }
                apply(span, key, value, decision);
            });
        } catch (Throwable t) {
            // Последний рубеж: изоляция ошибок отдельных правил уже выполнена в RuleExecutionWrapper,
            // сюда попадают только катастрофические сбои самого процессора. Без печати в System.err.
            log.error("[scrubbing] onEnding завершился аварийно: {}", t.toString());
        }
    }

    /**
     * Нормализация ключа для SPI-контракта: lower-case ({@link Locale#ROOT} — защита от
     * Turkish-I), trim. Структурные разделители {@code .} сохраняются.
     */
    static String normalizeForSpi(String key) {
        if (key == null) {
            return "";
        }
        return key.toLowerCase(Locale.ROOT).trim();
    }

    @SuppressWarnings("unchecked")
    private void apply(ReadWriteSpan span, AttributeKey<?> key, Object value, ScrubbingDecision decision) {
        AttributeType type = key.getType();

        // Fail-closed (правило упало / circuit breaker открыт): отдельный путь collapse, НЕ
        // переиспользующий обычную поэлементную логику — чтобы не утекли длина массива и данные.
        boolean failClosed = decision.action() == ScrubbingAction.MASK && FAILED.equals(decision.reason());
        if (failClosed) {
            applyFailClosed(span, key, type);
            return;
        }

        switch (type) {
            case STRING -> {
                String result = transformString((String) value, decision);
                if (result != null) {
                    span.setAttribute((AttributeKey<String>) key, result);
                }
            }
            case STRING_ARRAY -> {
                List<String> in = (List<String>) value;
                List<String> out = new ArrayList<>(in.size());
                for (String element : in) {
                    String transformed = transformString(element, decision);
                    out.add(transformed != null ? transformed : element);
                }
                span.setAttribute((AttributeKey<List<String>>) key, out);
            }
            case LONG, DOUBLE, BOOLEAN, LONG_ARRAY, DOUBLE_ARRAY, BOOLEAN_ARRAY ->
                    applyNonString(span, key, value, decision, type);
            default -> {
                // неизвестный тип — безопасно игнорируем
            }
        }
    }

    /**
     * Применяет fail-closed заполнитель type-safe способом:
     * <ul>
     *   <li>STRING → {@code "<SCRUBBING_FAILED>"};</li>
     *   <li>STRING_ARRAY → одноэлементный список {@code ["<SCRUBBING_FAILED>"]} — длина исходного
     *       массива скрыта (защита от side-channel);</li>
     *   <li>numeric/boolean (и их массивы) → type-neutral sentinel (эквивалент DROP).</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private void applyFailClosed(ReadWriteSpan span, AttributeKey<?> key, AttributeType type) {
        maskedActions.increment();
        switch (type) {
            case STRING -> span.setAttribute((AttributeKey<String>) key, FAILED);
            case STRING_ARRAY -> span.setAttribute((AttributeKey<List<String>>) key, List.of(FAILED));
            case LONG -> span.setAttribute((AttributeKey<Long>) key, 0L);
            case DOUBLE -> span.setAttribute((AttributeKey<Double>) key, 0.0d);
            case BOOLEAN -> span.setAttribute((AttributeKey<Boolean>) key, Boolean.FALSE);
            case LONG_ARRAY -> span.setAttribute((AttributeKey<List<Long>>) key, List.of(0L));
            case DOUBLE_ARRAY -> span.setAttribute((AttributeKey<List<Double>>) key, List.of(0.0d));
            case BOOLEAN_ARRAY -> span.setAttribute((AttributeKey<List<Boolean>>) key, List.of(Boolean.FALSE));
            default -> {
                // неизвестный тип — безопасно игнорируем
            }
        }
    }

    /**
     * Трансформация строкового значения. Возвращает новое значение либо {@code null}, если
     * перезапись не требуется (например TRUNCATE→KEEP неприменимо к STRING, но fallback нужен).
     */
    private String transformString(String value, ScrubbingDecision decision) {
        if (value == null) {
            return null;
        }
        switch (decision.action()) {
            case MASK -> {
                maskedActions.increment();
                return MASK;
            }
            case DROP -> {
                droppedActions.increment();
                return ""; // overwrite, никогда не null
            }
            case HASH -> {
                if (!hasHmacKey) {
                    // missing-key-policy=mask: деградируем до MASK, фиксируем метрику.
                    hashMissingKey.increment();
                    maskedActions.increment();
                    return MASK;
                }
                hashedActions.increment();
                return hmacHex(value);
            }
            case TRUNCATE -> {
                truncatedActions.increment();
                if (IpAddressRule.REASON.equals(decision.reason())) {
                    String truncated = IpPrefixTruncator.truncate(value);
                    if (truncated == null) {
                        // невалидный IP — не падаем: fallback MASK + метрика failures.
                        failures.increment();
                        return MASK;
                    }
                    return truncated;
                }
                int max = decision.maxLength();
                if (max >= 0 && value.length() > max) {
                    return value.substring(0, max);
                }
                return null; // усечение не требуется
            }
            default -> {
                return null;
            }
        }
    }

    private void applyNonString(ReadWriteSpan span, AttributeKey<?> key, Object value,
                                ScrubbingDecision decision, AttributeType type) {
        // Для нестроковых типов значимы только DROP (sentinel). MASK/HASH сводятся к DROP,
        // TRUNCATE — к KEEP (no-op). См. контракт в javadoc класса.
        switch (decision.action()) {
            case MASK, DROP, HASH -> {
                droppedActions.increment();
                writeSentinel(span, key, value, type);
            }
            case TRUNCATE -> {
                // KEEP-эквивалент для чисел/boolean — ничего не делаем.
            }
            default -> {
                // KEEP сюда не попадает
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void writeSentinel(ReadWriteSpan span, AttributeKey<?> key, Object value, AttributeType type) {
        switch (type) {
            case LONG -> span.setAttribute((AttributeKey<Long>) key, 0L);
            case DOUBLE -> span.setAttribute((AttributeKey<Double>) key, 0.0d);
            case BOOLEAN -> span.setAttribute((AttributeKey<Boolean>) key, Boolean.FALSE);
            case LONG_ARRAY -> {
                int size = ((List<Long>) value).size();
                List<Long> out = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    out.add(0L);
                }
                span.setAttribute((AttributeKey<List<Long>>) key, out);
            }
            case DOUBLE_ARRAY -> {
                int size = ((List<Double>) value).size();
                List<Double> out = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    out.add(0.0d);
                }
                span.setAttribute((AttributeKey<List<Double>>) key, out);
            }
            case BOOLEAN_ARRAY -> {
                int size = ((List<Boolean>) value).size();
                List<Boolean> out = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    out.add(Boolean.FALSE);
                }
                span.setAttribute((AttributeKey<List<Boolean>>) key, out);
            }
            default -> {
                // строковые типы здесь не обрабатываются
            }
        }
    }

    private String hmacHex(String value) {
        Mac mac = threadLocalMac.get();
        byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        char[] hex = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int b = digest[i] & 0xFF;
            hex[i * 2] = HEX[b >>> 4];
            hex[i * 2 + 1] = HEX[b & 0x0F];
        }
        return new String(hex);
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

    // -- Метрики для JMX --------------------------------------------------------------------------

    public long getDroppedActions() {
        return droppedActions.sum();
    }

    public long getHashedActions() {
        return hashedActions.sum();
    }

    public long getMaskedActions() {
        return maskedActions.sum();
    }

    public long getTruncatedActions() {
        return truncatedActions.sum();
    }

    public long getFailures() {
        return failures.sum();
    }

    public long getHashMissingKey() {
        return hashMissingKey.sum();
    }

    // -- Runtime-политика (Фаза 14) ---------------------------------------------------------------

    /** Текущий runtime-флаг scrubbing'а (snapshot). */
    public boolean isEnabled() {
        return policyHolder.current().enabled();
    }

    /** Версия текущего снимка политики scrubbing'а. */
    public long getPolicyVersion() {
        return policyHolder.version();
    }

    /** Источник последнего успешного обновления scrubbing'а. */
    public String getPolicySource() {
        return policyHolder.current().source();
    }

    /**
     * Атомарно обновляет политику scrubbing'а одним вызовом (PR-7B).
     *
     * @param ruleNames {@code null} — сохранить текущие правила (меняется только {@code enabled})
     */
    public boolean updateScrubbingPolicy(boolean enabled, List<String> ruleNames) {
        String[] names = ruleNames == null ? null : ruleNames.toArray(new String[0]);
        return tryApplyPolicyUpdate(enabled, names, "JMX");
    }

    /**
     * Validates scrubbing domain for JMX pre-check (throws {@link IllegalArgumentException} on invalid input).
     */
    public void validatePolicyUpdateDomain(String[] ruleNames) {
        policyHolder.validatePolicyUpdateDomain(ruleNames);
    }

    /**
     * Атомарно публикует полную политику scrubbing'а (PR-7B): validate → compile → CAS.
     */
    public boolean tryApplyPolicyUpdate(boolean enabled, String[] ruleNames, String source) {
        return policyHolder.tryApplyPolicyUpdate(enabled, ruleNames, source);
    }

    // -- Наблюдаемость circuit breaker'ов (PR-4/PR-6) ---------------------------------------------

    /** Число загруженных правил (после dedup/clamp). */
    public long getRuleCount() {
        return policyHolder.current().wrappers().size();
    }

    /** Число правил, чей circuit breaker сейчас в состоянии OPEN. */
    public long getOpenBreakerCount() {
        return policyHolder.current().wrappers().stream().filter(w -> w.getBreaker().isOpen()).count();
    }

    /** Суммарное число сбоев правил, зафиксированных circuit breaker'ами. */
    public long getTotalBreakerFailures() {
        return policyHolder.current().wrappers().stream().mapToLong(w -> w.getBreaker().getTotalFailures()).sum();
    }
}
