package space.br1440.platform.tracing.autoconfigure.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class ConfigServerProvenanceClassifierTest {

    private static final String KEY = ConfigServerPolicyManifestBuilder.SAMPLING_RATIO;

    private final ConfigServerProvenanceClassifier classifier =
            new ConfigServerProvenanceClassifier();

    @Test
    void separatesFetchMarkerFromPerKeyRemoteProvenance() {
        StandardEnvironment environment = environmentWithRemote(Map.of(KEY, "0.5"));

        ConfigServerProvenanceClassifier.ConfigServerMarker marker =
                classifier.inspectMarker(environment);
        ConfigServerProvenanceClassifier.KeyProvenance evidence =
                classifier.inspect(environment, KEY);

        assertThat(marker.present()).isTrue();
        assertThat(marker.sourceRevision()).isEqualTo("revision-1");
        assertThat(evidence.effectiveCategory())
                .isEqualTo(PropertySourceCategory.CONFIG_SERVER_REMOTE);
        assertThat(evidence.isAuthoritativeRemote()).isTrue();
    }

    @Test
    void detectsHigherPriorityLocalOverride() {
        StandardEnvironment environment = environmentWithRemote(Map.of(KEY, "0.5"));
        environment.getPropertySources().addFirst(
                new MapPropertySource("systemProperties", Map.of(KEY, "0.9")));

        ConfigServerProvenanceClassifier.KeyProvenance evidence =
                classifier.inspect(environment, KEY);

        assertThat(evidence.effectiveCategory()).isEqualTo(PropertySourceCategory.SYSTEM);
        assertThat(evidence.localOverride()).isTrue();
        assertThat(evidence.conflict()).isTrue();
        assertThat(evidence.isAuthoritativeRemote()).isFalse();
    }

    @Test
    void rejectsNameOnlyConfigServerSpoofAsUnknown() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new MapPropertySource("configserver:spoof", Map.of(KEY, "0.5")));
        environment.getPropertySources().addFirst(configClientMarker());

        ConfigServerProvenanceClassifier.KeyProvenance evidence =
                classifier.inspect(environment, KEY);

        assertThat(evidence.effectiveCategory())
                .isEqualTo(PropertySourceCategory.UNKNOWN_AMBIGUOUS);
        assertThat(evidence.remoteSource().name()).isNull();
        assertThat(evidence.isAuthoritativeRemote()).isFalse();
    }

    @Test
    void readsRevisionDirectlyFromMarkerSource() {
        StandardEnvironment environment = environmentWithRemote(Map.of(KEY, "0.5"));
        environment.getPropertySources().addFirst(
                new MapPropertySource(
                        "commandLineArgs",
                        Map.of("config.client.version", "spoofed")));

        assertThat(classifier.inspectMarker(environment).sourceRevision())
                .isEqualTo("revision-1");
    }

    private static StandardEnvironment environmentWithRemote(Map<String, Object> values) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new OriginTrackedMapPropertySource("configserver:application.yml", values));
        environment.getPropertySources().addFirst(configClientMarker());
        return environment;
    }

    private static MapPropertySource configClientMarker() {
        return new MapPropertySource(
                ConfigServerProvenanceClassifier.CONFIG_CLIENT,
                Map.of(
                        "config.client.version", "revision-1",
                        "config.client.state", "state-1"));
    }
}
