package space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScrubbingRulesLoaderTest {

    private final ClassLoader cl = ScrubbingRulesLoaderTest.class.getClassLoader();

    @Test
    void loadsAdditionalRulesFromClasspathProperties() {
        // when
        List<String> rules = ScrubbingRulesLoader.load("classpath:tracing/scrubbing-rules-test.properties", cl);

        // then
        assertThat(rules).containsExactly("iban", "inn", "snils");
    }

    @Test
    void preservesOrderAndDeduplicatesEntries() {
        // Дедупликация и сохранение порядка проверяется через комбинацию built-in + additional
        // на уровне PlatformAutoConfigurationCustomizer; здесь проверяем сам loader на
        // нормализацию whitespace и устойчивость к неизвестным именам (loader сам их не валидирует —
        // resolution делает BuiltInSpanAttributeScrubbingRules.resolve, неизвестные станут warning'ом выше).
        // when
        List<String> rules = ScrubbingRulesLoader.load("classpath:tracing/scrubbing-rules-with-unknown.properties", cl);

        // then
        assertThat(rules).containsExactly("iban", "unknown-rule", "snils");
    }

    @Test
    void emptyPropertiesFileReturnsEmptyList() {
        // when
        List<String> rules = ScrubbingRulesLoader.load("classpath:tracing/scrubbing-rules-empty.properties", cl);

        // then
        assertThat(rules).isEmpty();
    }

    @Test
    void missingClasspathResourceReturnsEmptyListWithoutThrowing() {
        // Файл отсутствует — loader возвращает пустой список и пишет WARN в лог, но не бросает.
        // when
        List<String> rules = ScrubbingRulesLoader.load("classpath:tracing/no-such-file.properties", cl);

        // then
        assertThat(rules).isEmpty();
    }

    @Test
    void blankPathConfigReturnsEmptyList() {
        // Эмулируем отсутствие свойства через пустой путь без префикса.
        // when
        List<String> rules = ScrubbingRulesLoader.load("", cl);
        // then
        assertThat(rules).isEmpty();
    }

    @Test
    void nullPathReturnsEmptyList() {
        // ScrubbingExtensionConfig.rulesConfig() returns null when property is absent.
        // load(String, ClassLoader) must handle null without NPE.
        // when
        List<String> rules = ScrubbingRulesLoader.load(null, cl);
        // then
        assertThat(rules).isEmpty();
    }
}
