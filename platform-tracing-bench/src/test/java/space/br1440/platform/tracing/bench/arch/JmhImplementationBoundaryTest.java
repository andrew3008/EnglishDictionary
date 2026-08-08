package space.br1440.platform.tracing.bench.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * G-PROP-6 (propagation cleanup, §5.5/§8.3 плана): {@code platform-tracing-bench} не должен иметь
 * compile-time dependency на package-private implementation-типы propagation-пакета
 * ({@code TraceControlPropagator}, {@code FilteringBaggagePropagator},
 * {@code SafeTextMapPropagator}). Benchmark обязан измерять итоговый {@code TextMapPropagator},
 * полученный через реальный autoconfigure/SPI boundary, а не через прямое конструирование
 * package-private типа.
 *
 * <p>Правило введено уже в Slice 1 (до сужения visibility в Slice 4), чтобы regression-gate
 * действовал на итоговом HEAD независимо от порядка коммитов narrowing/JMH-миграции.
 */
@DisplayName("G-PROP-6: JMH harness не зависит от propagation implementation-типов")
class JmhImplementationBoundaryTest {

    private static final List<String> FORBIDDEN_IMPORT_LINES = List.of(
            "import space.br1440.platform.tracing.otel.javaagent.propagation.TraceControlPropagator;",
            "import space.br1440.platform.tracing.otel.javaagent.propagation.FilteringBaggagePropagator;",
            "import space.br1440.platform.tracing.otel.javaagent.propagation.SafeTextMapPropagator;");

    @Test
    @DisplayName("исходники src/jmh/java не импортируют package-private implementation-типы propagation")
    void jmhSourcesDoNotImportPropagationImplementationTypes() throws IOException {
        Path jmhRoot = Path.of("src", "jmh", "java");
        assertThat(Files.isDirectory(jmhRoot))
                .as("src/jmh/java должен существовать в platform-tracing-bench")
                .isTrue();

        try (Stream<Path> files = Files.walk(jmhRoot)) {
            List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
            assertThat(javaFiles).as("в src/jmh/java должны быть *.java источники").isNotEmpty();

            for (Path file : javaFiles) {
                String content = Files.readString(file);
                for (String forbiddenImport : FORBIDDEN_IMPORT_LINES) {
                    assertThat(content)
                            .as("%s не должен зависеть от propagation implementation-типа: %s",
                                    file, forbiddenImport)
                            .doesNotContain(forbiddenImport);
                }
            }
        }
    }
}
