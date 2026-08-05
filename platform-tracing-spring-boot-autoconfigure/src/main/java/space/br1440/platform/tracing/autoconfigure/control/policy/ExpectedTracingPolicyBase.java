package space.br1440.platform.tracing.autoconfigure.control.policy;

/**
 * Атомарная пара «ожидаемый владелец политики», которую control-plane обязан передавать
 * write-пути целиком и только той версией, что была реально прочитана вместе со снапшотом.
 * <p>
 * До введения этого типа {@code applySnapshot} принимал только {@code acceptedSequence},
 * а {@code runtimeInstanceId} подставлялся из отдельного mutable side channel в
 * JMX-клиенте, обновляемого произвольным read-only статусным вызовом. Это позволяло
 * мутации уйти с identity, которую она никогда не наблюдала вместе с применяемой политикой
 * (например, после restart Agent). Данный record делает такую рассинхронизацию
 * невозможной на уровне типов: у вызывающего физически нет способа передать sequence без
 * runtimeInstanceId или наоборот.
 * <p>
 * Расположен в корневом пакете {@code control.policy}, а не в {@code .read} или
 * {@code .delivery}, поскольку является общим контрактом для обеих сторон
 * (read возвращает наблюдённую базу, delivery принимает её обратно) и не должен создавать
 * зависимость одного дочернего пакета от другого.
 */
public record ExpectedTracingPolicyBase(String runtimeInstanceId, long acceptedSequence) {

    public ExpectedTracingPolicyBase {
        if (runtimeInstanceId == null || runtimeInstanceId.isBlank()) {
            throw new IllegalArgumentException("runtimeInstanceId must not be blank");
        }
        if (acceptedSequence < 0L) {
            throw new IllegalArgumentException(
                    "acceptedSequence must be >= 0, got " + acceptedSequence);
        }
    }
}
