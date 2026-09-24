package space.br1440.platform.tracing.otel.javaagent.factory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import static org.assertj.core.api.Assertions.assertThat;

class ScrubbingAssemblySurfaceTest {

    @Test
    @DisplayName("PlatformExportProcessorFactory: публичен только конструктор с session runtime")
    void exportFactoryExposesOnlyTheRuntimeAwareConstructor() {
        // given/when/then
        assertOnlyRuntimeAwareConstructorIsPublic(PlatformExportProcessorFactory.class);
    }

    @Test
    @DisplayName("PlatformSpanProcessorFactory: публичен только конструктор с session runtime")
    void spanProcessorFactoryExposesOnlyTheRuntimeAwareConstructor() {
        // given/when/then
        assertOnlyRuntimeAwareConstructorIsPublic(PlatformSpanProcessorFactory.class);
    }

    private static void assertOnlyRuntimeAwareConstructorIsPublic(Class<?> factory) {
        for (Constructor<?> constructor : factory.getDeclaredConstructors()) {
            if (!Modifier.isPublic(constructor.getModifiers())) {
                continue;
            }

            assertThat(constructor.getParameterTypes())
                    .as("публичный конструктор %s обязан требовать session-scoped ScrubbingRuntime",
                            factory.getSimpleName())
                    .containsExactly(PlatformTracingMBeanRegistrar.class, ScrubbingRuntime.class);
        }

        assertThat(factory.getConstructors())
                .as("должен остаться ровно один публичный способ собрать %s", factory.getSimpleName())
                .hasSize(1);
    }
}
