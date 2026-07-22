package space.br1440.platform.tracing.otel.control.protocol;

import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolOperation;

/**
 * Политика допуска мутаций unified runtime-control protocol.
 *
 * <p>Граница намеренно находится в {@code core}: она не зависит ни от JMX,
 * ни от Spring и может быть одинаково применена каждым runtime-адаптером.
 */
@FunctionalInterface
public interface RuntimeControlMutationPolicy {

    /**
     * Оценивает возможность выполнить операцию после успешной decode- и
     * domain-валидации, но до вызова applier.
     */
    RuntimeControlMutationDecision evaluate(TracingControlProtocolOperation operation);

    /**
     * Создаёт startup-политику с fail-closed поведением для APPLY.
     */
    static RuntimeControlMutationPolicy startupConfigured(boolean mutationEnabled) {
        return operation -> {
            if (operation != TracingControlProtocolOperation.APPLY_RUNTIME_POLICY) {
                return RuntimeControlMutationDecision.permitted();
            }
            if (mutationEnabled) {
                return RuntimeControlMutationDecision.permitted();
            }
            return RuntimeControlMutationDecision.rejected(
                    "runtime mutation is disabled; set platform.tracing.control.runtime-mutation.enabled=true at startup");
        };
    }
}
