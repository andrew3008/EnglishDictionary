package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.ExpectedSettingsRevision;

final class ApplySnapshotRequestParametersTest {

    private static final ExpectedSettingsRevision EXPECTED_BASE =
            new ExpectedSettingsRevision(UUID.randomUUID().toString(), 1L);
    private static final CompleteTracingSettings POLICY = CompleteTracingSettings.helmBootstrapDefaults();

    @Test
    void fullyPopulatedBuilderExposesAllFieldsThroughAccessors() {
        // given
        String attemptId = UUID.randomUUID().toString();

        // when
        ApplySnapshotRequestParameters parameters = ApplySnapshotRequestParameters.builder()
                .expectedBase(EXPECTED_BASE)
                .attemptId(attemptId)
                .sourceAuthority("CONFIG_SERVER")
                .applyTrigger("REFRESH")
                .sourceRevision("rev-1")
                .policy(POLICY)
                .build();

        // then
        assertThat(parameters.expectedBase()).isSameAs(EXPECTED_BASE);
        assertThat(parameters.attemptId()).isEqualTo(attemptId);
        assertThat(parameters.sourceAuthority()).isEqualTo("CONFIG_SERVER");
        assertThat(parameters.applyTrigger()).isEqualTo("REFRESH");
        assertThat(parameters.sourceRevision()).isEqualTo("rev-1");
        assertThat(parameters.policy()).isSameAs(POLICY);
    }

    @Test
    void blankSourceRevisionIsAllowed() {
        // given
        // when
        ApplySnapshotRequestParameters parameters = ApplySnapshotRequestParameters.builder()
                .expectedBase(EXPECTED_BASE)
                .attemptId(UUID.randomUUID().toString())
                .sourceAuthority("CONFIG_SERVER")
                .applyTrigger("REFRESH")
                .sourceRevision("")
                .policy(POLICY)
                .build();

        // then
        assertThat(parameters.sourceRevision()).isEmpty();
    }

    @Test
    void defaultSourceRevisionIsEmptyWhenNotSet() {
        // given
        // when
        ApplySnapshotRequestParameters parameters = ApplySnapshotRequestParameters.builder()
                .expectedBase(EXPECTED_BASE)
                .attemptId(UUID.randomUUID().toString())
                .sourceAuthority("CONFIG_SERVER")
                .applyTrigger("REFRESH")
                .policy(POLICY)
                .build();

        // then
        assertThat(parameters.sourceRevision()).isEmpty();
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
                        .sourceAuthority("CONFIG_SERVER")
                        .applyTrigger("REFRESH")
                        .policy(POLICY)
                        .build())
                .withMessageContaining("attemptId");
    }

    @Test
    void blankSourceAuthorityIsRejected() {
        // given
        // when
        // then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ApplySnapshotRequestParameters.builder()
                        .expectedBase(EXPECTED_BASE)
                        .attemptId(UUID.randomUUID().toString())
                        .sourceAuthority("")
                        .applyTrigger("REFRESH")
                        .policy(POLICY)
                        .build())
                .withMessageContaining("sourceAuthority");
    }

    @Test
    void blankApplyTriggerIsRejected() {
        // given
        // when
        // then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ApplySnapshotRequestParameters.builder()
                        .expectedBase(EXPECTED_BASE)
                        .attemptId(UUID.randomUUID().toString())
                        .sourceAuthority("CONFIG_SERVER")
                        .applyTrigger(null)
                        .policy(POLICY)
                        .build())
                .withMessageContaining("applyTrigger");
    }

    @Test
    void nullExpectedBaseIsRejected() {
        // given
        // when
        // then
        assertThatNullPointerException()
                .isThrownBy(() -> ApplySnapshotRequestParameters.builder()
                        .attemptId(UUID.randomUUID().toString())
                        .sourceAuthority("CONFIG_SERVER")
                        .applyTrigger("REFRESH")
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
                        .sourceAuthority("CONFIG_SERVER")
                        .applyTrigger("REFRESH")
                        .build())
                .withMessageContaining("policy");
    }

    @Test
    void nullSourceRevisionIsRejected() {
        // given
        // when
        // then
        assertThatNullPointerException()
                .isThrownBy(() -> ApplySnapshotRequestParameters.builder()
                        .expectedBase(EXPECTED_BASE)
                        .attemptId(UUID.randomUUID().toString())
                        .sourceAuthority("CONFIG_SERVER")
                        .applyTrigger("REFRESH")
                        .sourceRevision(null)
                        .policy(POLICY)
                        .build())
                .withMessageContaining("sourceRevision");
    }
}
