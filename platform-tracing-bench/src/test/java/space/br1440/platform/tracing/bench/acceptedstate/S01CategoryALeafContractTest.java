package space.br1440.platform.tracing.bench.acceptedstate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S-01 corrective evidence for blockers B2 and B3: the concrete accepted-policy payload carries
 * exactly the eight Category A leaves, carries no Category B state, and declares no mutable field
 * type.
 * <p>
 * Structural rather than behavioural on purpose. The eight-leaf contract is a shape obligation, so
 * the proof reads the shape reflectively; that also means the proof keeps working when the field
 * values change.
 */
@DisplayName("S-01 corrective: accepted policy carries exactly eight Category A leaves")
class S01CategoryALeafContractTest {

    /** Gate E5 leaf order. Names are {@code PROPOSED}; the leaf set itself is binding. */
    private static final List<String> EXPECTED_CATEGORY_A_COMPONENTS = List.of(
            "samplingEnabled",
            "samplingRatio",
            "samplingRouteRatios",
            "validationEnabled",
            "validationStrict",
            "exporterEnabled",
            "propagationTraceControlHeadersEnabled",
            "diagnosticsLogLevel");

    /**
     * Category B and out-of-scope vocabulary. A component whose name contains any of these would
     * mean startup-only configuration, a secret, or pipeline composition had leaked into accepted
     * runtime state.
     */
    private static final List<String> FORBIDDEN_NAME_FRAGMENTS = List.of(
            "scrub",
            "droppath",
            "droproute",
            "forcerecord",
            "builtinrule",
            "rule",
            "pipeline",
            "processor",
            "exporterendpoint",
            "secret",
            "password",
            "token",
            "credential",
            "keystore",
            "path");

    /** Types that would defeat deep immutability if declared as an accepted-policy field type. */
    private static final List<Class<?>> FORBIDDEN_FIELD_TYPES = List.of(
            HashMap.class,
            TreeMap.class,
            LinkedHashMap.class,
            ArrayList.class,
            HashSet.class,
            AtomicReference.class,
            AtomicBoolean.class,
            StringBuilder.class);

    @Test
    @DisplayName("exactly eight Category A components, in the Gate E5 leaf order")
    void policyDeclaresExactlyEightCategoryALeaves() {
        // when
        List<String> components = Arrays.stream(S01EffectiveTracingPolicy.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        // then
        assertThat(components)
                .as("the accepted runtime envelope is a closed eight-leaf contract")
                .containsExactlyElementsOf(EXPECTED_CATEGORY_A_COMPONENTS)
                .hasSize(8);
    }

    @Test
    @DisplayName("no Category B leaf: scrubbing, drop-paths, force-record-values and secrets are absent")
    void policyDeclaresNoCategoryBLeaf() {
        // when
        List<String> offending = Arrays.stream(S01EffectiveTracingPolicy.class.getRecordComponents())
                .map(RecordComponent::getName)
                .filter(name -> containsForbiddenFragment(name.toLowerCase(Locale.ROOT)))
                .toList();

        // then
        assertThat(offending)
                .as("scrubbing is Category B startup configuration and is not accepted runtime state")
                .isEmpty();
    }

    @Test
    @DisplayName("every declared field is private final and of an immutable or unmodifiable type")
    void policyDeclaresNoMutableFieldType() {
        // when
        Field[] fields = S01EffectiveTracingPolicy.class.getDeclaredFields();

        // then
        assertThat(fields).hasSize(8);
        assertThat(fields).allSatisfy(field -> {
            assertThat(Modifier.isFinal(field.getModifiers()))
                    .as("field %s must be final", field.getName())
                    .isTrue();
            assertThat(Modifier.isPrivate(field.getModifiers()))
                    .as("field %s must be private", field.getName())
                    .isTrue();
            assertThat(field.getType().isArray())
                    .as("field %s must not be an array, which is always mutable", field.getName())
                    .isFalse();
            assertThat(FORBIDDEN_FIELD_TYPES)
                    .as("field %s must not be declared as a mutable concrete type", field.getName())
                    .doesNotContain(field.getType());
        });
    }

    @Test
    @DisplayName("seven leaves are primitives or a closed enum; the only reference leaf is the route map")
    void onlyOneLeafIsAReference() {
        // when
        List<Class<?>> types = Arrays.stream(S01EffectiveTracingPolicy.class.getRecordComponents())
                .map(RecordComponent::getType)
                .toList();

        // then
        assertThat(types).containsExactly(
                boolean.class,
                double.class,
                NavigableMap.class,
                boolean.class,
                boolean.class,
                boolean.class,
                boolean.class,
                S01LogLevel.class);
        assertThat(S01LogLevel.class.isEnum())
                .as("the log-level leaf is a closed enum, so it needs no defensive copy")
                .isTrue();
        assertThat(S01LogLevel.values())
                .as("closed vocabulary mirroring the current PlatformLogControl.LogLevel owner")
                .containsExactly(S01LogLevel.OFF, S01LogLevel.ERROR, S01LogLevel.WARN,
                        S01LogLevel.INFO, S01LogLevel.DEBUG, S01LogLevel.TRACE);
    }

    @Test
    @DisplayName("route-map key order is natural, whatever order or comparator the source used")
    void routeMapOrderIsDeterministicRegardlessOfSource() {
        // given: insertion-ordered source, and a source with a reverse comparator
        Map<String, Double> insertionOrdered = new LinkedHashMap<>();
        insertionOrdered.put("/zeta", 0.3d);
        insertionOrdered.put("/alpha", 0.1d);
        insertionOrdered.put("/mid", 0.2d);

        NavigableMap<String, Double> reverseOrdered = new TreeMap<>(Comparator.reverseOrder());
        reverseOrdered.putAll(insertionOrdered);

        // when
        S01EffectiveTracingPolicy fromInsertionOrder = policyWithRoutes(insertionOrdered);
        S01EffectiveTracingPolicy fromReverseOrder =
                new S01EffectiveTracingPolicy(true, 0.5d, reverseOrdered,
                        true, false, true, true, S01LogLevel.INFO);

        // then
        assertThat(fromInsertionOrder.samplingRouteRatios().keySet())
                .containsExactly("/alpha", "/mid", "/zeta");
        assertThat(fromReverseOrder.samplingRouteRatios().keySet())
                .as("a caller-supplied comparator must not leak into the accepted representation")
                .containsExactly("/alpha", "/mid", "/zeta");
        assertThat(fromInsertionOrder)
                .as("deterministic order makes two equal policies equal regardless of source shape")
                .isEqualTo(fromReverseOrder);
    }

    @Test
    @DisplayName("the concrete envelope payload is identity, sequence and the eight-leaf policy only")
    void envelopeCarriesNoDiagnostics() {
        // when
        List<String> components = Arrays.stream(S01AcceptedPolicyEnvelope.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        // then
        assertThat(components).containsExactly("runtimeInstanceId", "acceptedSequence", "policy");
        assertThat(S01AcceptedPolicyEnvelope.class.getRecordComponents()[2].getType())
                .as("the payload type is concrete, so an arbitrary mutable policy cannot be installed")
                .isEqualTo(S01EffectiveTracingPolicy.class);
    }

    @Test
    @DisplayName("the concrete store holds one AtomicReference and exposes only current() and commit()")
    void concreteStoreSurfaceIsMinimal() {
        // when
        Field[] fields = S01AcceptedPolicyStateStore.class.getDeclaredFields();
        List<String> methods = Arrays.stream(S01AcceptedPolicyStateStore.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .sorted()
                .toList();

        // then
        assertThat(fields).hasSize(1);
        assertThat(fields[0].getType()).isEqualTo(AtomicReference.class);
        assertThat(Modifier.isFinal(fields[0].getModifiers())).isTrue();
        assertThat(methods).containsExactly("commit", "current");
    }

    @Test
    @DisplayName("no generic type parameter remains in the recommended production surface")
    void concreteSurfaceHasNoTypeParameter() {
        // then
        assertThat(S01AcceptedPolicyStateStore.class.getTypeParameters())
                .as("an arbitrary S could be mutable, so the production contract fixes the payload type")
                .isEmpty();
        assertThat(S01AcceptedPolicyEnvelope.class.getTypeParameters()).isEmpty();
        assertThat(S01EffectiveTracingPolicy.class.getTypeParameters()).isEmpty();
    }

    private static boolean containsForbiddenFragment(String lowerCaseName) {
        for (String fragment : FORBIDDEN_NAME_FRAGMENTS) {
            if (lowerCaseName.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static S01EffectiveTracingPolicy policyWithRoutes(Map<String, Double> routes) {
        return S01EffectiveTracingPolicy.of(true, 0.5d, routes,
                true, false, true, true, S01LogLevel.INFO);
    }
}
