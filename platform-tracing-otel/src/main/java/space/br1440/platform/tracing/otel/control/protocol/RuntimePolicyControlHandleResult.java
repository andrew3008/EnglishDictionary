package space.br1440.platform.tracing.otel.control.protocol;

import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolOperation;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolViolation;

import java.util.List;
import java.util.Optional;

/**
 * Неизменяемый результат одного вызова {@link RuntimePolicyControlHandler#handle}.
 *
 * <p>Возможны четыре терминальных состояния:
 * <ul>
 *   <li>{@link HandleStatus#SUCCESS} — payload структурно валиден,
 *       прошёл доменную валидацию и (для мутирующих операций) успешно
 *       применён к runtime-состоянию.</li>
 *   <li>{@link HandleStatus#DECODE_REJECTED} — wire-level decode не удался;
 *       операцию определить нельзя. {@link #violations()} содержит
 *       сообщения о структурных нарушениях со слоя decode.</li>
 *   <li>{@link HandleStatus#DOMAIN_REJECTED} — payload прошёл структурный
 *       decode, но не прошёл доменную / policy-валидацию; операция известна.
 *       {@link #violations()} содержит сообщения о доменных нарушениях.</li>
 *   <li>{@link HandleStatus#MUTATION_REJECTED} — decode и доменная валидация
 *       прошли успешно, но runtime-мутация отключена.</li>
 * </ul>
 *
 * <p>{@link #operation()} возвращает {@link Optional#empty()} только для
 * {@link HandleStatus#DECODE_REJECTED}, потому что значение операции — часть
 * payload, который не удалось декодировать.
 */
public record RuntimePolicyControlHandleResult(
        HandleStatus status,
        Optional<TracingControlProtocolOperation> operation,
        List<String> violations
) {

    /** Терминальный статус попытки обработки control-protocol запроса. */
    public enum HandleStatus {
        /** Payload декодирован, доменно валидирован и применён (или read-only). */
        SUCCESS,
        /** Структурный / wire decode не удался; операция неизвестна. */
        DECODE_REJECTED,
        /** Decode успешен, но доменная / policy-валидация не прошла. */
        DOMAIN_REJECTED,
        /** Decode и доменная валидация успешны, но мутация отключена. */
        MUTATION_REJECTED
    }

    public RuntimePolicyControlHandleResult {
        violations = (violations == null) ? List.of() : List.copyOf(violations);
    }

    public static RuntimePolicyControlHandleResult success(TracingControlProtocolOperation operation) {
        return new RuntimePolicyControlHandleResult(
                HandleStatus.SUCCESS,
                Optional.of(operation),
                List.of());
    }

    public static RuntimePolicyControlHandleResult decodeRejected(List<TracingControlProtocolViolation> violations) {
        List<String> messages = violations.stream()
                .map(v -> v.code().name() + ": " + v.reason())
                .toList();

        return new RuntimePolicyControlHandleResult(
                HandleStatus.DECODE_REJECTED,
                Optional.empty(),
                messages);
    }

    public static RuntimePolicyControlHandleResult domainRejected(TracingControlProtocolOperation operation,
                                                                  List<String> domainViolations) {
        return new RuntimePolicyControlHandleResult(
                HandleStatus.DOMAIN_REJECTED,
                Optional.of(operation),
                domainViolations);
    }

    public static RuntimePolicyControlHandleResult mutationRejected(TracingControlProtocolOperation operation,
                                                                    String reason) {
        return new RuntimePolicyControlHandleResult(
                HandleStatus.MUTATION_REJECTED,
                Optional.of(operation),
                List.of(reason));
    }

    public boolean isSuccess() {
        return (status == HandleStatus.SUCCESS);
    }

    public boolean isRejected() {
        return (status != HandleStatus.SUCCESS);
    }
}
