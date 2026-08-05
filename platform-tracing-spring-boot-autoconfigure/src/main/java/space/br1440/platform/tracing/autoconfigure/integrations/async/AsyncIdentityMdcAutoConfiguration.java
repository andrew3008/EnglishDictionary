package space.br1440.platform.tracing.autoconfigure.integrations.async;

import org.aopalliance.aop.Advice;
import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.Async;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;

@AutoConfiguration
@AutoConfigureAfter(TracingRuntimeAutoConfiguration.class)
@ConditionalOnClass({Async.class, Advisor.class})
@ConditionalOnBean(TraceOperations.class)
@ConditionalOnProperty(prefix = TracingProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
public class AsyncIdentityMdcAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "platformAsyncIdentityMdcAdvisor")
    public Advisor platformAsyncIdentityMdcAdvisor(TraceOperations traceOperations) {
        Advice advice = new AsyncRequestIdentityMdcProjectionInterceptor(traceOperations);
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(asyncPointcut(), advice);
        advisor.setOrder(Ordered.LOWEST_PRECEDENCE);
        return advisor;
    }

    private static Pointcut asyncPointcut() {
        Pointcut classAnnotation = new AnnotationMatchingPointcut(Async.class, true);
        Pointcut methodAnnotation = new AnnotationMatchingPointcut(null, Async.class, true);
        return new ComposablePointcut(classAnnotation).union(methodAnnotation);
    }
}
