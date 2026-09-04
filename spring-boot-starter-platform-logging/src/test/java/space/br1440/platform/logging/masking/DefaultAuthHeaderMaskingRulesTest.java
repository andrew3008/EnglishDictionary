package space.br1440.platform.logging.masking;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.zalando.logbook.HeaderFilter;
import org.zalando.logbook.HttpHeaders;
import space.br1440.platform.logging.configuration.LoggingAutoConfiguration;
import space.br1440.platform.logging.configuration.LoggingProperties;
import space.br1440.platform.logging.logbook.filter.HeaderSensitiveDataFilter;
import space.br1440.platform.logging.masking.model.ScalarMaskRule;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAuthHeaderMaskingRulesTest {

    private static final String TOKEN = "Bearer secret-token";
    private static final String USER_RIGHTS = "{\"email\":\"user@example.com\",\"permissions\":[\"admin\"]}";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LoggingAutoConfiguration.class));

    @Test
    void defaultRulesDeclareAuthorizationAndXAuthHeader() {
        Set<String> applyToKeys = defaultScalarApplyToKeys();

        assertThat(applyToKeys).contains("authorization", "x-auth-header");
    }

    @ParameterizedTest
    @ValueSource(strings = {"authorization", "x-auth-header"})
    void defaultMaskerMasksAuthHeadersWithoutApplicationConfig(String header) {
        SensitiveDataMasker masker = defaultMasker();

        assertThat(masker.maskValue(header, TOKEN)).isEqualTo("*****");
        assertThat(masker.maskValue("content-type", "application/json")).isEqualTo("application/json");
    }

    @Test
    void defaultHttpHeaderFilterMasksAuthHeaders() {
        HeaderFilter filter = new HeaderSensitiveDataFilter(defaultMasker());

        HttpHeaders filtered = filter.filter(HttpHeaders.of(Map.of(
                "authorization", List.of(TOKEN),
                "x-auth-header", List.of(USER_RIGHTS),
                "content-type", List.of("application/json")
        )));

        assertThat(filtered.get("authorization")).containsExactly("*****");
        assertThat(filtered.get("x-auth-header")).containsExactly("*****");
        assertThat(filtered.get("content-type")).containsExactly("application/json");
    }

    @Test
    void autoconfiguredMaskerUsesDefaultAuthHeaderRules() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            SensitiveDataMasker masker = context.getBean(SensitiveDataMasker.class);
            assertThat(masker.maskValue("authorization", TOKEN)).isEqualTo("*****");
            assertThat(masker.maskValue("x-auth-header", USER_RIGHTS)).isEqualTo("*****");
        });
    }

    private static SensitiveDataMasker defaultMasker() {
        return new PlatformSensitiveDataMasker(SensitiveDataRulesLoader.loadRules(new LoggingProperties()));
    }

    private static Set<String> defaultScalarApplyToKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (ScalarMaskRule rule : SensitiveDataRulesLoader.loadRules(new LoggingProperties()).getScalarRules()) {
            keys.addAll(rule.applyToKeys());
        }
        return keys;
    }
}
