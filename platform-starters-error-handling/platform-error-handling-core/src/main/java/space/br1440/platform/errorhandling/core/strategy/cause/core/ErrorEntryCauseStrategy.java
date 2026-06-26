package space.br1440.platform.errorhandling.core.strategy.cause.core;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.util.*;
import space.br1440.platform.errorhandling.core.util.sanitize.LogSanitizer;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.base.AbstractRootCauseExceptionStrategy;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.exception.AbstractErrorResponseException;
import space.br1440.platform.errorhandling.exception.business.BusinessException;
import space.br1440.platform.errorhandling.http.HttpStatusResolver;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.*;

/**
 * Стратегия разрешения root cause'а, являющейся носителем {@link ErrorEntry}
 * ({@link AbstractErrorResponseException} или любой его наследник как платформенный, так и кастомный доменный),
 * <b>за исключением</b> {@link BusinessException}.
 *
 * <h2>Архитектурная роль</h2>
 * <p>
 * {@link CauseChainResolver} обходит cause chain сверху вниз и передаёт каждое звено зарегистрированным {@link RootCauseExceptionHandlingStrategy}.
 * Остальные стратегии распознают <b>инфраструктурные</b> причины: таймауты, circuit breaker, retry exhaustion,
 * Redis, SSL и т.д. Ни одна из них не обрабатывает ситуацию, когда root cause'ом является исключение с уже сформированным {@link ErrorEntry},
 * то есть исключение, принадлежащее иерархии {@link AbstractErrorResponseException}.
 *
 * <p>Бизнес-исключения ({@link BusinessException}) обрабатываются отдельной top-level
 * стратегией ({@code BusinessExceptionStrategy}). Если {@link BusinessException}
 * прилетает как {@code cause} другого исключения — это нештатный сценарий
 * (бизнес-операцию следует пробрасывать как top-throwable, а не оборачивать); такая
 * ситуация намеренно <b>не</b> подхватывается данной стратегией и проваливается
 * в {@code DefaultUnknownExceptionHandler}.
 *
 * <h2>Propagation-контракт</h2>
 * <p>
 * При propagation вложенного {@link ErrorEntry} стратегия:
 * <ol>
 *   <li>Берёт {@code message}, {@code details}, {@code errorCode} из nested {@link ErrorEntry}.</li>
 *   <li>Резолвит HTTP status через {@link HttpStatusResolver} —
 *       итоговый status может отличаться от {@code nestedEntry.getHttpStatus()}
 *       (напр. {@code NotFoundException} создаётся с default 500, но резолвится в 404).</li>
 *   <li>Формирует {@code clientMetadata} с propagation-маркером и propagated error code.</li>
 *   <li>Формирует {@code diagnosticMetadata} с минимально-достаточной диагностикой для SRE:
 *       маркер propagation, FQCN класса, санитизированный truncated message, FQCN root-cause,
 *       stack hint.</li>
 *   <li>Сохраняет существующие {@code clientMetadata} и {@code diagnosticMetadata}
 *       из nested {@link ErrorInfoDetail} (merge) — за исключением <b>protected</b>
 *       propagation-маркеров, которые не должны перезаписываться (см. ниже).</li>
 * </ol>
 *
 * <h3>Безопасность</h3>
 * <ul>
 *   <li><b>CWE-117 Log Injection</b>, <b>CVE-2021-42574 "Trojan Source"</b>: все строковые
 *       значения, приходящие из nested {@link ErrorEntry} ({@code message},
 *       сообщение root-cause), пропускаются через {@link LogSanitizer} — удаляются
 *       CR/LF/TAB/NUL и Unicode bidirectional override.</li>
 *   <li><b>CWE-209 Information Exposure</b>: сообщение root-cause не попадает в metadata
 *       (может содержать имена пулов, ID транзакций, фрагменты SQL/URL).
 *       Fallback-сообщение в {@link RootCauseResolution#message()} не включает FQCN класса:
 *       при отсутствии человекочитаемого сообщения используется
 *       {@value #DEFAULT_PROPAGATION_MESSAGE}.</li>
 *   <li>{@code diagnosticMetadata} не попадает в DTO — design by contract
 *       в {@link space.br1440.platform.errorhandling.model.mapping.ErrorEntryToDtoMapper}.</li>
 * </ul>
 *
 * <h2>Пример cause chain</h2>
 * <pre>
 *   ChannelGetException (AbstractErrorResponseException + BusinessException)
 *       |-- cause: ChannelWithKeyNotExistException (AbstractErrorResponseException + NotFoundException)
 *           errorEntry: { httpStatus=500, errorCode="UMS-8100", message="...does not exist" }
 *
 *   1. CauseChainResolver предлагает ChannelWithKeyNotExistException стратегиям
 *   2. ErrorEntryCauseStrategy: supportsCause = true
 *      (instanceof AbstractErrorResponseException and !(BusinessException))
 *   3. extractCauseDetails:
 *        - читает ErrorEntry из ChannelWithKeyNotExistException
 *        - HttpStatusResolver: NotFoundException → 404
 *        - diagnosticMetadata: propagated=true, exceptionClass=..., causeMessage=..., stackHint=...
 *        - формирует CauseDetails(404, message из ErrorEntry, details)
 *   4. BusinessExceptionStrategy подставляет resolved cause details в итоговый ErrorEntry
 *   5. Итог: HTTP 404, clientMetadata: {propagated=true, propagatedErrorCode=UMS-8100}
 * </pre>
 */
public class ErrorEntryCauseStrategy extends AbstractRootCauseExceptionStrategy {

    /**
     * Значение 300 мотивировано тем, что propagation-стратегия часто оборачивает
     * validation-сообщения (RFC 9457) и бизнес-нарушения из чужих сервисов, которые
     * могут быть длиннее лимита в 200 символов других стратегий. Ключи, ограниченные
     * этим лимитом, никогда не попадают в клиентский ответ.
     */
    static final int MAX_CAUSE_MESSAGE_LENGTH = 300;

    /**
     * Fallback-сообщение для клиентского ответа, если {@code nestedEntry.message} и
     * {@code nestedEx.message} пустые. Намеренно нейтральное — не утекает FQCN
     * класса исключения (CWE-209).
     */
    static final String DEFAULT_PROPAGATION_MESSAGE = "Propagated error";

    private final HttpStatusResolver httpStatusResolver;
    private final String applicationName;

    public ErrorEntryCauseStrategy(@NonNull HttpStatusResolver httpStatusResolver,
                                   @NonNull StackHintExtractor stackHintExtractor,
                                   @NonNull String applicationName) {
        super(RootCauseExceptionStrategyOrder.ERROR_ENTRY, stackHintExtractor);
        this.httpStatusResolver = httpStatusResolver;
        this.applicationName = applicationName;
    }

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        // BusinessException обрабатывается отдельной top-level стратегией и в cause-цепочке не подхватывается:
        // вложенный BusinessException — нештатный сценарий (его следует бросать как top-throwable).
        return cause instanceof AbstractErrorResponseException && !(cause instanceof BusinessException);
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        AbstractErrorResponseException nestedEx = (AbstractErrorResponseException) cause;
        ErrorEntry nestedEntry = nestedEx.getErrorEntry();
        String resolvedMessage = resolveClientMessage(nestedEntry, nestedEx);

        List<ErrorDetail> nestedDetails = (nestedEntry.getDetails() != null)
                ? new ArrayList<>(nestedEntry.getDetails())
                : new ArrayList<>();
        enrichOrAddErrorInfo(nestedDetails, nestedEx, nestedEntry);

        HttpStatus resolvedStatus = httpStatusResolver.resolve(nestedEx);

        Retryability retryability = RetryabilityResolver.containsRetryInfo(nestedEntry.getDetails())
                ? Retryability.RETRYABLE
                : Retryability.UNSPECIFIED;

        return RootCauseResolution.of(
                resolvedStatus,
                resolvedMessage,
                Collections.unmodifiableList(nestedDetails), retryability
        );
    }

    @NonNull
    private String resolveClientMessage(@NonNull ErrorEntry nestedEntry,
                                        @NonNull AbstractErrorResponseException nestedEx) {
        String candidate = StringUtils.firstNonBlank(
                nestedEntry.getMessage(),
                nestedEx.getMessage()
        );

        if (!StringUtils.hasText(candidate)) {
            return DEFAULT_PROPAGATION_MESSAGE;
        }

        return LogSanitizer.sanitize(candidate, MAX_CAUSE_MESSAGE_LENGTH);
    }

    @NonNull
    private static Map<String, String> buildClientMetadata(@NonNull ErrorEntry nestedEntry) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(PropagationMetadataKeys.PROPAGATED, PropagationMetadataKeys.TRUE);

        String errorCode = nestedEntry.getErrorCode();
        if (StringUtils.hasText(errorCode)) {
            meta.put(PropagationMetadataKeys.PROPAGATED_ERROR_CODE, errorCode);
        }

        return meta;
    }

    @NonNull
    private Map<String, String> buildDiagnosticMetadata(@NonNull AbstractErrorResponseException nestedEx,
                                                        @NonNull ErrorEntry nestedEntry) {
        DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCauseRaw(nestedEx, stackHintExtractor);
        builder.put(PropagationMetadataKeys.PROPAGATED, PropagationMetadataKeys.TRUE);
        builder.put(PropagationMetadataKeys.EXCEPTION_CLASS, nestedEx.getClass().getName());

        String causeMessage = nestedEntry.getMessage();
        if (StringUtils.hasText(causeMessage)) {
            builder.put(
                    PropagationMetadataKeys.CAUSE_MESSAGE,
                    LogSanitizer.sanitize(causeMessage, MAX_CAUSE_MESSAGE_LENGTH)
            );
        }

        enrichRootCauseClass(builder, nestedEx);

        return builder
                .withStackHint()
                .build();
    }

    /**
     * Добавляет только FQCN самой глубокой причины ({@code rootCauseClass}).
     * Сообщение root-cause <b>намеренно не пишется</b> (CWE-209):
     * сообщения инфраструктурных исключений часто содержат имена пулов,
     * идентификаторы транзакций, фрагменты SQL и URL.
     */
    private void enrichRootCauseClass(@NonNull DiagnosticMetadataBuilder builder,
                                      @NonNull Throwable cause) {
        Throwable root = findDeepestCause(cause);
        if (root != cause) {
            builder.put("rootCauseClass", root.getClass().getName());
        }
    }

    @NonNull
    private static Throwable findDeepestCause(@NonNull Throwable start) {
        Throwable current = start;
        Throwable deepest = start;

        for (int i = 0; i < CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH; i++) {
            Throwable next = current.getCause();
            if (next == null || next == current) {
                break;
            }

            deepest = next;
            current = next;
        }

        return deepest;
    }

    private void enrichOrAddErrorInfo(@NonNull List<ErrorDetail> details,
                                      @NonNull AbstractErrorResponseException nestedEx,
                                      @NonNull ErrorEntry nestedEntry) {
        Map<String, String> propagationDiagnostic = buildDiagnosticMetadata(nestedEx, nestedEntry);
        Map<String, String> propagationClient = buildClientMetadata(nestedEntry);

        ListIterator<ErrorDetail> iterator = details.listIterator();
        while (iterator.hasNext()) {
            if (iterator.next() instanceof ErrorInfoDetail info) {
                Map<String, String> mergedDiag = mergeWithProtectedOverlay(
                        info.getDiagnosticMetadata(),
                        propagationDiagnostic,
                        PROTECTED_DIAGNOSTIC_KEYS
                );

                Map<String, String> mergedClient = mergeWithProtectedOverlay(
                        info.getClientMetadata(),
                        propagationClient,
                        PROTECTED_CLIENT_KEYS
                );

                String resolvedDomain = resolveDetailDomain(nestedEntry, info);
                ErrorInfoDetail enriched = info.toBuilder()
                        .domain(resolvedDomain)
                        .diagnosticMetadata(mergedDiag)
                        .clientMetadata(mergedClient)
                        .build();
                iterator.set(enriched);
                return;
            }
        }

        String reason = StringUtils.firstNonBlank(
                nestedEntry.getErrorCode(),
                nestedEx.getClass().getName()
        );

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(reason)
                .domain(resolveDetailDomain(nestedEntry, null))
                .clientMetadata(propagationClient)
                .diagnosticMetadata(propagationDiagnostic)
                .build();
        details.addFirst(info);
    }

    /**
     * Определяет {@code domain} итогового {@link ErrorInfoDetail}.
     *
     * <p>Приоритет: {@code nestedEntry.getDomain()} (истинное происхождение) →
     * существующий {@code info.getDomain()} → {@code applicationName}.
     * Это исправляет регрессию, при которой {@code nestedDomain} терялся и подменялся
     * на имя текущего сервиса при cross-service propagation.
     */
    @Nullable
    private String resolveDetailDomain(@NonNull ErrorEntry nestedEntry,
                                       @Nullable ErrorInfoDetail info) {
        if (StringUtils.hasText(nestedEntry.getDomain())) {
            return nestedEntry.getDomain();
        }

        if (info != null && StringUtils.hasText(info.getDomain())) {
            return info.getDomain();
        }

        return StringUtils.hasText(applicationName) ? applicationName : null;
    }

    /**
     * Merge-политика: сначала кладутся <i>existing</i> metadata из nested detail,
     * затем поверх них перезаписываются <b>только protected</b> propagation-ключи.
     * Это гарантирует, что факт propagation не теряется при случайной коллизии ключей
     * (например, если nested detail содержит собственный ключ {@code "propagated"}
     * или {@code "sourceService"} с произвольным значением).
     *
     * <p>Не-protected propagation-ключи добавляются только если их ещё нет — для
     * совместимости со стратегиями, которые могли уже уточнить metadata
     * (например, более точный {@code exceptionClass}).
     */
    @NonNull
    private static Map<String, String> mergeWithProtectedOverlay(@NonNull Map<String, String> existing,
                                                                 @NonNull Map<String, String> propagation,
                                                                 @NonNull Set<String> protectedKeys) {
        Map<String, String> merged = new LinkedHashMap<>(existing);

        for (Map.Entry<String, String> entry : propagation.entrySet()) {
            String key = entry.getKey();
            if (protectedKeys.contains(key) || !merged.containsKey(key)) {
                merged.put(key, entry.getValue());
            }
        }

        return merged;
    }

    private static final Set<String> PROTECTED_DIAGNOSTIC_KEYS = Set.of(
            PropagationMetadataKeys.PROPAGATED
    );

    private static final Set<String> PROTECTED_CLIENT_KEYS = Set.of(
            PropagationMetadataKeys.PROPAGATED,
            PropagationMetadataKeys.PROPAGATED_ERROR_CODE
    );
}
