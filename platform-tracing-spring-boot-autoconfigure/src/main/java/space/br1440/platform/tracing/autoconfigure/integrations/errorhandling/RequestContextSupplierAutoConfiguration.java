package space.br1440.platform.tracing.autoconfigure.integrations.errorhandling;

import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.context.RequestTraceContextSnapshot;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

/**
 * Авто-конфигурация поставщика {@link RequestTraceContextSnapshot} для платформенного error-handling.
 * <p>
 * <b>Намеренно не имеет зависимостей от tracing-core бинов:</b>
 * атрибут {@code after = TracingCoreAutoConfiguration.class} не указан осознанно —
 * {@link RequestTraceContextSnapshotSupplier} получает optional public facade через provider. <b>Не добавлять</b>
 * {@code after}/{@code before} «по аналогии с соседними классами»: это сделает регистрацию
 * supplier''а зависимой от {@code platform.tracing.enabled}
 * и сломает контракт «errorhandling всегда получает снимок контекста, даже при выключенном tracing».
 */
@AutoConfiguration
public class RequestContextSupplierAutoConfiguration {

    @Bean
    @ConditionalOnBean(TracingRuntime.class)
    @ConditionalOnMissingBean
    public RequestIdentityBoundarySupport requestIdentityBoundarySupport(TracingRuntime tracingRuntime) {
        return new RequestIdentityBoundarySupport(tracingRuntime);
    }

    @Bean(name = "platformRequestTraceContextSnapshotSupplier")
    @ConditionalOnMissingBean(name = "platformRequestTraceContextSnapshotSupplier")
    public Supplier<RequestTraceContextSnapshot> platformRequestTraceContextSnapshotSupplier(ObjectProvider<TraceOperations> traceOperationsProvider) {
        return new RequestTraceContextSnapshotSupplier(traceOperationsProvider::getIfAvailable);
    }
}
