package space.br1440.platform.errorhandling.core.strategy.top;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.ResolvedRootCause;
import space.br1440.platform.errorhandling.extension.base.AbstractTopExceptionStrategy;
import space.br1440.platform.errorhandling.extension.order.TopExceptionStrategyOrder;
import space.br1440.platform.errorhandling.exception.AbstractErrorResponseException;
import space.br1440.platform.errorhandling.exception.business.BusinessException;
import space.br1440.platform.errorhandling.http.HttpStatusResolver;
import space.br1440.platform.errorhandling.model.ErrorEntry;

import java.util.List;

/**
 * Top-level стратегия для бизнес-исключений сервиса
 * ({@link BusinessException} и его наследники).
 *
 * <h2>Контракт supports()</h2>
 * <p>Стратегия принимает <b>исключительно</b> наследников {@link BusinessException}.
 * Прочие наследники {@link AbstractErrorResponseException} (валидационные, integrity-,
 * datasource-, security- исключения), брошенные напрямую с top-уровня, в эту стратегию
 * не попадают и обрабатываются {@code ErrorEntryCauseStrategy} только в составе
 * cause-цепочки. Если такой не-business {@link AbstractErrorResponseException}
 * прилетает на top-уровне без обёртки, ни одна стратегия его не подхватывает —
 * управление получает {@code DefaultUnknownExceptionHandler} (HTTP 500 +
 * {@code UNKNOWN_INTERNAL_ERROR}). Это намеренный архитектурный контракт: на top-уровне
 * допустимо бросать только {@link BusinessException}; остальные платформенные
 * исключения должны фигурировать в {@code cause}-цепочке. Нарушение ловится
 * ArchUnit-правилами.
 *
 * <h2>Алгоритм handle()</h2>
 * <p>HTTP-статус резолвится через {@link HttpStatusResolver} и подставляется в
 * {@link ErrorEntry}, прикреплённый к исключению. При наличии {@code cause}
 * стратегия обходит <b>всю</b> цепочку через
 * {@link CauseChainResolver#resolveAll(Throwable)} и собирает:
 * <ul>
 *   <li>{@code errorCode} — всегда из wrapper-исключения (операционный код операции).</li>
 *   <li>{@code httpStatus} — из первого resolved cause (ближайшего к операции).</li>
 *   <li>{@code reason} — <b>человекочитаемое сообщение</b> первого resolved cause:
 *       приоритетно {@code resolution().message()} (санитизировано RootCause-стратегией),
 *       либо fallback на {@code rootCause.getMessage()}. Машиночитаемый код root-cause
 *       (SCREAMING_SNAKE) кладётся в {@code details[].ERROR_INFO.reason}, а не в
 *       {@code ErrorEntry.reason}.</li>
 *   <li>{@code details} — merge wrapper details + {@code ERROR_INFO} из <b>всех</b>
 *       resolved causes. Каждый {@code ERROR_INFO} несёт свой {@code clientMetadata}
 *       (для DTO/UI) и {@code diagnosticMetadata} (только в {@code ErrorEntry}, для логов).</li>
 * </ul>
 * <p>{@link space.br1440.platform.errorhandling.model.mapping.ErrorEntryToDtoMapper#toDto}
 * экспонирует <b>всю</b> цепочку {@code ERROR_INFO} в {@code ErrorEntryDTO.details[]}
 * (для cause-chain видимости клиенту).
 */
public class BusinessExceptionStrategy extends AbstractTopExceptionStrategy {

    private final HttpStatusResolver httpStatusResolver;

    public BusinessExceptionStrategy(@NonNull CauseChainResolver causeChainResolver,
                                     @NonNull HttpStatusResolver httpStatusResolver) {
        super(TopExceptionStrategyOrder.BUSINESS, causeChainResolver);
        this.httpStatusResolver = httpStatusResolver;
    }

    @Override
    public boolean supports(@NonNull Exception exception) {
        return exception instanceof BusinessException;
    }

    @Override
    @NonNull
    protected ErrorEntry doHandle(@NonNull Exception exception, @NonNull ErrorHandlingContext context) {
        BusinessException businessException = (BusinessException) exception;
        ErrorEntry baseEntry = businessException.getErrorEntry();

        HttpStatus resolvedStatus = httpStatusResolver.resolve(businessException);
        return baseEntry.toBuilder()
                .httpStatus(resolvedStatus)
                .build();
    }

    @Override
    @NonNull
    protected ErrorEntry enrichFromCauseChain(@NonNull Exception exception, @NonNull ErrorEntry base) {
        Throwable cause = exception.getCause();
        if (cause == null) {
            return base;
        }

        List<ResolvedRootCause> chain = causeChainResolver.resolveAll(cause);
        return applyResolvedCauseChain(base, chain);
    }
}
