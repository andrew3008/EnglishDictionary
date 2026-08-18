package space.br1440.platform.tracing.autoconfigure.control.configserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.mock.env.MockEnvironment;

class ConfigServerSettingsProvenanceClassifierTest {

    private static final String POLICY_KEY = "platform.tracing.policy.diagnostics.log-level";

    @Test
    void configurationPropertiesFacadeIsSkippedInFavorOfConfigServerOrigin() {
        // given
        MockEnvironment environment = new MockEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.addFirst(new MapPropertySource(
                "configserver:http://config-server:8888",
                Map.of(POLICY_KEY, "INFO")));
        sources.addFirst(new MapPropertySource(
                "configurationProperties",
                Map.of(POLICY_KEY, "INFO")));
        ConfigServerSettingsProvenanceClassifier classifier =
                new ConfigServerSettingsProvenanceClassifier(environment);

        // when / then
        assertThat(classifier.classify(POLICY_KEY))
                .isEqualTo(ConfigServerSettingsProvenanceClassifier.ProvenanceClass.AUTHORITATIVE);
        assertThat(classifier.originSourceName(POLICY_KEY))
                .isEqualTo("configserver:http://config-server:8888");
    }

    @Test
    void nativeConfigServerFileUriIsAuthoritative() {
        // given
        ConfigServerSettingsProvenanceClassifier classifier =
                classifier("file:/config-live/tracing-client.yml");

        // when / then
        assertThat(classifier.classify(POLICY_KEY))
                .isEqualTo(ConfigServerSettingsProvenanceClassifier.ProvenanceClass.AUTHORITATIVE);
    }

    @Test
    void localApplicationFileStillRejectedAsOverride() {
        // given
        ConfigServerSettingsProvenanceClassifier classifier =
                classifier("applicationConfig: [file:./application.yml]");

        // when / then
        assertThat(classifier.classify(POLICY_KEY))
                .isEqualTo(ConfigServerSettingsProvenanceClassifier.ProvenanceClass.LOCAL_OVERRIDE);
    }

    @Test
    void facadeAloneWithoutOriginIsUnknown() {
        // given
        ConfigServerSettingsProvenanceClassifier classifier = classifier("configurationProperties");

        // when / then
        assertThat(classifier.classify(POLICY_KEY))
                .isEqualTo(ConfigServerSettingsProvenanceClassifier.ProvenanceClass.UNKNOWN);
        assertThat(classifier.originSourceName(POLICY_KEY)).isNull();
    }

    private static ConfigServerSettingsProvenanceClassifier classifier(String sourceName) {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(sourceName, Map.of(POLICY_KEY, "INFO")));
        return new ConfigServerSettingsProvenanceClassifier(environment);
    }
}
