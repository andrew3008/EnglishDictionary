package space.br1440.platform.tracing.autoconfigure.integrations.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncAnnotationAdvisor;
import org.springframework.scheduling.annotation.EnableAsync;

import space.br1440.platform.tracing.api.TraceOperations;

class AsyncRequestIdentityMdcAutoConfigurationTest {

    private static final String PROCESSOR_BEAN_NAME =
            "platformAsyncRequestIdentityMdcProjectionBeanPostProcessor";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AsyncRequestIdentityMdcAutoConfiguration.class))
            .withBean(TraceOperations.class, () -> mock(TraceOperations.class));

    @Test
    void doesNotRegisterAdvisorWithoutAsyncProcessing() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(PROCESSOR_BEAN_NAME));
    }

    @Test
    void registersInfrastructureAdvisorWhenAsyncProcessingIsEnabled() {
        contextRunner
                .withUserConfiguration(AsyncEnabledConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(AsyncRequestIdentityMdcAutoConfiguration.class);
                    assertThat(context).hasBean(PROCESSOR_BEAN_NAME);
                    assertThat(context.getBeanFactory().getBeanDefinition(PROCESSOR_BEAN_NAME).getRole())
                            .isEqualTo(BeanDefinition.ROLE_INFRASTRUCTURE);
                });
    }

    @Test
    void doesNotRegisterAdvisorWhenTracingIsDisabled() {
        contextRunner
                .withUserConfiguration(AsyncEnabledConfiguration.class)
                .withPropertyValues("platform.tracing.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PROCESSOR_BEAN_NAME));
    }

    @Test
    void matchesAsyncAnnotationOnMethodAndType() throws NoSuchMethodException {
        contextRunner
                .withUserConfiguration(AsyncEnabledConfiguration.class)
                .run(context -> {
                    Advised asyncService = (Advised) context.getBean(MethodAnnotatedAsyncService.class);
                    PointcutAdvisor advisor = projectionAdvisor(asyncService.getAdvisors());
                    Method methodAnnotated = MethodAnnotatedAsyncService.class.getDeclaredMethod("execute");
                    Method typeAnnotated = TypeAnnotatedAsyncService.class.getDeclaredMethod("execute");

                    assertThat(advisor.getPointcut().getMethodMatcher()
                            .matches(methodAnnotated, MethodAnnotatedAsyncService.class)).isTrue();
                    assertThat(advisor.getPointcut().getMethodMatcher()
                            .matches(typeAnnotated, TypeAnnotatedAsyncService.class)).isTrue();
                });
    }

    @Test
    void asyncAdvisorWrapsIdentityProjectionAdvisor() {
        contextRunner
                .withUserConfiguration(AsyncEnabledConfiguration.class)
                .run(context -> {
                    Advised asyncService = (Advised) context.getBean(MethodAnnotatedAsyncService.class);
                    Advisor[] advisors = asyncService.getAdvisors();

                    int asyncAdvisorIndex = indexOfType(advisors, AsyncAnnotationAdvisor.class);
                    int projectionAdvisorIndex = indexOfAdviceType(
                            advisors,
                            AsyncRequestIdentityMdcProjectionInterceptor.class);

                    assertThat(asyncAdvisorIndex).isGreaterThanOrEqualTo(0);
                    assertThat(projectionAdvisorIndex).isGreaterThan(asyncAdvisorIndex);
                });
    }

    private static int indexOfType(Advisor[] advisors, Class<? extends Advisor> type) {
        for (int index = 0; index < advisors.length; index++) {
            if (type.isInstance(advisors[index])) {
                return index;
            }
        }
        return -1;
    }

    private static int indexOfAdviceType(Advisor[] advisors, Class<?> adviceType) {
        for (int index = 0; index < advisors.length; index++) {
            if (adviceType.isInstance(advisors[index].getAdvice())) {
                return index;
            }
        }
        return -1;
    }

    private static PointcutAdvisor projectionAdvisor(Advisor[] advisors) {
        for (Advisor advisor : advisors) {
            if (advisor instanceof PointcutAdvisor pointcutAdvisor
                    && advisor.getAdvice() instanceof AsyncRequestIdentityMdcProjectionInterceptor) {
                return pointcutAdvisor;
            }
        }
        throw new AssertionError("Advisor проекции request identity не найден");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    static class AsyncEnabledConfiguration {

        @Bean
        MethodAnnotatedAsyncService methodAnnotatedAsyncService() {
            return new MethodAnnotatedAsyncService();
        }
    }

    static class MethodAnnotatedAsyncService {

        @Async
        public void execute() {
        }
    }

    @Async
    static class TypeAnnotatedAsyncService {

        public void execute() {
        }
    }
}
