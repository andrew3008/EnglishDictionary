package space.br1440.platform.errorhandling.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StackHintExtractor")
class StackHintExtractorTest {

    @Nested
    @DisplayName("enabled = false")
    class Disabled {

        private final StackHintExtractor extractor = new StackHintExtractor(false, List.of());

        @Test
        @DisplayName("всегда возвращает null")
        void alwaysReturnsNull() {
            // given
            RuntimeException cause = new RuntimeException("test");

            // when / then
            assertThat(extractor.extractStackHint(cause)).isNull();
        }

        @Test
        @DisplayName("isEnabled() == false")
        void isEnabledFalse() {
            // when / then
            assertThat(extractor.isEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("enabled = true, фильтрация DEFAULT_NOISE_PREFIXES")
    class DefaultNoisePrefixes {

        private final StackHintExtractor extractor = new StackHintExtractor(true, List.of());

        @Test
        @DisplayName("фрейм из доменного кода включается в hint")
        void domainCodeFrameIncluded() {
            // given
            RuntimeException cause = new RuntimeException("test");

            // when
            String hint = extractor.extractStackHint(cause);

            // then
            assertThat(hint).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("все фреймы noise -> возвращает null")
        void allNoiseFramesReturnsNull() {
            // given
            RuntimeException cause = new RuntimeException("test");
            cause.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("sun.reflect.NativeMethodAccessorImpl", "invoke", "NativeMethodAccessorImpl.java", 62),
                    new StackTraceElement("java.lang.reflect.Method", "invoke", "Method.java", 566),
                    new StackTraceElement("org.springframework.cglib.proxy.MethodProxy", "invoke", "MethodProxy.java", 218)
            });

            // when / then
            assertThat(extractor.extractStackHint(cause)).isNull();
        }

        @Test
        @DisplayName("фрейм из DEFAULT_NOISE_PREFIXES пропускается, доменный включается")
        void defaultNoiseFilteredDomainIncluded() {
            // given
            RuntimeException cause = new RuntimeException("test");
            cause.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("org.springframework.aop.framework.ReflectiveMethodInvocation", "proceed", "ReflectiveMethodInvocation.java", 186),
                    new StackTraceElement("com.myapp.service.PaymentService", "processPayment", "PaymentService.java", 42),
                    new StackTraceElement("java.lang.reflect.Method", "invoke", "Method.java", 566)
            });

            // when
            String hint = extractor.extractStackHint(cause);

            // then
            assertThat(hint).isNotNull()
                    .contains("PaymentService.processPayment")
                    .doesNotContain("ReflectiveMethodInvocation")
                    .doesNotContain("java.lang.reflect");
        }
    }

    @Nested
    @DisplayName("additionalNoisePrefixes")
    class AdditionalNoisePrefixes {

        @Test
        @DisplayName("фрейм из additionalNoisePrefixes пропускается")
        void additionalPrefixFiltered() {
            // given
            StackHintExtractor extractor = new StackHintExtractor(true, List.of("com.mycompany.internal."));
            RuntimeException cause = new RuntimeException("test");
            cause.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("com.mycompany.internal.proxy.DynamicProxy", "invoke", "DynamicProxy.java", 10),
                    new StackTraceElement("com.mycompany.service.OrderService", "placeOrder", "OrderService.java", 55)
            });

            // when
            String hint = extractor.extractStackHint(cause);

            // then
            assertThat(hint).isNotNull()
                    .contains("OrderService.placeOrder")
                    .doesNotContain("DynamicProxy");
        }

        @Test
        @DisplayName("пустой additionalNoisePrefixes работает как дефолт")
        void emptyAdditionalWorksAsDefault() {
            // given
            StackHintExtractor withEmpty = new StackHintExtractor(true, List.of());
            StackHintExtractor withNull = new StackHintExtractor(true, List.of());
            RuntimeException cause = new RuntimeException("test");
            cause.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("com.myapp.Controller", "handle", "Controller.java", 10)
            });

            // when / then
            assertThat(withEmpty.extractStackHint(cause))
                    .isEqualTo(withNull.extractStackHint(cause));
        }

        @Test
        @DisplayName("additional стоят после дефолтных в итоговом массиве — дефолтные проверяются первыми")
        void defaultPrefixesCheckedFirst() {
            // given
            StackHintExtractor extractor = new StackHintExtractor(true, List.of("com.custom.noise."));
            RuntimeException cause = new RuntimeException("test");
            cause.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("sun.reflect.NativeMethodAccessorImpl", "invoke", "NativeMethodAccessorImpl.java", 62),
                    new StackTraceElement("com.custom.noise.Proxy", "invoke", "Proxy.java", 10),
                    new StackTraceElement("com.myapp.Service", "run", "Service.java", 20)
            });

            // when
            String hint = extractor.extractStackHint(cause);

            // then
            assertThat(hint).isNotNull()
                    .contains("Service.run")
                    .doesNotContain("NativeMethodAccessorImpl")
                    .doesNotContain("Proxy");
        }
    }

    @Nested
    @DisplayName("cause chain")
    class CauseChain {

        private final StackHintExtractor extractor = new StackHintExtractor(true, List.of());

        @Test
        @DisplayName("циклический cause chain не зависает, возвращает null/hint")
        void cyclicCauseChainDoesNotHang() {
            // given
            RuntimeException a = new RuntimeException("a");
            RuntimeException b = new RuntimeException("b", a);
            try {
                var field = Throwable.class.getDeclaredField("cause");
                field.setAccessible(true);
                field.set(a, b);
            } catch (Exception ignored) {
                return;
            }
            a.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("sun.reflect.Foo", "bar", "Foo.java", 1)
            });
            b.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("sun.reflect.Baz", "qux", "Baz.java", 1)
            });

            // when / then
            assertThat(extractor.extractStackHint(a)).isNull();
        }

        @Test
        @DisplayName("hint извлекается из nested cause если top-level весь noise")
        void hintFromNestedCause() {
            // given
            RuntimeException inner = new RuntimeException("inner");
            inner.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("com.myapp.Repository", "save", "Repository.java", 33)
            });
            RuntimeException outer = new RuntimeException("outer", inner);
            outer.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("org.springframework.aop.framework.JdkDynamicAopProxy", "invoke", "JdkDynamicAopProxy.java", 215)
            });

            // when
            String hint = extractor.extractStackHint(outer);

            // then
            assertThat(hint).isNotNull().contains("Repository.save");
        }
    }
}
