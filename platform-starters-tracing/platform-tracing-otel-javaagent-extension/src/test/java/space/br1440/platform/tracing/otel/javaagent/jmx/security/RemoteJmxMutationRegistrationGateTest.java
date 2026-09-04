package space.br1440.platform.tracing.otel.javaagent.jmx.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

class RemoteJmxMutationRegistrationGateTest {

    @Test
    void successfulAssessmentAuthorizesMutationRegistration() {
        // given
        RemoteJmxDeploymentDeclaration declaration =
                RemoteJmxDeploymentTestFixtures.startupVerifiedRemoteProhibited();
        RemoteJmxMutationRegistrationGate gate = gate(declaration);

        // when
        // then
        assertThatCode(() -> gate.requirePermit(
                inputs(Map.of(), disabledAttachArguments(), declaration)))
                .doesNotThrowAnyException();
        assertThat(gate.evaluate(inputs(Map.of(), disabledAttachArguments(), declaration))
                .allowsLocalMutationRegistration()).isTrue();
    }

    @Test
    void deniedAssessmentRejectsMutationRegistration() {
        // given
        RemoteJmxDeploymentDeclaration declaration =
                RemoteJmxDeploymentTestFixtures.startupVerifiedUndeclared();
        RemoteJmxMutationRegistrationGate gate = gate(declaration);
        RemoteJmxExposureEvidence evidence = inputs(Map.of(), disabledAttachArguments(), declaration);

        // when
        // then
        assertThat(gate.evaluate(evidence).allowsLocalMutationRegistration()).isFalse();
        assertThatThrownBy(() -> gate.requirePermit(evidence))
                .isInstanceOf(SecurityException.class)
                .hasMessage(
                        "Remote JMX mutation registration is not authorized: "
                                + RemoteJmxDenialReason.REMOTE_JMX_DYNAMIC_OR_PROGRAMMATIC_POSSIBLE);
    }

    @Test
    void explicit_remote_prohibition_is_classified_as_absent() {
        // given
        RemoteJmxDeploymentDeclaration declaration =
                RemoteJmxDeploymentTestFixtures.startupVerifiedRemoteProhibited();
        RemoteJmxMutationRegistrationGate gate = gate(declaration);

        // when
        RemoteJmxExposureAssessment assessment = gate.evaluate(inputs(
                Map.of(),
                disabledAttachArguments(),
                declaration));

        // then
        assertThat(assessment.state()).isEqualTo(RemoteJmxExposureState.ABSENT);
        assertThat(assessment.allowsLocalMutationRegistration()).isTrue();
        assertThat(assessment.allowsRemoteObservation()).isFalse();
        assertThat(assessment.failureCode()).isEqualTo(RemoteJmxDenialReason.NONE);
        assertThat(declaration.attestationSource()).isEqualTo(
                RemoteJmxDeploymentDeclaration.AttestationSource.STARTUP_ARGUMENTS_VERIFIED);
        assertThat(assessment.stickyFailure()).isFalse();
    }

    @Test
    void missing_disable_attach_mechanism_argument_is_fail_closed() {
        // given
        RemoteJmxDeploymentDeclaration declaration =
                RemoteJmxDeploymentTestFixtures.startupVerifiedRemoteProhibited();
        RemoteJmxMutationRegistrationGate gate = gate(declaration);

        // when
        RemoteJmxExposureAssessment assessment = gate.evaluate(inputs(
                Map.of(),
                List.of(),
                declaration));

        // then
        assertThat(assessment.state())
                .isEqualTo(RemoteJmxExposureState.POSSIBLE_DYNAMIC_OR_PROGRAMMATIC);
        assertThat(assessment.failureCode())
                .isEqualTo(RemoteJmxDenialReason.REMOTE_JMX_ATTACH_MECHANISM_NOT_DISABLED);
        assertThat(assessment.evidence()).contains("attach-mechanism:unconfirmed");
        assertThat(assessment.allowsLocalMutationRegistration()).isFalse();
    }

    @Test
    void explicitly_enabled_attach_mechanism_is_fail_closed() {
        // given
        RemoteJmxDeploymentDeclaration declaration =
                RemoteJmxDeploymentTestFixtures.startupVerifiedRemoteProhibited();
        RemoteJmxMutationRegistrationGate gate = gate(declaration);

        // when
        RemoteJmxExposureAssessment assessment = gate.evaluate(inputs(
                Map.of(),
                List.of(RemoteJmxExposureEvidence.ENABLE_ATTACH_MECHANISM_ARGUMENT),
                declaration));

        // then
        assertThat(assessment.state())
                .isEqualTo(RemoteJmxExposureState.POSSIBLE_DYNAMIC_OR_PROGRAMMATIC);
        assertThat(assessment.failureCode())
                .isEqualTo(RemoteJmxDenialReason.REMOTE_JMX_ATTACH_MECHANISM_NOT_DISABLED);
        assertThat(assessment.evidence()).contains("attach-mechanism:enabled");
    }

    @Test
    void conflicting_attach_mechanism_arguments_are_unknown() {
        // given
        RemoteJmxDeploymentDeclaration declaration =
                RemoteJmxDeploymentTestFixtures.startupVerifiedRemoteProhibited();
        RemoteJmxMutationRegistrationGate gate = gate(declaration);

        // when
        RemoteJmxExposureAssessment assessment = gate.evaluate(inputs(
                Map.of(),
                List.of(
                        RemoteJmxExposureEvidence.DISABLE_ATTACH_MECHANISM_ARGUMENT,
                        RemoteJmxExposureEvidence.ENABLE_ATTACH_MECHANISM_ARGUMENT),
                declaration));

        // then
        assertThat(assessment.state()).isEqualTo(RemoteJmxExposureState.UNKNOWN);
        assertThat(assessment.failureCode())
                .isEqualTo(RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN);
        assertThat(assessment.evidence()).contains("attach-mechanism:conflicting");
    }

    @Test
    void programmatic_deployment_declaration_is_not_verified_startup_evidence() {
        // given
        RemoteJmxDeploymentDeclaration declaration = RemoteJmxDeploymentDeclaration.remoteProhibited();
        RemoteJmxMutationRegistrationGate gate = gate(declaration);

        // when
        RemoteJmxExposureAssessment assessment = gate.evaluate(inputs(
                Map.of(),
                disabledAttachArguments(),
                declaration));

        // then
        assertThat(assessment.state()).isEqualTo(RemoteJmxExposureState.UNKNOWN);
        assertThat(assessment.allowsLocalMutationRegistration()).isFalse();
        assertThat(assessment.failureCode())
                .isEqualTo(RemoteJmxDenialReason.REMOTE_JMX_DEPLOYMENT_ATTESTATION_UNVERIFIED);
        assertThat(assessment.evidence()).contains(
                "deployment-attestation:PROGRAMMATIC_DECLARATION_UNVERIFIED");
        assertThat(assessment.stickyFailure()).isTrue();
    }

    @Test
    void missing_duplicate_or_unknown_startup_declaration_argument_is_rejected() {
        // given
        List<String> complete = RemoteJmxDeploymentTestFixtures.startupArguments(
                RemoteJmxDeploymentDeclaration.Mode.REMOTE_PROHIBITED,
                false,
                false);
        List<String> missing = List.of(
                "-D" + RemoteJmxDeploymentDeclaration.MODE_PROPERTY + "=REMOTE_PROHIBITED",
                RemoteJmxExposureEvidence.DISABLE_ATTACH_MECHANISM_ARGUMENT);
        List<String> duplicate = new ArrayList<>(complete);
        duplicate.add("-D" + RemoteJmxDeploymentDeclaration.MODE_PROPERTY + "=REMOTE_PROHIBITED");
        List<String> unknown = new ArrayList<>(complete);
        unknown.add("-Dplatform.tracing.jmx.remote.unexpected=true");

        // when
        List<RemoteJmxExposureAssessment> assessments = List.of(
                RemoteJmxMutationRegistrationGate.fromStartupArguments(missing).evaluateCurrentProcess(),
                RemoteJmxMutationRegistrationGate.fromStartupArguments(duplicate).evaluateCurrentProcess(),
                RemoteJmxMutationRegistrationGate.fromStartupArguments(unknown).evaluateCurrentProcess());

        // then
        assertThat(assessments)
                .allSatisfy(assessment -> {
                    assertThat(assessment.state()).isEqualTo(RemoteJmxExposureState.UNKNOWN);
                    assertThat(assessment.failureCode())
                            .isEqualTo(RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN);
                    assertThat(assessment.allowsLocalMutationRegistration()).isFalse();
                });
    }

    @Test
    void builtin_jmxremote_is_not_treated_as_wrapped_by_platform_forwarder() {
        // given
        RemoteJmxDeploymentDeclaration declaration =
                RemoteJmxDeploymentTestFixtures.startupVerifiedPlatformConnector(true, true);
        RemoteJmxMutationRegistrationGate gate = gate(declaration);

        // when
        RemoteJmxExposureAssessment assessment = gate.evaluate(inputs(
                Map.of("com.sun.management.jmxremote.port", "9010"),
                List.of(
                        "-Dcom.sun.management.jmxremote.port=9010",
                        RemoteJmxExposureEvidence.DISABLE_ATTACH_MECHANISM_ARGUMENT),
                declaration));

        // then
        assertThat(assessment.state()).isEqualTo(RemoteJmxExposureState.CONFIGURED_AT_STARTUP);
        assertThat(assessment.allowsLocalMutationRegistration()).isFalse();
        assertThat(assessment.failureCode())
                .isEqualTo(RemoteJmxDenialReason.REMOTE_JMX_UNCERTIFIED_STARTUP_CONFIGURATION);
    }

    @Test
    void disabled_authentication_is_insecure_configuration() {
        // given
        RemoteJmxDeploymentDeclaration declaration = RemoteJmxDeploymentDeclaration.remoteProhibited();
        RemoteJmxMutationRegistrationGate gate = gate(declaration);

        // when
        RemoteJmxExposureAssessment assessment = gate.evaluate(inputs(
                Map.of(
                        "com.sun.management.jmxremote.port", "9010",
                        "com.sun.management.jmxremote.authenticate", "false"),
                List.of(),
                declaration));

        // then
        assertThat(assessment.state()).isEqualTo(RemoteJmxExposureState.INSECURE_CONFIGURATION);
        assertThat(assessment.failureCode())
                .isEqualTo(RemoteJmxDenialReason.REMOTE_JMX_INSECURE_CONFIGURATION);
        assertThat(assessment.evidence())
                .contains("insecure-property:com.sun.management.jmxremote.authenticate");
    }

    @Test
    void invalid_remote_port_is_insecure_configuration() {
        // given
        RemoteJmxDeploymentDeclaration declaration = RemoteJmxDeploymentDeclaration.remoteProhibited();
        RemoteJmxMutationRegistrationGate gate = gate(declaration);

        // when
        RemoteJmxExposureAssessment assessment = gate.evaluate(inputs(
                Map.of("com.sun.management.jmxremote.port", "70000"),
                List.of(),
                declaration));

        // then
        assertThat(assessment.state()).isEqualTo(RemoteJmxExposureState.INSECURE_CONFIGURATION);
        assertThat(assessment.allowsLocalMutationRegistration()).isFalse();
    }

    @Test
    void malformed_security_value_is_unknown_and_denied() {
        // given
        RemoteJmxDeploymentDeclaration declaration = RemoteJmxDeploymentDeclaration.remoteProhibited();
        RemoteJmxMutationRegistrationGate gate = gate(declaration);

        // when
        RemoteJmxExposureAssessment assessment = gate.evaluate(inputs(
                Map.of("com.sun.management.jmxremote.ssl", "sometimes"),
                List.of(),
                declaration));

        // then
        assertThat(assessment.state()).isEqualTo(RemoteJmxExposureState.UNKNOWN);
        assertThat(assessment.failureCode()).isEqualTo(RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN);
    }

    @Test
    void conflicting_property_and_jvm_argument_are_unknown() {
        // given
        RemoteJmxDeploymentDeclaration declaration = RemoteJmxDeploymentDeclaration.remoteProhibited();
        RemoteJmxMutationRegistrationGate gate = gate(declaration);

        // when
        RemoteJmxExposureAssessment assessment = gate.evaluate(inputs(
                Map.of("com.sun.management.jmxremote.ssl", "true"),
                List.of("-Dcom.sun.management.jmxremote.ssl=false"),
                declaration));

        // then
        assertThat(assessment.state()).isEqualTo(RemoteJmxExposureState.UNKNOWN);
        assertThat(assessment.allowsLocalMutationRegistration()).isFalse();
    }

    @Test
    void certified_platform_listener_with_forwarder_allows_remote_observation() {
        // given
        RemoteJmxDeploymentDeclaration declaration =
                RemoteJmxDeploymentTestFixtures.startupVerifiedPlatformConnector(true, true);
        RemoteJmxMutationRegistrationGate gate = gate(declaration);

        // when
        RemoteJmxExposureAssessment assessment = gate.evaluate(inputs(
                Map.of(),
                disabledAttachArguments(),
                declaration));

        // then
        assertThat(assessment.state()).isEqualTo(RemoteJmxExposureState.CONFIRMED_LISTENER);
        assertThat(assessment.allowsLocalMutationRegistration()).isTrue();
        assertThat(assessment.allowsRemoteObservation()).isTrue();
        assertThat(assessment.evidence()).contains("listener:confirmed", "forwarder:confirmed");
    }

    @Test
    void confirmed_listener_without_forwarder_is_denied() {
        // given
        RemoteJmxDeploymentDeclaration declaration =
                RemoteJmxDeploymentTestFixtures.startupVerifiedPlatformConnector(true, false);
        RemoteJmxMutationRegistrationGate gate = gate(declaration);

        // when
        RemoteJmxExposureAssessment assessment = gate.evaluate(inputs(
                Map.of(),
                disabledAttachArguments(),
                declaration));

        // then
        assertThat(assessment.state()).isEqualTo(RemoteJmxExposureState.CONFIRMED_LISTENER);
        assertThat(assessment.failureCode())
                .isEqualTo(RemoteJmxDenialReason.REMOTE_JMX_FORWARDER_NOT_CONFIRMED);
        assertThat(assessment.allowsLocalMutationRegistration()).isFalse();
    }

    @Test
    void external_confirmed_listener_is_not_certified() {
        // given
        RemoteJmxDeploymentDeclaration declaration =
                RemoteJmxDeploymentTestFixtures.startupVerifiedExternalConnector(true);
        RemoteJmxMutationRegistrationGate gate = gate(declaration);

        // when
        RemoteJmxExposureAssessment assessment = gate.evaluate(inputs(
                Map.of(),
                disabledAttachArguments(),
                declaration));

        // then
        assertThat(assessment.state()).isEqualTo(RemoteJmxExposureState.CONFIRMED_LISTENER);
        assertThat(assessment.failureCode())
                .isEqualTo(RemoteJmxDenialReason.REMOTE_JMX_CONNECTOR_NOT_PLATFORM_OWNED);
        assertThat(assessment.allowsRemoteObservation()).isFalse();
    }

    @Test
    void incomplete_capture_is_unknown() {
        // given
        RemoteJmxDeploymentDeclaration declaration = RemoteJmxDeploymentDeclaration.remoteProhibited();
        RemoteJmxMutationRegistrationGate gate = gate(declaration);
        RemoteJmxExposureEvidence incomplete = RemoteJmxExposureEvidence.incomplete(
                Map.of(),
                List.of(),
                declaration,
                EvidenceCaptureOutcome.JVM_ARGUMENTS_UNAVAILABLE);

        // when
        RemoteJmxExposureAssessment assessment = gate.evaluate(incomplete);

        // then
        assertThat(assessment.state()).isEqualTo(RemoteJmxExposureState.UNKNOWN);
        assertThat(assessment.evidence()).contains("capture-failure:JVM_ARGUMENTS_UNAVAILABLE");
    }

    @Test
    void first_failure_is_sticky_for_policy_instance() {
        // given
        RemoteJmxDeploymentDeclaration declaration =
                RemoteJmxDeploymentTestFixtures.startupVerifiedUndeclared();
        RemoteJmxMutationRegistrationGate gate = gate(declaration);
        RemoteJmxExposureAssessment first = gate.evaluate(inputs(
                Map.of(),
                disabledAttachArguments(),
                declaration));

        // when
        RemoteJmxExposureAssessment second = gate.evaluate(inputs(
                Map.of(),
                disabledAttachArguments(),
                declaration));

        // then
        assertThat(second).isSameAs(first);
        assertThat(second.state()).isEqualTo(RemoteJmxExposureState.POSSIBLE_DYNAMIC_OR_PROGRAMMATIC);
        assertThat(second.stickyFailure()).isTrue();
    }

    @Test
    void later_remote_exposure_turns_previous_allow_into_sticky_failure() {
        // given
        RemoteJmxDeploymentDeclaration declaration =
                RemoteJmxDeploymentTestFixtures.startupVerifiedRemoteProhibited();
        RemoteJmxMutationRegistrationGate gate = gate(declaration);
        RemoteJmxExposureAssessment initial = gate.evaluate(inputs(
                Map.of(),
                disabledAttachArguments(),
                declaration));

        // when
        RemoteJmxExposureAssessment detected = gate.evaluate(inputs(
                Map.of("com.sun.management.jmxremote.port", "9010"),
                List.of(
                        "-Dcom.sun.management.jmxremote.port=9010",
                        RemoteJmxExposureEvidence.DISABLE_ATTACH_MECHANISM_ARGUMENT),
                declaration));
        RemoteJmxExposureAssessment repeated = gate.evaluate(inputs(
                Map.of(),
                disabledAttachArguments(),
                declaration));

        // then
        assertThat(initial.allowsLocalMutationRegistration()).isTrue();
        assertThat(detected.state()).isEqualTo(RemoteJmxExposureState.CONFIGURED_AT_STARTUP);
        assertThat(detected.stickyFailure()).isTrue();
        assertThat(repeated).isSameAs(detected);
    }

    @Test
    void missing_deployment_mode_from_config_properties_is_undeclared() {
        // given
        ConfigProperties config = config(Map.of());

        // when
        RemoteJmxDeploymentDeclaration declaration = RemoteJmxDeploymentDeclaration.from(config);

        // then
        assertThat(declaration.mode()).isEqualTo(RemoteJmxDeploymentDeclaration.Mode.UNDECLARED);
        assertThat(declaration.attestationSource())
                .isEqualTo(RemoteJmxDeploymentDeclaration.AttestationSource.UNAVAILABLE);
        RemoteJmxExposureAssessment assessment = gate(declaration).evaluate(inputs(
                Map.of(),
                disabledAttachArguments(),
                declaration));
        assertThat(assessment.allowsLocalMutationRegistration()).isFalse();
        assertThat(assessment.failureCode())
                .isEqualTo(RemoteJmxDenialReason.REMOTE_JMX_DEPLOYMENT_ATTESTATION_UNVERIFIED);
    }

    @Test
    void config_properties_parser_never_upgrades_connector_declaration_to_attestation() {
        // given
        ConfigProperties config = config(Map.of(
                RemoteJmxDeploymentDeclaration.MODE_PROPERTY,
                "PLATFORM_OWNED_CERTIFIED_CONNECTOR",
                RemoteJmxDeploymentDeclaration.LISTENER_CONFIRMED_PROPERTY,
                "true",
                RemoteJmxDeploymentDeclaration.AUTHORIZATION_FORWARDER_INSTALLED_PROPERTY,
                "true"));

        // when
        RemoteJmxDeploymentDeclaration declaration = RemoteJmxDeploymentDeclaration.from(config);

        // then
        assertThat(declaration.mode())
                .isEqualTo(RemoteJmxDeploymentDeclaration.Mode.PLATFORM_OWNED_CERTIFIED_CONNECTOR);
        assertThat(declaration.listenerConfirmed()).isTrue();
        assertThat(declaration.authorizationForwarderInstalled()).isTrue();
        assertThat(declaration.attestationSource()).isEqualTo(
                RemoteJmxDeploymentDeclaration.AttestationSource.CONFIG_PROPERTIES_UNVERIFIED);
        RemoteJmxExposureAssessment assessment = gate(declaration).evaluate(inputs(
                Map.of(),
                disabledAttachArguments(),
                declaration));
        assertThat(assessment.failureCode())
                .isEqualTo(RemoteJmxDenialReason.REMOTE_JMX_DEPLOYMENT_ATTESTATION_UNVERIFIED);
    }

    @Test
    void config_properties_parser_maps_all_non_connector_modes_exactly() {
        // given
        ConfigProperties prohibited = config(Map.of(
                RemoteJmxDeploymentDeclaration.MODE_PROPERTY,
                "REMOTE_PROHIBITED"));
        ConfigProperties external = config(Map.of(
                RemoteJmxDeploymentDeclaration.MODE_PROPERTY,
                "EXTERNAL_OR_BUILT_IN_CONNECTOR",
                RemoteJmxDeploymentDeclaration.LISTENER_CONFIRMED_PROPERTY,
                "true"));
        ConfigProperties undeclared = config(Map.of(
                RemoteJmxDeploymentDeclaration.MODE_PROPERTY,
                "UNDECLARED"));
        ConfigProperties unknown = config(Map.of(
                RemoteJmxDeploymentDeclaration.MODE_PROPERTY,
                "UNKNOWN"));

        // when
        List<RemoteJmxDeploymentDeclaration.Mode> modes = List.of(
                RemoteJmxDeploymentDeclaration.from(prohibited).mode(),
                RemoteJmxDeploymentDeclaration.from(external).mode(),
                RemoteJmxDeploymentDeclaration.from(undeclared).mode(),
                RemoteJmxDeploymentDeclaration.from(unknown).mode());

        // then
        assertThat(modes).containsExactly(
                RemoteJmxDeploymentDeclaration.Mode.REMOTE_PROHIBITED,
                RemoteJmxDeploymentDeclaration.Mode.EXTERNAL_OR_BUILT_IN_CONNECTOR,
                RemoteJmxDeploymentDeclaration.Mode.UNDECLARED,
                RemoteJmxDeploymentDeclaration.Mode.UNKNOWN);
    }

    @Test
    void malformed_or_contradictory_deployment_properties_are_unknown() {
        // given
        ConfigProperties malformedMode = config(Map.of(
                RemoteJmxDeploymentDeclaration.MODE_PROPERTY,
                "remote_prohibited"));
        ConfigProperties malformedBoolean = config(Map.of(
                RemoteJmxDeploymentDeclaration.MODE_PROPERTY,
                "PLATFORM_OWNED_CERTIFIED_CONNECTOR",
                RemoteJmxDeploymentDeclaration.LISTENER_CONFIRMED_PROPERTY,
                "yes"));
        ConfigProperties contradiction = config(Map.of(
                RemoteJmxDeploymentDeclaration.MODE_PROPERTY,
                "REMOTE_PROHIBITED",
                RemoteJmxDeploymentDeclaration.LISTENER_CONFIRMED_PROPERTY,
                "true"));

        // when
        List<RemoteJmxDeploymentDeclaration.Mode> modes = List.of(
                RemoteJmxDeploymentDeclaration.from(malformedMode).mode(),
                RemoteJmxDeploymentDeclaration.from(malformedBoolean).mode(),
                RemoteJmxDeploymentDeclaration.from(contradiction).mode());

        // then
        assertThat(modes).containsOnly(RemoteJmxDeploymentDeclaration.Mode.UNKNOWN);
    }

    @Test
    void inputs_make_defensive_copies() {
        // given
        Map<String, String> properties = new LinkedHashMap<>();
        List<String> arguments = new ArrayList<>();
        RemoteJmxExposureEvidence inputs = RemoteJmxExposureEvidence.of(
                properties,
                arguments,
                RemoteJmxDeploymentDeclaration.remoteProhibited());

        // when
        properties.put("com.sun.management.jmxremote.port", "9010");
        arguments.add("-Dcom.sun.management.jmxremote.port=9010");

        // then
        assertThat(inputs.systemProperties()).isEmpty();
        assertThat(inputs.jvmArguments()).isEmpty();
        assertThat(inputs.captureOutcome()).isEqualTo(EvidenceCaptureOutcome.COMPLETE);
    }

    @Test
    void incompleteCaptureRejectsCompleteOutcomeAndPublishesBoundedMachineCodes() {
        // given
        RemoteJmxDeploymentDeclaration declaration =
                RemoteJmxDeploymentDeclaration.remoteProhibited();

        // when
        // then
        assertThatThrownBy(() -> RemoteJmxExposureEvidence.incomplete(
                Map.of(), List.of(), declaration, EvidenceCaptureOutcome.COMPLETE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Incomplete capture must not report COMPLETE");
        assertThat(EvidenceCaptureOutcome.values())
                .allSatisfy(outcome -> assertThat(outcome.name()).matches("[A-Z][A-Z0-9_]{0,127}"));
    }

    @Test
    void current_process_without_attestation_is_always_fail_closed() {
        // given
        RemoteJmxMutationRegistrationGate gate = new RemoteJmxMutationRegistrationGate();

        // when
        RemoteJmxExposureAssessment assessment = gate.evaluateCurrentProcess();

        // then
        assertThat(assessment.allowsLocalMutationRegistration()).isFalse();
        assertThat(assessment.failureCode()).isNotEqualTo(RemoteJmxDenialReason.NONE);
    }

    private static RemoteJmxMutationRegistrationGate gate(RemoteJmxDeploymentDeclaration declaration) {
        return new RemoteJmxMutationRegistrationGate(declaration);
    }

    private static RemoteJmxExposureEvidence inputs(
            Map<String, String> properties,
            List<String> arguments,
            RemoteJmxDeploymentDeclaration declaration) {
        return RemoteJmxExposureEvidence.of(properties, arguments, declaration);
    }

    private static List<String> disabledAttachArguments() {
        return List.of(RemoteJmxExposureEvidence.DISABLE_ATTACH_MECHANISM_ARGUMENT);
    }

    private static ConfigProperties config(Map<String, String> values) {
        ConfigProperties config = mock(ConfigProperties.class);
        when(config.getString(anyString()))
                .thenAnswer(invocation -> values.get((String) invocation.getArgument(0)));
        return config;
    }
}
