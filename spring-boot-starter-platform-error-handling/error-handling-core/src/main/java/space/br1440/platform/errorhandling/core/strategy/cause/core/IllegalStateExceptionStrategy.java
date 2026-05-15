package space.br1440.platform.errorhandling.core.strategy.cause.core;

import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.core.util.CauseChainNavigator;
import space.br1440.platform.errorhandling.core.util.CauseMetadataEnricher;
import space.br1440.platform.errorhandling.core.util.DiagnosticMetadataBuilder;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.util.StringUtils;
import space.br1440.platform.errorhandling.core.util.sanitize.LogSanitizer;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.extension.base.AbstractRootCauseExceptionStrategy;

import java.util.List;
import java.util.Map;

/**
 * Root-cause стратегия для <b>точного</b> {@link IllegalStateException} — использующаяся
 * доменом в качестве сигнала о нарушении бизнес-инварианта (конфликт состояния).
 *
 * <h2>Границы применимости (узко)</h2>
 *
 * <p>Стратегия срабатывает <b>только</b> для экземпляров класса {@link IllegalStateException}
 * самого по себе (проверка {@code cause.getClass() == IllegalStateException.class}).
 * Наследники — {@code CancellationException}, {@code ConcurrentModificationException},
 * Hibernate {@code LazyInitializationException} и т.п. — <b>не</b> обрабатываются здесь,
 * потому что имеют совершенно иную семантику (server-side баг, отмена, конкурентность)
 * и другой целевой HTTP-статус. Для них должны существовать специализированные стратегии
 * либо generic fallback на {@code 500 Internal Server Error}.
 *
 * <p>Типичный сценарий:
 *
 * <pre>
 *   OrderService.submit()  → throw new IllegalStateException("Order is already submitted");
 *   PaymentService.charge() → throw new IllegalStateException("Cart is empty");
 * </pre>
 *
 * <h2>HTTP-маппинг</h2>
 *
 * <p>{@code 409 Conflict} (RFC 9110 §15.5.10): запрос не может быть выполнен из-за
 * конфликта с <i>текущим состоянием</i> целевого ресурса. Это точная семантика
 * «нарушение инварианта», в отличие от {@code 422 Unprocessable Entity}, который
 * зарезервирован для синтаксически корректных, но семантически невалидных данных
 * на уровне полей запроса (RFC 9110 §15.5.21).
 *
 * <h2>Политика логирования</h2>
 *
 * <p>{@link LogPolicy#WARN}: нарушение бизнес-инварианта — это ожидаемая ветка
 * в доменной логике (например, повторная отправка заказа), а не инфраструктурный
 * сбой. При росте счётчика {@code errors_total{reason="INVALID_STATE"}} команда
 * продукта должна анализировать UX-поток, а не работу платформы.
 *
 * <h2>Диагностические метаданные</h2>
 *
 * <p>Метаданные попадают в <b>структурированные логи</b> (не в клиентский ответ):
 * <ul>
 *   <li>{@code causeMessage} — сообщение исключения, <b>санитизированное</b>
 *       {@link LogSanitizer} (удаление CR/LF/TAB/NUL и bidirectional override) и
 *       обрезанное до {@value #MAX_CAUSE_MESSAGE_LENGTH} символов.</li>
 *   <li>{@code rootCauseClass} — FQCN самой глубокой причины (если {@code cause}
 *       является обёрткой, например, через retry или @Transactional proxy).
 *       Сообщение root-cause <b>не включается</b> — оно может содержать внутренние
 *       идентификаторы из инфраструктурных библиотек (см. ниже).</li>
 *   <li>{@code stackHint} — сжатый стек прикладных фреймов.</li>
 * </ul>
 *
 * <p>{@code exceptionClass} намеренно <b>не</b> добавляется: после сужения
 * {@link #supportsCause(Throwable)} до {@code cause.getClass() == IllegalStateException.class}
 * значение было бы константой ({@code java.lang.IllegalStateException}) и не несло бы
 * ни диагностической, ни агрегационной ценности — та же информация уже закодирована
 * в {@code reason=INVALID_STATE} + {@code domain=state}.
 *
 * <h2>Безопасность</h2>
 *
 * <ul>
 *   <li><b>CWE-117 Log Injection / "Trojan Source"</b>: {@code causeMessage}
 *       проходит через {@link LogSanitizer}, который удаляет управляющие и
 *       Unicode bidirectional override символы.</li>
 *   <li><b>CWE-209 Information Exposure</b>: сообщение исключения <b>не</b>
 *       отдаётся клиенту. В клиентский ответ идёт только нейтральный
 *       {@value #MESSAGE}.</li>
 *   <li><b>Root-cause message не пишется</b>: сообщения вложенных исключений
 *       часто содержат имена пулов, идентификаторы транзакций, фрагменты SQL,
 *       URL с query-параметрами. Класс root-cause достаточен для корреляции,
 *       а message root-cause — избыточный риск утечки.</li>
 * </ul>
 *
 * <h2>RetryInfoDetail</h2>
 *
 * <p>Не добавляется: повторный запрос в том же состоянии даст тот же результат —
 * клиент должен изменить поведение (дождаться изменения состояния, повторная
 * авторизация, пользовательское действие).
 */
public class IllegalStateExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    /**
     * Лимит длины {@code causeMessage} после санитизации. 200 согласовано с
     * {@link DiagnosticMetadataBuilder#DEFAULT_MAX_MESSAGE_LENGTH} и достаточно
     * для бизнес-сообщения вида {@code "Order <id> is already submitted"}.
     */
    static final int MAX_CAUSE_MESSAGE_LENGTH = 200;

    /**
     * Лимит длины {@code rootCauseClass}: защищает metadata от длинных FQCN
     * динамических прокси (CGLIB/ByteBuddy: {@code Foo$$EnhancerByCGLIB$$abc...}),
     * которые могут разрастаться до сотен символов и засорять структурированные логи.
     */
    static final int MAX_ROOT_CAUSE_CLASS_LENGTH = 256;

    /**
     * Нейтральное клиентское сообщение (CWE-209): отдаётся в Problem Details независимо
     * от значения {@link Throwable#getMessage()} root-cause. Сообщение исключения
     * может содержать внутренние идентификаторы (имена пулов, transaction-id, фрагменты
     * SQL/URL), поэтому проксировать его в ответ запрещено.
     */
    static final String MESSAGE =
            "The request could not be processed because the operation conflicts with the current state of the resource.";

    static final String REASON = "INVALID_STATE";
    static final String DOMAIN = "state";

    public IllegalStateExceptionStrategy(StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.ILLEGAL_STATE, stackHintExtractor);
    }

    @Override
    @NonNull
    public @NotNull LogPolicy logPolicy(@NonNull Exception exception,
                                        @NonNull ErrorEntry errorEntry) {
        return LogPolicy.WARN;
    }

    /**
     * Принимает <b>строго</b> экземпляры {@link IllegalStateException} —
     * наследники (например, {@link java.util.concurrent.CancellationException},
     * {@link java.util.ConcurrentModificationException}) намеренно исключены.
     */
    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        return cause.getClass() == IllegalStateException.class;
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(REASON)
                .domain(DOMAIN)
                .diagnosticMetadata(buildDiagnosticMetadata(cause))
                .build();

        return RootCauseResolution.notRetryable(HttpStatus.CONFLICT, MESSAGE, List.of(info));
    }

    @NonNull
    private Map<String, String> buildDiagnosticMetadata(@NonNull Throwable cause) {
        DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCauseRaw(cause, stackHintExtractor);

        String rawMessage = cause.getMessage();
        if (StringUtils.hasText(rawMessage)) {
            String sanitized = LogSanitizer.sanitize(rawMessage, MAX_CAUSE_MESSAGE_LENGTH);
            builder.put("causeMessage", sanitized);
        }

        Throwable root = CauseChainNavigator.findDeepestCause(cause,
                CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH);
        if (root != cause) {
            builder.put("rootCauseClass",
                    LogSanitizer.sanitize(root.getClass().getName(), MAX_ROOT_CAUSE_CLASS_LENGTH));
        }

        return builder.withStackHint().build();
    }
}
