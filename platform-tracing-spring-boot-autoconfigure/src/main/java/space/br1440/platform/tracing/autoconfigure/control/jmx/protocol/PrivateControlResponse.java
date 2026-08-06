package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.PrivateControlWireTypes.WireField;

/**
 * Декодирует и валидирует wire-ответ Agent {@code applySnapshot}.
 * <p>
 * Это межпроцессная, кросс-classloader граница доверия — как и {@link AcceptedCompleteSnapshotDecoder}
 * для {@code readAcceptedComplete}. Декодирование обязано быть fail-closed: неверный
 * {@code CompositeType}, отсутствующее, {@code null} или неверно типизированное поле
 * {@code outcome} должны детерминированно провалиться здесь, а не быть проглочены unchecked
 * cast'ом и превратиться в неверную классификацию отказа на вызывающей стороне
 * ({@code PrivateJmxTracingPolicySnapshotClient.parseOutcome} обязан завернуть исключение отсюда
 * в {@code TracingPolicyContractViolationException}, а не дать ему уйти в общий catch).
 */
public record PrivateControlResponse(String outcome) {

    private static final String OPERATION = "applySnapshot";

    public static PrivateControlResponse decode(CompositeData response) {
        if (response == null) {
            throw invalid("expected CompositeData, got null");
        }
        if (!PrivateControlWireTypes.RESPONSE.equals(response.getCompositeType())) {
            throw invalid("unexpected CompositeType " + response.getCompositeType().getTypeName()
                    + "; expected " + PrivateControlWireTypes.RESPONSE.getTypeName());
        }

        Object value = response.get(WireField.OUTCOME.wireName());
        if (value == null) {
            throw invalid("field '" + WireField.OUTCOME.wireName() + "' is null");
        }
        if (!(value instanceof String outcome)) {
            throw invalid("field '" + WireField.OUTCOME.wireName() + "' has unexpected type "
                    + value.getClass().getName());
        }
        // Пустая строка и так провалится в TracingPolicyDeliveryResult.valueOf(""); эта проверка
        // не закрывает дыру, а только даёт внятное сообщение прямо на границе декодирования.
        if (outcome.isBlank()) {
            throw invalid(WireField.OUTCOME.wireName() + " must not be blank");
        }

        return new PrivateControlResponse(outcome);
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException(OPERATION + ": " + reason);
    }
}
