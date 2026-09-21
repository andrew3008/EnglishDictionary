package space.br1440.platform.tracing.otel.javaagent.jmx.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class RemoteJmxRegistrationAssessmentTest {

    private static final String JMXREMOTE_PORT_NAME = "-Dcom.sun.management.jmxremote.port";
    private static final String CONFIG_FILE_NAME = "-Dcom.sun.management.config.file";
    private static final String REMEDIATION = "remove the offending startup arguments";

    @Test
    void allowed_assessment_carries_no_reason_arguments_or_remediation() {
        // given
        // when
        RemoteJmxRegistrationAssessment assessment = allowed();

        // then
        assertThat(assessment.allowsLocalMutationRegistration()).isTrue();
        assertThat(assessment.failureCode()).isEqualTo(RemoteJmxDenialReason.NONE);
        assertThat(assessment.offendingArguments()).isEmpty();
        assertThat(assessment.remediation()).isEmpty();
        assertThat(assessment.denialDescription()).isEmpty();
    }

    @Test
    void approval_paired_with_a_denial_reason_is_rejected() {
        // given
        // when
        // then
        assertThatThrownBy(() -> new RemoteJmxRegistrationAssessment(
                true, RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN, List.of(), ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void denial_paired_with_reason_none_is_rejected() {
        // given
        // when
        // then
        assertThatThrownBy(() -> new RemoteJmxRegistrationAssessment(
                false, RemoteJmxDenialReason.NONE, List.of(), ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowed_assessment_rejects_offending_arguments() {
        // given
        // when
        // then
        assertThatThrownBy(() -> new RemoteJmxRegistrationAssessment(
                true, RemoteJmxDenialReason.NONE, List.of(JMXREMOTE_PORT_NAME), ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowed_assessment_rejects_remediation() {
        // given
        // when
        // then
        assertThatThrownBy(() -> new RemoteJmxRegistrationAssessment(
                true, RemoteJmxDenialReason.NONE, List.of(), REMEDIATION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void null_components_are_rejected() {
        // given
        // when
        // then
        assertThatThrownBy(() -> new RemoteJmxRegistrationAssessment(false, null, List.of(), REMEDIATION))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new RemoteJmxRegistrationAssessment(
                false, RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN, null, REMEDIATION))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new RemoteJmxRegistrationAssessment(
                false, RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN, List.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void offending_arguments_are_copied_and_immutable() {
        // given
        List<String> source = new ArrayList<>(List.of(JMXREMOTE_PORT_NAME));
        RemoteJmxRegistrationAssessment assessment = deniedByManagementAgent(source);

        // when
        source.add(CONFIG_FILE_NAME);

        // then
        assertThat(assessment.offendingArguments()).containsExactly(JMXREMOTE_PORT_NAME);
        assertThatThrownBy(() -> assessment.offendingArguments().add(CONFIG_FILE_NAME))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void denial_description_names_reason_and_remediation_when_no_arguments_were_detected() {
        // given
        RemoteJmxRegistrationAssessment assessment = new RemoteJmxRegistrationAssessment(
                false, RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN, List.of(), REMEDIATION
        );

        // when
        String description = assessment.denialDescription();

        // then
        assertThat(description).isEqualTo("REMOTE_JMX_EVIDENCE_UNKNOWN: " + REMEDIATION);
    }

    @Test
    void denial_description_lists_detected_argument_names() {
        // given
        RemoteJmxRegistrationAssessment assessment = deniedByManagementAgent(List.of(JMXREMOTE_PORT_NAME, CONFIG_FILE_NAME));

        // when
        String description = assessment.denialDescription();

        // then
        String expectedDescription = """
                REMOTE_JMX_MANAGEMENT_AGENT_CONFIGURED: %s; detected=[%s, %s]"""
                .formatted(REMEDIATION, JMXREMOTE_PORT_NAME, CONFIG_FILE_NAME);

        assertThat(description).isEqualTo(expectedDescription);
    }

    private static RemoteJmxRegistrationAssessment allowed() {
        return new RemoteJmxRegistrationAssessment(true, RemoteJmxDenialReason.NONE, List.of(), "");
    }

    private static RemoteJmxRegistrationAssessment deniedByManagementAgent(List<String> offendingArguments) {
        return new RemoteJmxRegistrationAssessment(
                false,
                RemoteJmxDenialReason.REMOTE_JMX_MANAGEMENT_AGENT_CONFIGURED,
                offendingArguments,
                REMEDIATION
        );
    }
}
