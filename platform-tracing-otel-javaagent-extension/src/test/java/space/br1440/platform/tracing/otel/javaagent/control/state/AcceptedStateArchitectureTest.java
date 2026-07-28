package space.br1440.platform.tracing.otel.javaagent.control.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

class AcceptedStateArchitectureTest {

    private static final List<Class<?>> INTERNAL_TYPES = List.of(
            AcceptedPolicyCoordinator.class,
            AcceptedPolicyEnvelope.class,
            AcceptedPolicyStateStore.class,
            EffectiveTracingPolicy.class,
            EffectiveTracingPolicyDigest.class,
            EffectiveTracingPolicyValidator.class,
            ExpectedPolicyBase.class,
            PolicyApplyAttempt.class,
            PolicyApplyDiagnostics.class,
            PolicyApplyOutcome.class,
            PolicyApplyResult.class,
            PolicyAuditBuffer.class,
            PolicyCommitOutcome.class,
            PolicyCommitResult.class,
            PolicyLogLevel.class,
            PolicyMessageCode.class,
            PolicyValidationConstraints.class);

    @Test
    void acceptedStateTypesDoNotCreatePublicExtensionApi() {
        assertThat(INTERNAL_TYPES)
                .allMatch(type -> !Modifier.isPublic(type.getModifiers()));
    }

    @Test
    void acceptedEnvelopeCarriesOnlyLogicalAcceptedState() {
        assertThat(AcceptedPolicyEnvelope.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("runtimeInstanceId", "acceptedSequence", "policy");
    }

    @Test
    void storeCannotAcceptGenericMapOrRebaseFunction() {
        Method commit = Arrays.stream(AcceptedPolicyStateStore.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("commit"))
                .findFirst()
                .orElseThrow();

        assertThat(commit.getParameterTypes())
                .containsExactly(ExpectedPolicyBase.class, EffectiveTracingPolicy.class)
                .doesNotContain(Map.class, UnaryOperator.class);
        assertThat(AcceptedPolicyStateStore.class.getTypeParameters()).isEmpty();
    }
}
