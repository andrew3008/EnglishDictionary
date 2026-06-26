package space.br1440.platform.errorhandling.core.util;

import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fluent builder для формирования диагностических метаданных ({@code diagnosticMetadata})
 * в реализациях {@link space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy}.
 *
 * <p>Устраняет повторяющийся boilerplate из 23 стратегий:
 * создание {@link LinkedHashMap}, вставка {@code exceptionClass},
 * условная вставка через {@link StringUtils#hasText}, вызов
 * {@link CauseMetadataEnricher} и {@link StackHintExtractor}.
 *
 * <h2>Точка входа</h2>
 * <p>Стратегии не создают builder напрямую — они используют
 * {@code AbstractRootCauseExceptionStrategy.diagnosticMetaFor(Throwable)}
 * (см. {@link space.br1440.platform.errorhandling.extension.base.AbstractRootCauseExceptionStrategy}),
 * который делегирует в {@link #forCause(Throwable, StackHintExtractor)}.
 *
 * <h2>Типичное использование</h2>
 * <pre>{@code
 * return diagnosticMetaFor(cause)         // добавляет exceptionClass автоматически
 *     .putIfPresent("vaultAuthBackend", extractBackend(cause))
 *     .withRootCause(CAUSE_CHAIN_DEPTH, MAX_MSG_LENGTH)
 *     .withStackHint()
 *     .build();
 * }</pre>
 *
 * <h2>Гарантии порядка</h2>
 * <p>Используется {@link LinkedHashMap} — порядок вставки сохраняется.
 * Первым ключом всегда будет {@code exceptionClass} (если использован {@link #forCause}).
 *
 * <h2>Гарантии идемпотентности</h2>
 * <ul>
 *   <li>{@link #withRootCause()}, {@link #withRootCause(int, int)},
 *       {@link #withRootCauseClassOnly()} и {@link #withRootCauseClassOnly(int)} бросают
 *       {@link IllegalStateException} при повторном вызове любого из них (одно семейство).</li>
 *   <li>{@link #build()} бросает {@link IllegalStateException} при повторном вызове,
 *       исключая расхождение между двумя результатами при промежуточном {@link #put}.</li>
 * </ul>
 */
public final class DiagnosticMetadataBuilder {

    /**
     * Дефолтная максимальная длина root-cause сообщения для {@link #withRootCause()}.
     *
     * <p>Значение 200 безопасно для большинства стратегий. Стратегии с иным требованием
     * ({@code maxLen=300}: CircuitBreaker, DataAccess, Redis, Messaging, OAuth2)
     * должны явно вызывать {@link #withRootCause(int, int)}.
     *
     * @see CauseMetadataEnricher#DIAGNOSTIC_CAUSE_CHAIN_DEPTH
     */
    public static final int DEFAULT_MAX_MESSAGE_LENGTH = 200;

    private final Throwable cause;
    private final StackHintExtractor stackHintExtractor;
    private final LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
    private boolean rootCauseApplied = false;
    private boolean built = false;

    private DiagnosticMetadataBuilder(@NonNull Throwable cause,
                                      @NonNull StackHintExtractor stackHintExtractor) {
        this.cause = cause;
        this.stackHintExtractor = stackHintExtractor;
    }

    @NonNull
    public static DiagnosticMetadataBuilder forCause(@NonNull Throwable cause,
                                                     @NonNull StackHintExtractor stackHintExtractor) {
        DiagnosticMetadataBuilder builder = new DiagnosticMetadataBuilder(cause, stackHintExtractor);
        builder.metadata.put("exceptionClass", cause.getClass().getName());
        return builder;
    }

    /**
     * Создаёт builder БЕЗ автоматической вставки {@code exceptionClass}.
     *
     * <p>Предназначен для случаев, когда стратегия требует точного контроля
     * над порядком вставки, размещает {@code exceptionClass} не первым,
     * или осознанно не включает его в metadata.
     */
    @NonNull
    public static DiagnosticMetadataBuilder forCauseRaw(@NonNull Throwable cause,
                                                        @NonNull StackHintExtractor stackHintExtractor) {
        return new DiagnosticMetadataBuilder(cause, stackHintExtractor);
    }

    @NonNull
    public DiagnosticMetadataBuilder put(@NonNull String key, @NonNull String value) {
        metadata.put(key, value);
        return this;
    }

    @NonNull
    public DiagnosticMetadataBuilder putIfPresent(@NonNull String key, @Nullable String value) {
        if (StringUtils.hasText(value)) {
            metadata.put(key, value);
        }

        return this;
    }

    public DiagnosticMetadataBuilder putTruncated(@NonNull String key, String value, int maxLength) {
        if (StringUtils.hasText(value)) {
            metadata.put(key, StringUtils.truncateNonNull(value, maxLength));
        } else {
            metadata.put(key, null);
        }

        return this;
    }

    @NonNull
    public DiagnosticMetadataBuilder putTruncatedIfPresent(@NonNull String key, @Nullable String value, int maxLength) {
        if (StringUtils.hasText(value)) {
            metadata.put(key, StringUtils.truncateNonNull(value, maxLength));
        }

        return this;
    }

    @NonNull
    public DiagnosticMetadataBuilder withRootCause() {
        return withRootCause(
                CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH,
                DEFAULT_MAX_MESSAGE_LENGTH
        );
    }

    @NonNull
    public DiagnosticMetadataBuilder withNestedCause(int maxCauseMessageLength) {
        CauseMetadataEnricher.enrichWithNestedCause(cause, metadata, maxCauseMessageLength);
        return this;
    }

    @NonNull
    public DiagnosticMetadataBuilder withRootCause(int depth, int maxLen) {
        if (rootCauseApplied) {
            throw new IllegalStateException(
                    "withRootCause() already called on this builder — " +
                            "repeated call would silently overwrite keys and corrupt insertion order");
        }

        rootCauseApplied = true;
        CauseMetadataEnricher.enrichWithRootCause(cause, metadata, depth, maxLen);

        return this;
    }

    /**
     * Обогащает metadata <b>только</b> {@code rootCauseClass} (FQCN), без {@code rootCauseMessage}.
     *
     * <p>Используется в стратегиях с повышенными требованиями к CWE-209/CWE-200,
     * где текст root-cause может содержать чувствительные данные (например,
     * TLS — CN/SAN/serial; Vault — токены; HTTP — внутренние URL/тела ответов).
     *
     * <p>Контракт идемпотентности тот же, что у {@link #withRootCause()}: повторный
     * вызов {@code withRootCauseClassOnly} либо смешанный с {@code withRootCause}
     * бросает {@link IllegalStateException}.
     */
    @NonNull
    public DiagnosticMetadataBuilder withRootCauseClassOnly() {
        return withRootCauseClassOnly(CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH);
    }

    @NonNull
    public DiagnosticMetadataBuilder withRootCauseClassOnly(int depth) {
        if (rootCauseApplied) {
            throw new IllegalStateException(
                    "withRootCause*() already called on this builder — " +
                            "repeated call would silently overwrite keys and corrupt insertion order");
        }

        rootCauseApplied = true;
        CauseMetadataEnricher.enrichWithRootCauseClassOnly(cause, metadata, depth);

        return this;
    }

    /**
     * Обогащает metadata root-cause информацией из <i>произвольного</i> {@code subject},
     * не обязательно совпадающего с throwable основного builder.
     *
     * <p>Используется, когда диагностика строится вокруг обёртки (например, retry exhaustion),
     * а глубинная причина извлекается из последней попытки — отдельного {@link Throwable}.
     * Не изменяет флаг {@code rootCauseApplied} (в отличие от {@link #withRootCause(int, int)},
     * который привязан к {@link #cause} builder'а).
     */
    @NonNull
    public DiagnosticMetadataBuilder enrichRootCauseFrom(@NonNull Throwable subject,
                                                         int depth,
                                                         int maxLen) {
        CauseMetadataEnricher.enrichWithRootCause(subject, metadata, depth, maxLen);
        return this;
    }

    /**
     * Обогащает metadata <b>только</b> {@code rootCauseClass} (FQCN), извлечённым
     * из произвольного {@code subject}, без {@code rootCauseMessage}.
     *
     * <p>Используется, когда диагностика строится вокруг обёртки (например, retry exhaustion),
     * глубинная причина извлекается из {@code lastAttempt}, и при этом её сообщение
     * нельзя раскрывать (CWE-209: SQL bind, JWT-токены, internal hostnames).
     *
     * <p>Не изменяет флаг {@code rootCauseApplied}.
     */
    @NonNull
    public DiagnosticMetadataBuilder enrichRootCauseClassOnlyFrom(@NonNull Throwable subject,
                                                                  int depth) {
        CauseMetadataEnricher.enrichWithRootCauseClassOnly(subject, metadata, depth);
        return this;
    }

    @NonNull
    public DiagnosticMetadataBuilder withStackHint() {
        String hint = stackHintExtractor.extractStackHint(cause);
        if (StringUtils.hasText(hint)) {
            metadata.put("stackHint", hint);
        }

        return this;
    }

    @NonNull
    public Map<String, String> build() {
        if (built) {
            throw new IllegalStateException(
                    "build() already called on this builder — " +
                            "reuse is not allowed to prevent divergence between two snapshots");
        }

        built = true;
        return Collections.unmodifiableMap(metadata);
    }
}
