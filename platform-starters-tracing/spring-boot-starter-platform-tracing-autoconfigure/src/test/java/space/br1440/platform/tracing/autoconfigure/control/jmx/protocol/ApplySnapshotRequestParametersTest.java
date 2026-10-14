package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsRevision;

final class ApplySnapshotRequestParametersTest {

    private static final SettingsRevision EXPECTED_BASE = new SettingsRevision(UUID.randomUUID().toString(), 1L);
    private static final CompleteTracingSettings POLICY = CompleteTracingSettings.helmBootstrapDefaults();
    private static final String WRITER = "CONFIG_SERVER";

    @Test
    void fullyPopulatedBuilderExposesAllFieldsThroughAccessors() {
        // given
        String attemptId = UUID.randomUUID().toString();

        // when
        ApplySnapshotRequestParameters parameters = ApplySnapshotRequestParameters.builder()
                .expectedBase(EXPECTED_BASE)
                .attemptId(attemptId)
                .writer(WRITER)
                .policy(POLICY)
                .build();

        // then
        assertThat(parameters.expectedBase()).isSameAs(EXPECTED_BASE);
        assertThat(parameters.attemptId()).isEqualTo(attemptId);
        assertThat(parameters.writer()).isEqualTo(WRITER);
        assertThat(parameters.policy()).isSameAs(POLICY);
    }

    @Test
    void blankAttemptIdIsRejected() {
        // given
        // when
        // then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ApplySnapshotRequestParameters.builder()
                        .expectedBase(EXPECTED_BASE)
                        .attemptId(" ")
                        .writer(WRITER)
                        .policy(POLICY)
                        .build())
                .withMessageContaining("attemptId");
    }

    @Test
    void blankWriterIsRejected() {
        // given
        // when
        // then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ApplySnapshotRequestParameters.builder()
                        .expectedBase(EXPECTED_BASE)
                        .attemptId(UUID.randomUUID().toString())
                        .writer("")
                        .policy(POLICY)
                        .build())
                .withMessageContaining("writer");
    }

    @Test
    void nullExpectedBaseIsRejected() {
        // given
        // when
        // then
        assertThatNullPointerException()
                .isThrownBy(() -> ApplySnapshotRequestParameters.builder()
                        .attemptId(UUID.randomUUID().toString())
                        .writer(WRITER)
                        .policy(POLICY)
                        .build())
                .withMessageContaining("expectedBase");
    }

    @Test
    void nullPolicyIsRejected() {
        // given
        // when
        // then
        assertThatNullPointerException()
                .isThrownBy(() -> ApplySnapshotRequestParameters.builder()
                        .expectedBase(EXPECTED_BASE)
                        .attemptId(UUID.randomUUID().toString())
                        .writer(WRITER)
                        .build())
                .withMessageContaining("policy");
    }
}
