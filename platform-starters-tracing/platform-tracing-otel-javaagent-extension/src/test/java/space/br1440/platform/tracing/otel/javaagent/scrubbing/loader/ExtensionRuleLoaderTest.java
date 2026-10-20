package space.br1440.platform.tracing.otel.javaagent.scrubbing.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

class ExtensionRuleLoaderTest {

    private static ExtensionRuleLoader loader() {
        return new ExtensionRuleLoader(ExtensionRuleLoader.ValidationMode.WARNING);
    }

    @Test
    void empty_property_yields_zero_rules_without_errors() {
        // when/then
        try (ExtensionRuleLoader loader = loader()) {
            assertThat(loader.load(null)).isEmpty();
            assertThat(loader.load("   ")).isEmpty();
            assertThat(loader.load(", ,")).isEmpty();
            assertThat(loader.getFailedProviders()).isZero();
        }
    }

    @Test
    void nonexistent_path_is_recorded_as_MISSING_PATH() {
        // when/then
        try (ExtensionRuleLoader loader = loader()) {
            assertThat(loader.load("/no/such/path/rules.jar")).isEmpty();
            assertThat(loader.getFailedEntries())
                    .extracting(ExtensionRuleLoader.FailedEntry::reason)
                    .containsExactly(FailedProviderReason.MISSING_PATH);
        }
    }

    @Test
    void jar_with_forbidden_class_is_rejected_by_hygiene(@TempDir Path dir) throws IOException {
        // given
        Path jar = dir.resolve("dirty.jar");
        writeJar(jar, Map.of(
                "io/opentelemetry/Foo.class", "x",
                "space/br1440/platform/tracing/api/spi/SpanAttributeScrubbingRule.class", "y"));

        // when/then
        try (ExtensionRuleLoader loader = loader()) {
            assertThat(loader.load(jar.toString())).isEmpty();
            assertThat(loader.getFailedEntries())
                    .extracting(ExtensionRuleLoader.FailedEntry::reason)
                    .containsExactly(FailedProviderReason.FORBIDDEN_CLASSES);
        }
    }

    @Test
    void corrupt_jar_is_recorded_as_UNREADABLE_FILE(@TempDir Path dir) throws IOException {
        // given
        Path jar = dir.resolve("corrupt.jar");
        Files.writeString(jar, "not a jar");

        // when
        try (ExtensionRuleLoader loader = loader()) {
            loader.load(jar.toString());

            // then
            assertThat(loader.getFailedEntries())
                    .extracting(ExtensionRuleLoader.FailedEntry::reason)
                    .containsExactly(FailedProviderReason.UNREADABLE_FILE);
        }
    }

    @Test
    void corrupt_jar_in_ERROR_mode_preserves_inspection_cause(@TempDir Path dir) throws IOException {
        // given
        Path jar = dir.resolve("corrupt.jar");
        Files.writeString(jar, "not a jar");

        // when
        // then
        try (ExtensionRuleLoader loader =
                     new ExtensionRuleLoader(ExtensionRuleLoader.ValidationMode.ERROR)) {
            assertThatThrownBy(() -> loader.load(jar.toString()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("UNREADABLE_FILE")
                    .hasCauseInstanceOf(IOException.class);
        }
    }

    @Test
    void non_class_entries_with_forbidden_prefix_do_not_trigger_hygiene(@TempDir Path dir) throws IOException {
        // Записи с префиксом space/br1440 и io/opentelemetry, но НЕ *.class, не должны давать
        // ложного hygiene-отказа (Javadoc, POM, package-info.html и т.п.).
        // given
        Path jar = dir.resolve("clean.jar");
        writeJar(jar, Map.of(
                "space/br1440/platform/tracing/api/spi/package-info.html", "<html/>",
                "io/opentelemetry/notes.txt", "doc",
                "META-INF/maven/space/br1440/pom.xml", "<project/>"));

        // when/then
        try (ExtensionRuleLoader loader = loader()) {
            // Провайдеров нет (нет META-INF/services), но и hygiene-отказа быть не должно.
            assertThat(loader.load(jar.toString())).isEmpty();
            assertThat(loader.getFailedProviders()).isZero();
        }
    }

    @Test
    void corrupt_services_file_is_recorded_as_SERVICE_CONFIGURATION_ERROR(@TempDir Path dir) throws IOException {
        // META-INF/services ссылается на несуществующий класс — JAR не должен валить старт.
        // given
        Path jar = dir.resolve("broken.jar");
        writeJar(jar, Map.of(
                "META-INF/services/space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule",
                "com.example.DoesNotExist"));

        // when/then
        try (ExtensionRuleLoader loader = loader()) {
            assertThat(loader.load(jar.toString())).isEmpty();
            assertThat(loader.getFailedEntries())
                    .extracting(ExtensionRuleLoader.FailedEntry::reason)
                    .containsExactly(FailedProviderReason.SERVICE_CONFIGURATION_ERROR);
        }
    }

    @Test
    void ERROR_mode_throws_on_hygiene_violation(@TempDir Path dir) throws IOException {
        // given
        Path jar = dir.resolve("dirty.jar");
        writeJar(jar, Map.of("io/opentelemetry/Foo.class", "x"));

        // when/then
        try (ExtensionRuleLoader loader =
                     new ExtensionRuleLoader(ExtensionRuleLoader.ValidationMode.ERROR)) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> loader.load(jar.toString()));
        }
    }

    @Test
    void same_jar_listed_twice_is_deduplicated_by_canonical_path(@TempDir Path dir) throws IOException {
        // Один и тот же JAR передан и как файл, и через директорию: должен учитываться один раз
        // и не попадать в failed (это не ошибка, а штатная дедупликация).
        // given
        Path jar = dir.resolve("rules.jar");
        writeJar(jar, Map.of("com/example/Marker.txt", "x"));

        String property = jar + "," + dir;
        // when/then
        try (ExtensionRuleLoader loader = loader()) {
            assertThat(loader.load(property)).isEmpty();
            assertThat(loader.getFailedProviders()).isZero();
        }
    }

    @Test
    void linkage_error_is_rethrown_as_fatal_and_not_classified_as_sanitizer_failure() {
        // given
        NoClassDefFoundError linkage = new NoClassDefFoundError("company/MandatoryRule");
        // when/then
        org.junit.jupiter.api.Assertions.assertThrows(NoClassDefFoundError.class,
                () -> PlatformThrowables.propagateFatalCauseChain(linkage));
    }

    @Test
    void linkage_error_wrapped_as_ServiceConfigurationError_cause_is_still_fatal() {
        // given
        NoClassDefFoundError linkage = new NoClassDefFoundError("company/MandatoryRule");
        ServiceConfigurationError wrapper = new ServiceConfigurationError("provider init failed", linkage);

        // when/then
        org.junit.jupiter.api.Assertions.assertThrows(NoClassDefFoundError.class,
                () -> PlatformThrowables.propagateFatalCauseChain(wrapper));
    }

    @Test
    void ordinary_RuntimeException_in_WARNING_mode_is_still_skipped() {
        // given/when
        PlatformThrowables.propagateFatalCauseChain(new IllegalStateException("provider misbehaved"));
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
