package space.br1440.platform.errorhandling.autoconfigure.core;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import space.br1440.platform.errorhandling.model.RequestContext;
import space.br1440.platform.tracing.api.context.TracingRequestContext;

import java.util.function.Supplier;

/**
 * Мост между модулем трассировки и моделью ошибок: превращает
 * {@code Supplier<TracingRequestContext>} в {@code Supplier<RequestContext>}.
 * <p>
 * Регистрируется только если на classpath есть тип {@link TracingRequestContext} и bean
 * {@code platformTracingRequestContextSupplier} (автоконфигурация
 * {@code RequestContextSupplierAutoConfiguration} модуля {@code platform-tracing-spring-boot-autoconfigure}).
 * <p>
 * Имя bean'а {@code tracingRequestContextSupplier} сохранено для совместимости с прежним контрактом Spring.
 */
@AutoConfiguration
@AutoConfigureAfter(name = "space.br1440.platform.tracing.autoconfigure.RequestContextSupplierAutoConfiguration")
@ConditionalOnClass(TracingRequestContext.class)
@ConditionalOnBean(name = "platformTracingRequestContextSupplier")
public class ErrorHandlingTracingRequestContextAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "tracingRequestContextSupplier")
    public Supplier<RequestContext> tracingRequestContextSupplier(
            @Qualifier("platformTracingRequestContextSupplier")
            Supplier<TracingRequestContext> platformTracingRequestContextSupplier) {
        return () -> {
            TracingRequestContext snapshot = platformTracingRequestContextSupplier.get();
            return new RequestContext(
                    snapshot.correlationId(),
                    snapshot.traceId(),
                    snapshot.spanId());
        };
    }
}
