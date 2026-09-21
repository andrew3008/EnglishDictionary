package space.br1440.platform.tracing.otel.javaagent.jmx.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RemoteJmxMutationRegistrationAssessorTest {

    private static final String JMXREMOTE_PORT_NAME = "-Dcom.sun.management.jmxremote.port";
    private static final String JMXREMOTE_PORT_ARGUMENT = JMXREMOTE_PORT_NAME + "=9010";
    private static final String CONFIG_FILE_NAME = "-Dcom.sun.management.config.file";

    @Test
    void empty_startup_arguments_allow_mutation_registration() {
        // given
        RemoteJmxMutationRegistrationAssessor assessor = RemoteJmxAssessorTestFixtures.allowingAssessor();

        // when
        RemoteJmxRegistrationAssessment assessment = assessor.assess();

        // then
        assertAllowed(assessment);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            -Dcom.sun.management.jmxremote                              | -Dcom.sun.management.jmxremote
            -Dcom.sun.management.jmxremote=true                         | -Dcom.sun.management.jmxremote
            -Dcom.sun.management.jmxremote=false                        | -Dcom.sun.management.jmxremote
            -Dcom.sun.management.jmxremote=                             | -Dcom.sun.management.jmxremote
            -Dcom.sun.management.jmxremote.port=9010                    | -Dcom.sun.management.jmxremote.port
            -Dcom.sun.management.jmxremote.authenticate=false           | -Dcom.sun.management.jmxremote.authenticate
            -Dcom.sun.management.config.file=/etc/management.properties | -Dcom.sun.management.config.file
            -Dcom.sun.management.agent.class=Foo                        | -Dcom.sun.management.agent.class
            -Dcom.sun.management.jmxremoteOther=1                       | -Dcom.sun.management.jmxremoteOther
            -Dcom.sun.managementX=1                                     | -Dcom.sun.managementX
            -XX:+ManagementServer                                       | -XX:+ManagementServer
            """)
    void management_agent_activation_argument_denies_registration_and_is_reported_by_name(String argument,
                                                                                          String reportedName) {
        // given
        RemoteJmxMutationRegistrationAssessor assessor = RemoteJmxAssessorTestFixtures.assessor(List.of(argument));

        // when
        RemoteJmxRegistrationAssessment assessment = assessor.assess();

        // then
        assertDeniedByManagementAgent(assessment);
        assertThat(assessment.offendingArguments()).containsExactly(reportedName);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "-Dcom.sun.manage=1",
            "-Dcom.sun.jmx.remote.x=1",
            "-Dcom.sun.managemen=1",
            "-XX:-ManagementServer"
    })
    void arguments_outside_the_management_namespace_allow_mutation_registration(String argument) {
        // given
        RemoteJmxMutationRegistrationAssessor assessor = RemoteJmxAssessorTestFixtures.assessor(List.of(argument));

        // when
        RemoteJmxRegistrationAssessment assessment = assessor.assess();

        // then
        assertAllowed(assessment);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"-XX:+DisableAttachMechanism", "-XX:-DisableAttachMechanism"})
    void attach_mechanism_flags_are_outside_the_assessment_scope(String attachFlag) {
        // given
        RemoteJmxMutationRegistrationAssessor assessor = RemoteJmxAssessorTestFixtures.assessor(List.of(attachFlag));

        // when
        RemoteJmxRegistrationAssessment assessment = assessor.assess();

        // then
        assertAllowed(assessment);
    }

    @Test
    void detected_argument_names_are_reported_without_their_values() {
        // given
        RemoteJmxMutationRegistrationAssessor assessor = RemoteJmxAssessorTestFixtures.assessor(List.of(
                JMXREMOTE_PORT_ARGUMENT,
                CONFIG_FILE_NAME + "=/etc/secret/jmx.access"
        ));

        // when
        RemoteJmxRegistrationAssessment assessment = assessor.assess();

        // then
        assertThat(assessment.offendingArguments()).containsExactly(JMXREMOTE_PORT_NAME, CONFIG_FILE_NAME);
        assertThat(assessment.offendingArguments())
                .noneMatch(argument -> argument.contains("9010") || argument.contains("jmx.access"));
    }

    @Test
    void repeated_management_argument_names_are_reported_once_in_startup_order() {
        // given
        RemoteJmxMutationRegistrationAssessor assessor = RemoteJmxAssessorTestFixtures.assessor(List.of(
                JMXREMOTE_PORT_ARGUMENT,
                CONFIG_FILE_NAME + "=/etc/management.properties",
                JMXREMOTE_PORT_NAME + "=9011"
        ));

        // when
        RemoteJmxRegistrationAssessment assessment = assessor.assess();

        // then
        assertThat(assessment.offendingArguments()).containsExactly(JMXREMOTE_PORT_NAME, CONFIG_FILE_NAME);
    }

    @Test
    void null_startup_argument_entries_are_ignored() {
        // given
        List<String> arguments = Arrays.asList(null, JMXREMOTE_PORT_ARGUMENT, null);
        RemoteJmxMutationRegistrationAssessor assessor = new RemoteJmxMutationRegistrationAssessor(() -> arguments);

        // when
        RemoteJmxRegistrationAssessment assessment = assessor.assess();

        // then
        assertDeniedByManagementAgent(assessment);
        assertThat(assessment.offendingArguments()).containsExactly(JMXREMOTE_PORT_NAME);
    }

    @Test
    void failing_startup_argument_capture_is_evidence_unknown_without_naming_arguments() {
        // given
        RemoteJmxMutationRegistrationAssessor assessor = RemoteJmxAssessorTestFixtures.captureUnavailableAssessor();

        // when
        RemoteJmxRegistrationAssessment assessment = assessor.assess();

        // then
        assertEvidenceUnknown(assessment);
    }

    @Test
    void null_startup_argument_capture_is_evidence_unknown() {
        // given
        RemoteJmxMutationRegistrationAssessor assessor = new RemoteJmxMutationRegistrationAssessor(() -> null);

        // when
        RemoteJmxRegistrationAssessment assessment = assessor.assess();

        // then
        assertEvidenceUnknown(assessment);
    }

    @Test
    void supplier_error_propagates_instead_of_being_classified() {
        // given
        RemoteJmxMutationRegistrationAssessor assessor = RemoteJmxAssessorTestFixtures.explodingAssessor();

        // when
        // then
        assertThatThrownBy(assessor::assess)
                .isInstanceOf(Error.class)
                .hasMessage(RemoteJmxAssessorTestFixtures.EXPLODING_SUPPLIER_MESSAGE);
    }

    @Test
    void null_startup_argument_supplier_is_rejected_at_construction() {
        // given
        // when
        // then
        assertThatThrownBy(() -> new RemoteJmxMutationRegistrationAssessor(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void every_assessment_recaptures_startup_arguments_and_stays_deterministic() {
        // given
        AtomicInteger captures = new AtomicInteger();
        RemoteJmxMutationRegistrationAssessor assessor = new RemoteJmxMutationRegistrationAssessor(() -> {
            captures.incrementAndGet();
            return List.of();
        });

        // when
        RemoteJmxRegistrationAssessment first = assessor.assess();
        RemoteJmxRegistrationAssessment second = assessor.assess();

        // then
        assertThat(captures).hasValue(2);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void assessment_is_a_snapshot_of_the_arguments_captured_at_assess_time() {
        // given
        List<String> arguments = new ArrayList<>(List.of(JMXREMOTE_PORT_ARGUMENT));
        RemoteJmxMutationRegistrationAssessor assessor = new RemoteJmxMutationRegistrationAssessor(() -> arguments);
        RemoteJmxRegistrationAssessment assessment = assessor.assess();

        // when
        arguments.clear();

        // then
        assertDeniedByManagementAgent(assessment);
        assertThat(assessment.offendingArguments()).containsExactly(JMXREMOTE_PORT_NAME);
    }

    private static void assertAllowed(RemoteJmxRegistrationAssessment assessment) {
        assertThat(assessment.allowsLocalMutationRegistration()).isTrue();
        assertThat(assessment.failureCode()).isEqualTo(RemoteJmxDenialReason.NONE);
        assertThat(assessment.offendingArguments()).isEmpty();
        assertThat(assessment.remediation()).isEmpty();
    }

    private static void assertDeniedByManagementAgent(RemoteJmxRegistrationAssessment assessment) {
        assertThat(assessment.allowsLocalMutationRegistration()).isFalse();
        assertThat(assessment.failureCode()).isEqualTo(RemoteJmxDenialReason.REMOTE_JMX_MANAGEMENT_AGENT_CONFIGURED);
        assertThat(assessment.remediation()).isEqualTo(RemoteJmxMutationRegistrationAssessor.REMEDIATION_MANAGEMENT_AGENT);
    }

    private static void assertEvidenceUnknown(RemoteJmxRegistrationAssessment assessment) {
        assertThat(assessment.allowsLocalMutationRegistration()).isFalse();
        assertThat(assessment.failureCode()).isEqualTo(RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN);
        assertThat(assessment.offendingArguments()).isEmpty();
        assertThat(assessment.remediation()).isEqualTo(RemoteJmxMutationRegistrationAssessor.REMEDIATION_CAPTURE_UNAVAILABLE);
    }
}
