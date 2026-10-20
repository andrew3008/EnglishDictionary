package space.br1440.platform.tracing.otel.javaagent.exporter.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ObservingSenderPreferenceTest {

    private String originalHttpPreference;
    private String originalGrpcPreference;

    @BeforeEach
    void captureOriginalPreferences() {
        originalHttpPreference = System.getProperty(ObservingSenderPreference.HTTP_PREFERENCE_KEY);
        originalGrpcPreference = System.getProperty(ObservingSenderPreference.GRPC_PREFERENCE_KEY);
        System.clearProperty(ObservingSenderPreference.HTTP_PREFERENCE_KEY);
        System.clearProperty(ObservingSenderPreference.GRPC_PREFERENCE_KEY);
    }

    @AfterEach
    void restoreOriginalPreferences() {
        restore(ObservingSenderPreference.HTTP_PREFERENCE_KEY, originalHttpPreference);
        restore(ObservingSenderPreference.GRPC_PREFERENCE_KEY, originalGrpcPreference);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    @DisplayName("Без заданной preference устанавливаются наблюдающие senders")
    void installsObservingSenders() {
        // when
        ObservingSenderPreference.install();

        // then
        assertThat(System.getProperty(ObservingSenderPreference.HTTP_PREFERENCE_KEY))
                .isEqualTo(ObservingHttpSenderProvider.class.getName());
        assertThat(System.getProperty(ObservingSenderPreference.GRPC_PREFERENCE_KEY))
                .isEqualTo(ObservingGrpcSenderProvider.class.getName());
    }

    @Test
    @DisplayName("Повторная установка идемпотентна")
    void installIsIdempotent() {
        // given
        ObservingSenderPreference.install();

        // when
        ObservingSenderPreference.install();

        // then
        assertThat(System.getProperty(ObservingSenderPreference.HTTP_PREFERENCE_KEY))
                .isEqualTo(ObservingHttpSenderProvider.class.getName());
    }

    @Test
    @DisplayName("Preference на чужой provider прекращает bootstrap")
    void foreignPreferenceFailsBootstrap() {
        // given
        System.setProperty(ObservingSenderPreference.HTTP_PREFERENCE_KEY, "com.example.OtherSenderProvider");

        // when/then
        assertThatThrownBy(ObservingSenderPreference::install)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ObservingSenderPreference.HTTP_PREFERENCE_KEY)
                .hasMessageContaining("com.example.OtherSenderProvider");
        assertThat(System.getProperty(ObservingSenderPreference.GRPC_PREFERENCE_KEY)).isNull();
    }

    @Test
    @DisplayName("Чужой GRPC provider не оставляет частично установленный HTTP provider")
    void foreignGrpcPreferenceDoesNotPartiallyInstallHttpPreference() {
        // given
        System.setProperty(ObservingSenderPreference.GRPC_PREFERENCE_KEY, "com.example.OtherGrpcSenderProvider");

        // when/then
        assertThatThrownBy(ObservingSenderPreference::install)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ObservingSenderPreference.GRPC_PREFERENCE_KEY)
                .hasMessageContaining("com.example.OtherGrpcSenderProvider");
        assertThat(System.getProperty(ObservingSenderPreference.HTTP_PREFERENCE_KEY)).isNull();
    }
}
