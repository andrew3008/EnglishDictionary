package space.br1440.platform.tracing.bench.acceptedstate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Gate E3 misuse-resistance proof: the forbidden operations are not merely discouraged, they are
 * absent from the API surface.
 * <p>
 * Each test below corresponds to one misuse case that the E3 contract has to exclude. The strongest
 * form of exclusion is that the operation cannot be expressed at all, which is what the surface
 * assertions check; where a caller could still construct a value object by hand, the test shows there
 * is no way to install it.
 */
@DisplayName("S-01: forbidden mutations are absent from the API surface")
class S01ApiMisuseTest {

    private static final String RUNTIME_INSTANCE_ID = "s01-runtime";

    @Test
    @DisplayName("the store exposes exactly two operations: current() and commit()")
    void storeSurfaceIsTwoOperations() {
        // when
        var names = Arrays.stream(S01AcceptedPolicyStore.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .sorted()
                .toList();

        // then
        assertThat(names).containsExactly("commit", "current");
    }

    @Test
    @DisplayName("no unconditional set: every mutation must present an expected base")
    void noUnconditionalSetExists() throws NoSuchMethodException {
        // when
        var mutators = Arrays.stream(S01AcceptedPolicyStore.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> method.getName().startsWith("set")
                        || method.getName().startsWith("put")
                        || method.getName().startsWith("replace")
                        || method.getName().startsWith("reset")
                        || method.getName().startsWith("update")
                        || method.getName().startsWith("getAndSet")
                        || method.getName().startsWith("force"))
                .toList();

        // then
        assertThat(mutators).isEmpty();
        assertThat(S01AcceptedPolicyStore.class.getDeclaredMethod("commit",
                S01ExpectedBase.class, Object.class))
                .as("the only mutation takes an expected base as its first parameter")
                .isNotNull();
    }

    @Test
    @DisplayName("no way to install a hand-built envelope, so the sequence cannot be forged")
    void noEnvelopeInjectionPointExists() {
        // when
        var envelopeAcceptingMethods = Arrays.stream(S01AcceptedPolicyStore.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> Arrays.asList(method.getParameterTypes())
                        .contains(S01AcceptedEnvelope.class))
                .toList();

        // then
        assertThat(envelopeAcceptingMethods)
                .as("a caller may construct an envelope but has no API to publish one")
                .isEmpty();
    }

    @Test
    @DisplayName("the accepted sequence has no mutator anywhere: the store is its only owner")
    void acceptedSequenceHasNoMutator() {
        // when
        var sequenceMutators = Arrays.stream(S01AcceptedEnvelope.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> method.getReturnType() == void.class)
                .toList();

        // then
        assertThat(sequenceMutators).isEmpty();
        assertThat(S01AcceptedEnvelope.class.isRecord()).isTrue();
        assertThat(S01AcceptedEnvelope.class.getDeclaredFields())
                .as("accepted state fields are final, so a published envelope cannot be mutated in place")
                .allSatisfy(field -> assertThat(Modifier.isFinal(field.getModifiers())).isTrue());
    }

    @Test
    @DisplayName("runtimeInstanceId cannot be bypassed: it is a mandatory expected-base component")
    void runtimeInstanceIdCannotBeBypassed() {
        // when
        var components = Arrays.stream(S01ExpectedBase.class.getRecordComponents())
                .map(component -> component.getName())
                .toList();

        // then
        assertThat(components).containsExactly("runtimeInstanceId", "acceptedSequence");
        assertThatThrownBy(() -> new S01ExpectedBase(null, 0L))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("the accepted state carries no diagnostics, audit or attempt metadata")
    void acceptedStateCarriesNoDiagnostics() {
        // when
        var components = Arrays.stream(S01AcceptedEnvelope.class.getRecordComponents())
                .map(component -> component.getName())
                .toList();

        // then
        assertThat(components)
                .as("the compare-and-set payload is identity, sequence and policy only")
                .containsExactly("runtimeInstanceId", "acceptedSequence", "policy");
    }

    @Test
    @DisplayName("accepted state is not a generic string map and needs no copy on read")
    void acceptedStateIsTyped() {
        // given
        S01AcceptedPolicyStore<S01Policy> store =
                new S01AcceptedPolicyStore<>(RUNTIME_INSTANCE_ID, S01Policy.of(2L));

        // when
        S01AcceptedEnvelope<S01Policy> first = store.current();
        S01AcceptedEnvelope<S01Policy> second = store.current();

        // then
        assertThat(first)
                .as("consecutive reads return the same instance, so there is no copy-on-read")
                .isSameAs(second);
        assertThat(first.policy()).isNotInstanceOf(Map.class);
    }

    @Test
    @DisplayName("the store holds one atomic reference, not a policy plus a separate counter")
    void storeHoldsSingleAtomicReference() {
        // when
        Field[] fields = S01AcceptedPolicyStore.class.getDeclaredFields();

        // then
        assertThat(fields).hasSize(1);
        assertThat(fields[0].getType()).isEqualTo(java.util.concurrent.atomic.AtomicReference.class);
        assertThat(Modifier.isFinal(fields[0].getModifiers())).isTrue();
    }
}
