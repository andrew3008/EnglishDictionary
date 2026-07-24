package space.br1440.platform.tracing.otel.control.protocol;

import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolDecodeResult;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolKeys;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolOperation;

import java.util.Map;
import java.util.Objects;

/**
 * Фасад, замыкающий полный pipeline control-protocol:
 * <pre>
 *   decode  →  domain validate  →  apply
 * </pre>
 *
 * <p>Вызывающая сторона отвечает за выполнение шага decode (через
 * {@code TracingControlProtocolDecoder}) и передачу полученного
 * {@link TracingControlProtocolDecodeResult} в {@link #handle}. Этот класс
 * затем выполняет доменную / policy-валидацию и при успехе делегирует
 * внедрённому {@link RuntimePolicyApplier} фактическую мутацию состояния.
 *
 * <h2>Гарантии</h2>
 * <ul>
 *   <li>{@link RuntimePolicyApplier#apply} вызывается <em>только</em> когда
 *       и {@code decodeResult.valid()}, и {@code domainResult.valid()} равны {@code true}.</li>
 *   <li>Операции только для чтения ({@link TracingControlProtocolOperation#READ_APPLIED_STATE})
 *       коротко замыкаются до доменной валидации и никогда не доходят до applier.</li>
 *   <li>Метод не бросает исключения для ожидаемых ошибок протокола; все
 *       причины отклонения кодируются в возвращаемом {@link RuntimePolicyControlHandleResult}.</li>
 * </ul>
 *
 * <h2>Потокобезопасность</h2>
 * После конструкции класс не хранит изменяемого состояния и поэтому
 * потокобезопасен сам по себе, при условии, что внедрённая реализация {@link RuntimePolicyApplier} также потоко-безопасна.
 */
public final class RuntimePolicyControlHandler {

    private final RuntimePolicyApplier applier;
    private final RuntimeControlMutationPolicy mutationPolicy;

    public RuntimePolicyControlHandler(RuntimePolicyApplier applier) {
        this(applier, RuntimeControlMutationPolicy.startupConfigured(false));
    }

    public RuntimePolicyControlHandler(RuntimePolicyApplier applier,
                                       RuntimeControlMutationPolicy mutationPolicy) {
        this.applier = Objects.requireNonNull(applier, "applier");
        this.mutationPolicy = Objects.requireNonNull(mutationPolicy, "mutationPolicy");
    }

    /**
     * Обрабатывает один декодированный запрос протокола.
     *
     * @param decodeResult результат, полученный от
     *                     {@code TracingControlProtocolDecoder.decode(Map)}
     * @return результат, указывающий на успех или причину отклонения;
     *         никогда не {@code null}
     */
    public RuntimePolicyControlHandleResult handle(TracingControlProtocolDecodeResult decodeResult) {
        Objects.requireNonNull(decodeResult, "decodeResult");

        // --- Шаг 1: структурный / wire decode ---
        if (!decodeResult.valid()) {
            return RuntimePolicyControlHandleResult.decodeRejected(decodeResult.violations());
        }

        TracingControlProtocolOperation operation = decodeResult.operation()
                .orElseThrow(() -> new IllegalStateException(
                        "Decode result is valid but operation is absent – decoder contract violation"));

        // --- Шаг 2: короткое замыкание для операций только для чтения ---
        if (operation == TracingControlProtocolOperation.READ_APPLIED_STATE) {
            return RuntimePolicyControlHandleResult.success(operation);
        }

        // --- Шаг 3: доменная / policy-валидация ---
        Map<String, Object> payload = decodeResult.normalizedPayload();
        TracingControlDomainValidationResult domainResult = RuntimePolicyControlDomainValidator.validate(payload);
        if (!domainResult.valid()) {
            return RuntimePolicyControlHandleResult.domainRejected(operation, domainResult.violations());
        }

        // VALIDATE выполняет только domain-проверку и не меняет runtime-состояние.
        if (operation == TracingControlProtocolOperation.VALIDATE_RUNTIME_POLICY) {
            return RuntimePolicyControlHandleResult.success(operation);
        }

        // Политика проверяется только для уже корректной мутирующей операции.
        RuntimeControlMutationDecision decision = mutationPolicy.evaluate(operation);
        if (!decision.allowed()) {
            return RuntimePolicyControlHandleResult.mutationRejected(operation, decision.reason());
        }

        // --- Шаг 4: apply (только мутирующие операции) ---
        String source = resolveSource(payload);
        applier.apply(operation, payload, source);

        return RuntimePolicyControlHandleResult.success(operation);
    }

    private static String resolveSource(Map<String, Object> payload) {
        Object raw = payload.get(TracingControlProtocolKeys.SOURCE);
        return (raw instanceof String s && !s.isBlank()) ? s : "JMX";
    }
}
