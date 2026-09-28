package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.logging.ratelimit.MinIntervalRateLimiter;

class SpanTimeoutArchitectureGateTest {

    @Test
    void facadeDoesNotOwnTraceMapOrSpanPermitFieldsDirectly() {
        // given/when
        List<Field> facadeMaps = Arrays.stream(SpanTimeoutProcessor.class.getDeclaredFields())
                .filter(field -> Map.class.isAssignableFrom(field.getType()))
                .toList();
        // then
        assertThat(facadeMaps).as("facade must not hold the trace map directly").isEmpty();

        // given/when
        List<Field> facadePermitCounters = Arrays.stream(SpanTimeoutProcessor.class.getDeclaredFields())
                .filter(field -> AtomicInteger.class.isAssignableFrom(field.getType()))
                .toList();
        // then
        assertThat(facadePermitCounters).as("facade must not hold the span permit tracker directly").isEmpty();

        // given/when
        List<String> facadeAtomicLongs = Arrays.stream(SpanTimeoutProcessor.class.getDeclaredFields())
                .filter(field -> AtomicLong.class.isAssignableFrom(field.getType()))
                .map(Field::getName)
                .toList();
        // then
        assertThat(facadeAtomicLongs)
                .as("scan counters belong to SpanTimeoutScan, heartbeat to SpanTimeoutScanner")
                .isEmpty();
    }

    @Test
    void scanOwnsForceEndCountersAndScannerOwnsHeartbeat() {
        // given/when
        List<String> scanAtomicLongs = Arrays.stream(SpanTimeoutScan.class.getDeclaredFields())
                .filter(field -> AtomicLong.class.isAssignableFrom(field.getType()))
                .map(Field::getName)
                .toList();
        List<String> scannerAtomicLongs = Arrays.stream(SpanTimeoutScanner.class.getDeclaredFields())
                .filter(field -> AtomicLong.class.isAssignableFrom(field.getType()))
                .map(Field::getName)
                .toList();

        // then
        assertThat(scanAtomicLongs).containsExactlyInAnyOrder(
                "forcedSpanEndAttemptsTotal",
                "forcedTraceEndAttemptsTotal",
                "scanFailuresTotal",
                "forceEndFailuresTotal");
        assertThat(scannerAtomicLongs).containsExactly("lastSuccessfulScanEpochMillis");
    }

    @Test
    void logRateLimitingIsOwnedOnlyByMinIntervalRateLimiter() {
        // given/when
        List<String> rawLogClocks = Stream.concat(
                        Stream.of(SpanTimeoutScan.class, SpanTimeoutScanner.class, InFlightTraceIndex.class,
                                AdmissionRejections.class),
                        Arrays.stream(AdmissionRejections.class.getDeclaredClasses()))
                .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                .filter(field -> AtomicLong.class.isAssignableFrom(field.getType()))
                .map(Field::getName)
                .filter(name -> name.endsWith("LogNanos"))
                .toList();
        List<String> intervalGates = Stream.concat(
                        Stream.of(SpanTimeoutScan.class, SpanTimeoutScanner.class, InFlightTraceIndex.class,
                                AdmissionRejections.class),
                        Arrays.stream(AdmissionRejections.class.getDeclaredClasses()))
                .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                        .filter(field -> field.getType() == MinIntervalRateLimiter.class)
                .map(field -> field.getDeclaringClass().getSimpleName() + "." + field.getName())
                .toList();

        // then
        assertThat(rawLogClocks).as("no hand-rolled log clocks outside MinIntervalRateLimiter").isEmpty();
        assertThat(intervalGates).containsExactlyInAnyOrder(
                "SpanTimeoutScan.scanLog",
                "InFlightTraceIndex.evictionLog",
                "Slot.limiter");
    }

    @Test
    void inFlightTraceIndexIsTheSingleAuthoritativeTraceOwner() {
        // given/when
        List<Field> outerMaps = Arrays.stream(InFlightTraceIndex.class.getDeclaredFields())
                .filter(field -> ConcurrentHashMap.class.isAssignableFrom(field.getType()))
                .toList();

        // then
        assertThat(outerMaps).extracting(Field::getName).containsExactly("traces");
        assertThat(outerMaps.getFirst().getGenericType()).isInstanceOf(ParameterizedType.class);
        assertThat(outerMaps.getFirst().getGenericType().getTypeName())
                .contains("java.lang.String", "InFlightTraceState");

        // given/when
        List<String> indexAtomicLongs = Arrays.stream(InFlightTraceIndex.class.getDeclaredFields())
                .filter(field -> AtomicLong.class.isAssignableFrom(field.getType()))
                .map(Field::getName)
                .toList();
        // then
        assertThat(indexAtomicLongs)
                .as("rejection counters belong to AdmissionRejections; index keeps only the eviction counter")
                .containsExactly("trackingEvictedTotal");
    }

    @Test
    void admissionRejectionsOwnsRejectionCountersAndPlannerIsStateless() {
        // given/when
        List<Field> rejectionsMaps = Arrays.stream(AdmissionRejections.class.getDeclaredFields())
                .filter(field -> Map.class.isAssignableFrom(field.getType()))
                .toList();
        long slotCounters = Arrays.stream(AdmissionRejections.class.getDeclaredClasses())
                .filter(nested -> !nested.isEnum())
                .flatMap(nested -> Arrays.stream(nested.getDeclaredFields()))
                .filter(field -> AtomicLong.class.isAssignableFrom(field.getType()))
                .count();
        List<Field> plannerInstanceFields = Arrays.stream(EmptyEvictionPlanner.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .toList();

        // then
        assertThat(rejectionsMaps).as("rejections ledger must not see the trace map").isEmpty();
        assertThat(slotCounters).as("one AtomicLong per rejection slot").isEqualTo(1);
        assertThat(plannerInstanceFields).as("planner is a pure function over entries").isEmpty();
        assertThat(Arrays.stream(EmptyEvictionPlanner.class.getDeclaredFields())
                .noneMatch(field -> Map.class.isAssignableFrom(field.getType())))
                .isTrue();
    }

    @Test
    void inFlightTraceIndexNeverLeaksTheRawMapThroughItsPublicSurface() {
        // when/then
        for (Method method : InFlightTraceIndex.class.getDeclaredMethods()) {
            assertThat(Map.class.isAssignableFrom(method.getReturnType()))
                    .as("method %s must not return the raw trace map", method.getName())
                    .isFalse();
        }
    }

    @Test
    void admissionBudgetIsTheSingleOwnerOfTheBudgetState() {
        // given/when
        List<String> atomicIntegers = Arrays.stream(AdmissionBudget.class.getDeclaredFields())
                .filter(field -> AtomicInteger.class.isAssignableFrom(field.getType()))
                .map(Field::getName)
                .toList();
        // then
        assertThat(atomicIntegers).containsExactly("used");

        // given/when
        List<String> atomicLongs = Arrays.stream(AdmissionBudget.class.getDeclaredFields())
                .filter(field -> AtomicLong.class.isAssignableFrom(field.getType()))
                .map(Field::getName)
                .toList();
        // then
        assertThat(atomicLongs).containsExactly("invariantViolationTotal");
    }

    @Test
    void supportTypesAreNotPublic() {
        // when/then
        assertThat(Modifier.isPublic(AdmissionBudget.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(InFlightTraceState.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(InFlightTraceIndex.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(SpanTimeoutScan.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(SpanTimeoutScanner.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(AdmissionRejections.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(EmptyEvictionPlanner.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(ExpiredSpan.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(TimeoutReason.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(SpanTimeoutProcessor.class.getModifiers())).isTrue();
    }
}
