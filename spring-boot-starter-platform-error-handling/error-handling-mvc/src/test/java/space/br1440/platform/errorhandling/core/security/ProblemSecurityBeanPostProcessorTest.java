package space.br1440.platform.errorhandling.core.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.web.servlet.HandlerExceptionResolver;
import space.br1440.platform.errorhandling.testing.MockSecurityTest;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@MockSecurityTest
@DisplayName("ProblemSecurityBeanPostProcessor")
class ProblemSecurityBeanPostProcessorTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SecurityProblemSupport> mockProvider() {
        return mock(ObjectProvider.class);
    }

    @SuppressWarnings("unchecked")
    private static void setupIfAvailable(ObjectProvider<SecurityProblemSupport> provider,
                                          SecurityProblemSupport support) {
        doAnswer(inv -> {
            Consumer<SecurityProblemSupport> consumer = inv.getArgument(0, Consumer.class);
            consumer.accept(support);
            return null;
        }).when(provider).ifAvailable(any());
    }

    @Nested
    @DisplayName("Фильтрация бинов по типу")
    class BeanTypeFiltering {

        @Test
        @DisplayName("Не-HttpSecurity бин проходит без изменений")
        void nonHttpSecurityBean_passedThrough() {
            // given
            ObjectProvider<SecurityProblemSupport> provider = mockProvider();
            ProblemSecurityBeanPostProcessor processor = new ProblemSecurityBeanPostProcessor(provider);
            String bean = "some-string-bean";

            // when
            Object result = processor.postProcessAfterInitialization(bean, "stringBean");

            // then
            assertThat(result).isSameAs(bean);
            verify(provider, never()).ifAvailable(any());
        }

        @Test
        @DisplayName("HttpSecurity бин запускает ifAvailable")
        void httpSecurityBean_triggersIfAvailable() {
            // given
            ObjectProvider<SecurityProblemSupport> provider = mockProvider();
            ProblemSecurityBeanPostProcessor processor = new ProblemSecurityBeanPostProcessor(provider);
            HttpSecurity http = mock(HttpSecurity.class);

            // when
            processor.postProcessAfterInitialization(http, "httpSecurity");

            // then
            verify(provider).ifAvailable(any());
        }
    }

    @Nested
    @DisplayName("Конфигурация HttpSecurity")
    class HttpSecurityConfiguration {

        @Test
        @DisplayName("HttpSecurity бин обогащается exceptionHandling при доступном SecurityProblemSupport")
        void httpSecurityBean_configuredWithExceptionHandling() throws Exception {
            // given
            HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
            SecurityProblemSupport support = new SecurityProblemSupport(resolver);
            ObjectProvider<SecurityProblemSupport> provider = mockProvider();
            setupIfAvailable(provider, support);

            HttpSecurity http = mock(HttpSecurity.class);
            when(http.exceptionHandling(any())).thenReturn(http);
            ProblemSecurityBeanPostProcessor processor = new ProblemSecurityBeanPostProcessor(provider);

            // when
            Object result = processor.postProcessAfterInitialization(http, "httpSecurity");

            // then
            assertThat(result).isSameAs(http);
            verify(http).exceptionHandling(any());
        }
    }

    @Nested
    @DisplayName("Обработка ошибок")
    class ErrorHandling {

        @Test
        @DisplayName("При отсутствии SecurityProblemSupport в ObjectProvider -- ничего не происходит")
        void noSecurityProblemSupport_noOp() {
            // given
            ObjectProvider<SecurityProblemSupport> provider = mockProvider();
            ProblemSecurityBeanPostProcessor processor = new ProblemSecurityBeanPostProcessor(provider);
            HttpSecurity http = mock(HttpSecurity.class);

            // when / then
            assertThatCode(() -> processor.postProcessAfterInitialization(http, "httpSecurity"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("При exception в exceptionHandling -- бин не ломается")
        void exceptionInConfiguration_beanNotBroken() throws Exception {
            // given
            HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
            SecurityProblemSupport support = new SecurityProblemSupport(resolver);
            ObjectProvider<SecurityProblemSupport> provider = mockProvider();
            setupIfAvailable(provider, support);

            HttpSecurity http = mock(HttpSecurity.class);
            when(http.exceptionHandling(any())).thenThrow(new RuntimeException("config error"));
            ProblemSecurityBeanPostProcessor processor = new ProblemSecurityBeanPostProcessor(provider);

            // when
            Object result = processor.postProcessAfterInitialization(http, "httpSecurity");

            // then
            assertThat(result).isSameAs(http);
        }
    }
}
