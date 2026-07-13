package space.br1440.platform.tracing.otel.extension.scrubbing.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.br1440.platform.tracing.otel.extension.scrubbing.diagnostics.FailedProviderReason;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты загрузчика внешних правил (PR-2): пустое свойство, отсутствующий путь, JAR hygiene по
 * {@code *.class}, режим валидации (LENIENT/STRICT), дедупликация canonical-путей.
 */
class ExtensionRuleLoaderTest {

    private static ExtensionRuleLoader loader() {
        return new ExtensionRuleLoader(ExtensionRuleLoader.ValidationMode.LENIENT, Set.of());
    }

    @Test
    void пустое_свойство_даёт_ноль_правил_без_ошибок() {
        try (ExtensionRuleLoader loader = loader()) {
            assertThat(loader.load(null)).isEmpty();
            assertThat(loader.load("   ")).isEmpty();
            assertThat(loader.load(", ,")).isEmpty();
            assertThat(loader.getFailedProviders()).isZero();
        }
    }

    @Test
    void несуществующий_путь_фиксируется_как_MISSING_PATH() {
        try (ExtensionRuleLoader loader = loader()) {
            assertThat(loader.load("/no/such/path/rules.jar")).isEmpty();
            assertThat(loader.getFailedEntries())
                    .extracting(ExtensionRuleLoader.FailedEntry::reason)
                    .containsExactly(FailedProviderReason.MISSING_PATH);
        }
    }

    @Test
    void jar_с_запрещённым_class_отбраковывается_hygiene(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve("dirty.jar");
        writeJar(jar, Map.of(
                "io/opentelemetry/Foo.class", "x",
                "space/br1440/platform/tracing/api/spi/SpanAttributeScrubbingRule.class", "y"));

        try (ExtensionRuleLoader loader = loader()) {
            assertThat(loader.load(jar.toString())).isEmpty();
            assertThat(loader.getFailedEntries())
                    .extracting(ExtensionRuleLoader.FailedEntry::reason)
                    .containsExactly(FailedProviderReason.FORBIDDEN_CLASSES);
        }
    }

    @Test
    void не_class_записи_с_запрещённым_префиксом_не_триггерят_hygiene(@TempDir Path dir) throws IOException {
        // Записи с префиксом space/br1440 и io/opentelemetry, но НЕ *.class, не должны давать
        // ложного hygiene-отказа (Javadoc, POM, package-info.html и т.п.).
        Path jar = dir.resolve("clean.jar");
        writeJar(jar, Map.of(
                "space/br1440/platform/tracing/api/spi/package-info.html", "<html/>",
                "io/opentelemetry/notes.txt", "doc",
                "META-INF/maven/space/br1440/pom.xml", "<project/>"));

        try (ExtensionRuleLoader loader = loader()) {
            // Провайдеров нет (нет META-INF/services), но и hygiene-отказа быть не должно.
            assertThat(loader.load(jar.toString())).isEmpty();
            assertThat(loader.getFailedProviders()).isZero();
        }
    }

    @Test
    void битый_services_файл_фиксируется_как_SERVICE_CONFIGURATION_ERROR(@TempDir Path dir) throws IOException {
        // META-INF/services ссылается на несуществующий класс — JAR не должен валить старт.
        Path jar = dir.resolve("broken.jar");
        writeJar(jar, Map.of(
                "META-INF/services/space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule",
                "com.example.DoesNotExist"));

        try (ExtensionRuleLoader loader = loader()) {
            assertThat(loader.load(jar.toString())).isEmpty();
            assertThat(loader.getFailedEntries())
                    .extracting(ExtensionRuleLoader.FailedEntry::reason)
                    .containsExactly(FailedProviderReason.SERVICE_CONFIGURATION_ERROR);
        }
    }

    @Test
    void duplicate_jar_при_LENIENT_пропускается_с_DUPLICATE_CONFIG(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve("rules.jar");
        writeJar(jar, Map.of("com/example/Marker.txt", "x"));
        String canonical = jar.toFile().getCanonicalPath();

        try (ExtensionRuleLoader loader =
                     new ExtensionRuleLoader(ExtensionRuleLoader.ValidationMode.LENIENT, Set.of(canonical))) {
            assertThat(loader.load(jar.toString())).isEmpty();
            assertThat(loader.getFailedEntries())
                    .extracting(ExtensionRuleLoader.FailedEntry::reason)
                    .containsExactly(FailedProviderReason.DUPLICATE_CONFIG);
        }
    }

    @Test
    void duplicate_jar_при_STRICT_бросает(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve("rules.jar");
        writeJar(jar, Map.of("com/example/Marker.txt", "x"));
        String canonical = jar.toFile().getCanonicalPath();

        try (ExtensionRuleLoader loader =
                     new ExtensionRuleLoader(ExtensionRuleLoader.ValidationMode.STRICT, Set.of(canonical))) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> loader.load(jar.toString()));
        }
    }

    @Test
    void STRICT_режим_бросает_на_нарушении_hygiene(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve("dirty.jar");
        writeJar(jar, Map.of("io/opentelemetry/Foo.class", "x"));

        try (ExtensionRuleLoader loader =
                     new ExtensionRuleLoader(ExtensionRuleLoader.ValidationMode.STRICT, Set.of())) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> loader.load(jar.toString()));
        }
    }

    @Test
    void тот_же_jar_указанный_дважды_дедуплицируется_по_canonical_path(@TempDir Path dir) throws IOException {
        // Один и тот же JAR передан и как файл, и через директорию: должен учитываться один раз
        // и не попадать в failed (это не ошибка, а штатная дедупликация).
        Path jar = dir.resolve("rules.jar");
        writeJar(jar, Map.of("com/example/Marker.txt", "x"));

        String property = jar.toString() + "," + dir.toString();
        try (ExtensionRuleLoader loader = loader()) {
            assertThat(loader.load(property)).isEmpty();
            assertThat(loader.getFailedProviders()).isZero();
        }
    }

    private static void writeJar(Path jar, Map<String, String> entries) throws IOException {
        try (OutputStream os = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(os)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                jos.putNextEntry(new JarEntry(e.getKey()));
                jos.write(e.getValue().getBytes());
                jos.closeEntry();
            }
        }
    }
}
