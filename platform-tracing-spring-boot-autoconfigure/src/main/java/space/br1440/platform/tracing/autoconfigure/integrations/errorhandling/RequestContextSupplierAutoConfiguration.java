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
 * Авто-конфигурация поставщика {@link RequestTraceContextSnapshot} для платформенного решения по error-handling'у.
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
