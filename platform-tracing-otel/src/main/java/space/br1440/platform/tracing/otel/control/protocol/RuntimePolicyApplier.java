package space.br1440.platform.tracing.otel.control.protocol;

import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolOperation;

import java.util.Map;

/**
 * SPI применения нормализованного и доменно валидированного payload
 * control-protocol к runtime-состоянию платформы.
 *
 * <p>Реализации живут в {@code platform-tracing-otel-javaagent-extension} (или в
 * другом runtime-модуле) и внедряются в {@link RuntimePolicyControlHandler}. Такая инверсия зависимостей держит
 * {@code platform-tracing-otel} свободным от зависимости на extension-модуль.
 *
 * <p><b>Контракт:</b> метод вызывается {@link RuntimePolicyControlHandler}
 * <em>только</em> после успешной валидации на слоях decode и domain.
 * Реализации поэтому могут считать, что каждое значение в
 * {@code normalizedPayload} структурно корректно и доменно валидно;
 * повторная валидация не требуется и не ожидается.
 *
 * <p>Реализации должны быть потокобезопасны: потоки JMX могут вызывать
 * {@code apply} параллельно с другими management-операциями.
 */
public interface RuntimePolicyApplier {

    /**
     * Применяет декодированный и доменно валидированный payload к живому
     * runtime-состоянию.
     *
     * @param operation         разрешённая операция протокола
     *                          (на практике всегда
     *                          {@link TracingControlProtocolOperation#APPLY_RUNTIME_POLICY};
     *                          операции только для чтения коротко замыкаются до этого вызова)
     * @param normalizedPayload нормализованная map, полученная от decoder и
     *                          принятая доменным валидатором; никогда не
     *                          {@code null}, не изменяется после этого вызова
     * @param source            человеко-читаемая метка источника (например, {@code "JMX"}, {@code "REST"}, {@code "test"})
     */
    void apply(TracingControlProtocolOperation operation,
               Map<String, Object> normalizedPayload,
               String source);
}
