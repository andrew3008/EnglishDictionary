package space.br1440.platform.tracing.otel.extension.scrubbing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Юнит-тесты {@link ScrubbingRulesLoader}: чтение Properties-файла из classpath/файловой системы,
 * парсинг comma-separated значений, защита от ошибок чтения.
 */
class ScrubbingRulesLoaderTest {

    private final ClassLoader cl = ScrubbingRulesLoaderTest.class.getClassLoader();

    @Test
    void loadsAdditionalRulesFromClasspathProperties() {
        List<String> rules = ScrubbingRulesLoader.load("classpath:tracing/scrubbing-rules-test.properties", cl);

        assertThat(rules).containsExactly("iban", "inn", "snils");
    }

    @Test
    void preservesOrderAndDeduplicatesEntries() {
        // Дедупликация и сохранение порядка проверяется через комбинацию built-in + additional
        // на уровне PlatformAutoConfigurationCustomizer; здесь проверяем сам loader на
        // нормализацию whitespace и устойчивость к неизвестным именам (loader сам их не валидирует —
        // resolution делает BuiltInSpanAttributeScrubbingRules.resolve, неизвестные станут warning'ом выше).
        List<String> rules = ScrubbingRulesLoader.load("classpath:tracing/scrubbing-rules-with-unknown.properties", cl);

        assertThat(rules).containsExactly("iban", "unknown-rule", "snils");
    }

    @Test
    void emptyPropertiesFileReturnsEmptyList() {
        List<String> rules = ScrubbingRulesLoader.load("classpath:tracing/scrubbing-rules-empty.properties", cl);

        assertThat(rules).isEmpty();
    }

    @Test
    void missingClasspathResourceReturnsEmptyListWithoutThrowing() {
        // Файл отсутствует — loader возвращает пустой список и пишет WARN в лог, но не бросает.
        List<String> rules = ScrubbingRulesLoader.load("classpath:tracing/no-such-file.properties", cl);

        assertThat(rules).isEmpty();
    }

    @Test
    void blankPathConfigReturnsEmptyList() {
        // Эмулируем отсутствие свойства через пустой путь без префикса.
        List<String> rules = ScrubbingRulesLoader.load("", cl);
        assertThat(rules).isEmpty();
    }

    @Test
    void nullPathReturnsEmptyList() {
        // PR-4B: ScrubbingExtensionConfig.rulesConfig() returns null when property is absent.
        // load(String, ClassLoader) must handle null without NPE.
        List<String> rules = ScrubbingRulesLoader.load((String) null, cl);
        assertThat(rules).isEmpty();
    }
}
