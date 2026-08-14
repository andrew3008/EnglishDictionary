package space.br1440.platform.tracing.otel.javaagent.factory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.jmx.registration.DomainMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Публичная поверхность сборки pipeline'а: фабрики можно собрать снаружи только вместе с
 * session-scoped {@link ScrubbingRuntime}. ArchUnit проверяет вызовы внутри модуля, а этот тест —
 * саму объявленную видимость, то есть то, что доступно стороннему customizer'у в другом пакете
 * (ADR-scrubbing-hybrid-processor-export-boundary.md, §9).
 */
class ScrubbingAssemblySurfaceTest {

    @Test
    @DisplayName("PlatformExportProcessorFactory: публичен только конструктор с session runtime")
    void exportFactoryExposesOnlyTheRuntimeAwareConstructor() {
        assertOnlyRuntimeAwareConstructorIsPublic(PlatformExportProcessorFactory.class);
    }

    @Test
    @DisplayName("PlatformSpanProcessorFactory: публичен только конструктор с session runtime")
    void spanProcessorFactoryExposesOnlyTheRuntimeAwareConstructor() {
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
                    .containsExactly(DomainMBeanRegistrar.class, ScrubbingRuntime.class);
        }

        assertThat(factory.getConstructors())
                .as("должен остаться ровно один публичный способ собрать %s", factory.getSimpleName())
                .hasSize(1);
    }
}
