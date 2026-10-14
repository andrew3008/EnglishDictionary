package space.br1440.platform.tracing.autoconfigure.control.configserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.CompositePropertySource;
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
                Map.of(POLICY_KEY, "INFO"))
        );
        sources.addFirst(new MapPropertySource(
                "configurationProperties",
                Map.of(POLICY_KEY, "INFO"))
        );
        ConfigServerSettingsProvenanceClassifier classifier =
                new ConfigServerSettingsProvenanceClassifier(environment);

        // when
        ConfigServerSettingsProvenanceClassifier.ProvenanceClass provenance = classifier.classify(POLICY_KEY);
        String sourceName = classifier.originSourceName(POLICY_KEY);

        // then
        assertThat(provenance)
                .isEqualTo(ConfigServerSettingsProvenanceClassifier.ProvenanceClass.AUTHORITATIVE);
        assertThat(sourceName).isEqualTo("configserver:http://config-server:8888");
    }

    @Test
    void nativeConfigServerFileLeafIsAuthoritative() {
        // given
        ConfigServerSettingsProvenanceClassifier classifier =
                classifier("configserver:file:/config-live/tracing-client.yml");

        // when
        ConfigServerSettingsProvenanceClassifier.ProvenanceClass provenance = classifier.classify(POLICY_KEY);

        // then
        assertThat(provenance)
                .isEqualTo(ConfigServerSettingsProvenanceClassifier.ProvenanceClass.AUTHORITATIVE);
    }

    @Test
    void rawFileSourceIsUnknownRatherThanAuthoritative() {
        // given
        ConfigServerSettingsProvenanceClassifier classifier =
                classifier("file:/config-live/tracing-client.yml");

        // when
        ConfigServerSettingsProvenanceClassifier.ProvenanceClass provenance = classifier.classify(POLICY_KEY);

        // then
        assertThat(provenance)
                .isEqualTo(ConfigServerSettingsProvenanceClassifier.ProvenanceClass.UNKNOWN);
    }

    @Test
    void currentBootLocalConfigDataNameIsRejectedAsOverride() {
        // given
        ConfigServerSettingsProvenanceClassifier classifier = classifier(
                "Config resource 'file [application.yml]' via location 'optional:file:./'");

        // when
        ConfigServerSettingsProvenanceClassifier.ProvenanceClass provenance = classifier.classify(POLICY_KEY);

        // then
        assertThat(provenance)
                .isEqualTo(ConfigServerSettingsProvenanceClassifier.ProvenanceClass.LOCAL_OVERRIDE);
    }

    @Test
    void authoritativeLeafInsideCompositeIsResolved() {
        // given
        MockEnvironment environment = new MockEnvironment();
        CompositePropertySource composite = new CompositePropertySource("config data");
        composite.addPropertySource(new MapPropertySource(
                "configserver:file:/config-live/tracing-client.yml",
                Map.of(POLICY_KEY, "INFO"))
        );
        environment.getPropertySources().addFirst(composite);
        ConfigServerSettingsProvenanceClassifier classifier =
                new ConfigServerSettingsProvenanceClassifier(environment);

        // when
        ConfigServerSettingsProvenanceClassifier.ProvenanceClass provenance = classifier.classify(POLICY_KEY);
        String sourceName = classifier.originSourceName(POLICY_KEY);

        // then
        assertThat(provenance)
                .isEqualTo(ConfigServerSettingsProvenanceClassifier.ProvenanceClass.AUTHORITATIVE);
        assertThat(sourceName).isEqualTo("configserver:file:/config-live/tracing-client.yml");
    }

    @Test
    void higherPrecedenceLocalLeafWinsOverAuthoritativeLeaf() {
        // given
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "configserver:application-prod.yml",
                Map.of(POLICY_KEY, "INFO")));
        environment.getPropertySources().addFirst(new MapPropertySource(
                "systemEnvironment",
                Map.of(POLICY_KEY, "DEBUG")));
        ConfigServerSettingsProvenanceClassifier classifier =
                new ConfigServerSettingsProvenanceClassifier(environment);

        // when
        ConfigServerSettingsProvenanceClassifier.ProvenanceClass provenance = classifier.classify(POLICY_KEY);

        // then
        assertThat(provenance)
                .isEqualTo(ConfigServerSettingsProvenanceClassifier.ProvenanceClass.LOCAL_OVERRIDE);
    }

    @Test
    void similarUnpinnedSourceNamesRemainUnknown() {
        assertThat(classifier("custom-configserver:copy").classify(POLICY_KEY))
                .isEqualTo(ConfigServerSettingsProvenanceClassifier.ProvenanceClass.UNKNOWN);

        assertThat(classifier("bootstrapProperties").classify(POLICY_KEY))
                .isEqualTo(ConfigServerSettingsProvenanceClassifier.ProvenanceClass.UNKNOWN);

        assertThat(classifier("configClient").classify(POLICY_KEY))
                .isEqualTo(ConfigServerSettingsProvenanceClassifier.ProvenanceClass.UNKNOWN);
    }

    @Test
    void facadeAloneWithoutOriginIsUnknown() {
        // given
        ConfigServerSettingsProvenanceClassifier classifier = classifier("configurationProperties");

        // when
        ConfigServerSettingsProvenanceClassifier.ProvenanceClass provenance = classifier.classify(POLICY_KEY);
        String sourceName = classifier.originSourceName(POLICY_KEY);

        // then
        assertThat(provenance)
                .isEqualTo(ConfigServerSettingsProvenanceClassifier.ProvenanceClass.UNKNOWN);
        assertThat(sourceName).isNull();
    }

    private static ConfigServerSettingsProvenanceClassifier classifier(String sourceName) {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(sourceName, Map.of(POLICY_KEY, "INFO")));
        return new ConfigServerSettingsProvenanceClassifier(environment);
    }
}
