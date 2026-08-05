package space.br1440.platform.tracing.autoconfigure.integrations.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Async;

import space.br1440.platform.tracing.api.TraceOperations;

class AsyncIdentityMdcAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AsyncIdentityMdcAutoConfiguration.class))
            .withBean(TraceOperations.class, () -> mock(TraceOperations.class));

    @Test
    void registersAdvisorWhenTracingIsAvailable() {
        contextRunner.run(context -> assertThat(context).hasBean("platformAsyncIdentityMdcAdvisor"));
    }

    @Test
    void doesNotRegisterAdvisorWhenTracingIsDisabled() {
        contextRunner
                .withPropertyValues("platform.tracing.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean("platformAsyncIdentityMdcAdvisor"));
    }

    @Test
    void matchesAsyncAnnotationOnMethodAndType() throws NoSuchMethodException {
        contextRunner.run(context -> {
            PointcutAdvisor advisor = context.getBean("platformAsyncIdentityMdcAdvisor", PointcutAdvisor.class);
            Method methodAnnotated = MethodAnnotatedAsyncService.class.getDeclaredMethod("execute");
            Method typeAnnotated = TypeAnnotatedAsyncService.class.getDeclaredMethod("execute");

            assertThat(advisor.getPointcut().getMethodMatcher()
                    .matches(methodAnnotated, MethodAnnotatedAsyncService.class)).isTrue();
            assertThat(advisor.getPointcut().getMethodMatcher()
                    .matches(typeAnnotated, TypeAnnotatedAsyncService.class)).isTrue();
        });
    }

    static class MethodAnnotatedAsyncService {

        @Async
        void execute() {
        }
    }

    @Async
    static class TypeAnnotatedAsyncService {

        void execute() {
        }
    }
}
