package space.br1440.platform.errorhandling.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReflectionUtils")
class ReflectionUtilsTest {

    @Nested
    @DisplayName("invokeMethod")
    class InvokeMethod {

        @Test
        void invokesNoArgGetterAndPassesResultToConsumer() {
            // given
            AtomicReference<Object> holder = new AtomicReference<>();

            // when
            ReflectionUtils.invokeMethod("hello", "length", holder::set);

            // then
            assertThat(holder.get()).isEqualTo(5);
        }

        @Test
        void swallowsMissingMethod_leavesConsumerUnused() {
            // given
            AtomicReference<Object> holder = new AtomicReference<>("x");

            // when
            ReflectionUtils.invokeMethod("hello", "nonexistentMethod", holder::set);

            // then
            assertThat(holder.get()).isEqualTo("x");
        }

    }

    @Nested
    @DisplayName("loadOptionalClass")
    class LoadOptionalClass {

        @Test
        void loadsExistingClass() {
            // when / then
            assertThat(ReflectionUtils.loadOptionalClass("java.lang.String", null))
                    .isEqualTo(String.class);
        }

        @Test
        void returnsNullForMissingClass() {
            // when / then
            assertThat(ReflectionUtils.loadOptionalClass(
                    "space.br1440.platform.errorhandling.nonexistent.TypeXyz", null)).isNull();
        }

        @Test
        void usesProvidedClassLoader() {
            // given
            ClassLoader cl = ClassLoader.getSystemClassLoader();

            // when / then
            assertThat(ReflectionUtils.loadOptionalClass("java.lang.Integer", cl))
                    .isEqualTo(Integer.class);
        }
    }

}
