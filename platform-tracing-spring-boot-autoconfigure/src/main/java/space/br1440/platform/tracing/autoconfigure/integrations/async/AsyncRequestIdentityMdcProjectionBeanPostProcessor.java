package space.br1440.platform.tracing.autoconfigure.integrations.async;

import org.springframework.aop.Pointcut;
import org.springframework.aop.framework.autoproxy.AbstractBeanFactoryAwareAdvisingPostProcessor;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.Async;

import space.br1440.platform.tracing.api.TraceOperations;

final class AsyncRequestIdentityMdcProjectionBeanPostProcessor extends AbstractBeanFactoryAwareAdvisingPostProcessor {

    private static final int ORDER_BEFORE_ASYNC_PROCESSOR = (Ordered.LOWEST_PRECEDENCE - 1);

    AsyncRequestIdentityMdcProjectionBeanPostProcessor(TraceOperations traceOperations) {
        this.advisor = new DefaultPointcutAdvisor(
                asyncPointcut(),
                new AsyncRequestIdentityMdcProjectionInterceptor(traceOperations)
        );

        setBeforeExistingAdvisors(false);
        setOrder(ORDER_BEFORE_ASYNC_PROCESSOR);
    }

    private static Pointcut asyncPointcut() {
        Pointcut classAnnotation = new AnnotationMatchingPointcut(Async.class, true);
        Pointcut methodAnnotation = new AnnotationMatchingPointcut(null, Async.class, true);
        return new ComposablePointcut(classAnnotation).union(methodAnnotation);
    }
}
