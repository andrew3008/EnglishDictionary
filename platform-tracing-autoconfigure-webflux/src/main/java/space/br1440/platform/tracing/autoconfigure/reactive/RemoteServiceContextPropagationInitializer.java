package space.br1440.platform.tracing.autoconfigure.reactive;

import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Marker для подмены eager-init регистрации {@link RemoteServiceContextPropagation}.
 * <p>
 * Потребитель регистрирует собственный бин этого типа вместо
 * {@link ReactiveTracingAutoConfiguration#platformTracingContextPropagationEagerInit()}.
 */
public interface RemoteServiceContextPropagationInitializer extends SmartInitializingSingleton {
}
