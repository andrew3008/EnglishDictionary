package space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.SanitizerBootstrapException;

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
    void missingClasspathResourceFailsClosed() {
        // when
        // then
        assertThatThrownBy(() -> ScrubbingRulesLoader.load("classpath:tracing/no-such-file.properties", cl))
                .isInstanceOfSatisfying(SanitizerBootstrapException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(SanitizerBootstrapException.RULE_LOADING_FAILED);
                    assertThat(failure)
                            .hasMessageContaining("platform.tracing.scrubbing.rules-config")
                            .hasMessageContaining("classpath:tracing/no-such-file.properties");
                });
    }

    @Test
    void blankPathConfigReturnsEmptyList() {
        // when
        List<String> rules = ScrubbingRulesLoader.load("", cl);
        // then
        assertThat(rules).isEmpty();
    }

    @Test
    void nullPathReturnsEmptyList() {
        // when
        List<String> rules = ScrubbingRulesLoader.load(null, cl);
        // then
        assertThat(rules).isEmpty();
    }
}
