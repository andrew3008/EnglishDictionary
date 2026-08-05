package space.br1440.platform.tracing.autoconfigure.integrations.async;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncAnnotationBeanPostProcessor;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;

@AutoConfiguration
@AutoConfigureAfter(TracingRuntimeAutoConfiguration.class)
@ConditionalOnClass({Async.class, AsyncAnnotationBeanPostProcessor.class})
@ConditionalOnBean({TraceOperations.class, AsyncAnnotationBeanPostProcessor.class})
@ConditionalOnProperty(prefix = TracingProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
public class AsyncRequestIdentityMdcAutoConfiguration {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnMissingBean(name = "platformAsyncRequestIdentityMdcProjectionBeanPostProcessor")
    public static BeanPostProcessor platformAsyncRequestIdentityMdcProjectionBeanPostProcessor(TraceOperations traceOperations) {
        return new AsyncRequestIdentityMdcProjectionBeanPostProcessor(traceOperations);
    }
}
