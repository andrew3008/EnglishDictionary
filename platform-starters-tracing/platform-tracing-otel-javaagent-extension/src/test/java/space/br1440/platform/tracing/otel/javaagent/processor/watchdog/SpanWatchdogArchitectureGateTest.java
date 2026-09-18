package space.br1440.platform.tracing.otel.javaagent.processor.watchdog;

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

import org.junit.jupiter.api.Test;

class SpanWatchdogArchitectureGateTest {

    @Test
    void facadeDoesNotOwnTraceMapOrSpanPermitFieldsDirectly() {
        // given/when
        List<Field> facadeMaps = Arrays.stream(SpanWatchdogProcessor.class.getDeclaredFields())
                .filter(field -> Map.class.isAssignableFrom(field.getType()))
                .toList();
        // then
        assertThat(facadeMaps).as("facade must not hold the trace map directly").isEmpty();

        // given/when
        List<Field> facadePermitCounters = Arrays.stream(SpanWatchdogProcessor.class.getDeclaredFields())
                .filter(field -> AtomicInteger.class.isAssignableFrom(field.getType()))
                .toList();
        // then
        assertThat(facadePermitCounters).as("facade must not hold the span permit tracker directly").isEmpty();

        // given/when
        List<String> facadeAtomicLongs = Arrays.stream(SpanWatchdogProcessor.class.getDeclaredFields())
                .filter(field -> AtomicLong.class.isAssignableFrom(field.getType()))
                .map(Field::getName)
                .toList();
        // then
        assertThat(facadeAtomicLongs)
                .as("facade keeps only its own OTel-side-effect counters")
                .containsExactlyInAnyOrder("forcedSpanCloses", "forcedTraceCloses");
    }

    @Test
    void watchdogTraceIndexIsTheSingleAuthoritativeTraceOwner() {
        // given/when
        List<Field> outerMaps = Arrays.stream(WatchdogTraceIndex.class.getDeclaredFields())
                .filter(field -> ConcurrentHashMap.class.isAssignableFrom(field.getType()))
                .toList();

        // then
        assertThat(outerMaps).extracting(Field::getName).containsExactly("traces");
        assertThat(outerMaps.getFirst().getGenericType()).isInstanceOf(ParameterizedType.class);
        assertThat(outerMaps.getFirst().getGenericType().getTypeName())
                .contains("java.lang.String", "TraceWatchdogState");
    }

    @Test
    void watchdogTraceIndexNeverLeaksTheRawMapThroughItsPublicSurface() {
        // when/then
        for (Method method : WatchdogTraceIndex.class.getDeclaredMethods()) {
            assertThat(Map.class.isAssignableFrom(method.getReturnType()))
                    .as("method %s must not return the raw trace map", method.getName())
                    .isFalse();
        }
    }

    @Test
    void spanPermitTrackerIsTheSingleOwnerOfThePermitState() {
        // given/when
        List<String> atomicIntegers = Arrays.stream(SpanPermitTracker.class.getDeclaredFields())
                .filter(field -> AtomicInteger.class.isAssignableFrom(field.getType()))
                .map(Field::getName)
                .toList();
        // then
        assertThat(atomicIntegers).containsExactly("acquiredPermitCount");

        // given/when
        List<String> atomicLongs = Arrays.stream(SpanPermitTracker.class.getDeclaredFields())
                .filter(field -> AtomicLong.class.isAssignableFrom(field.getType()))
                .map(Field::getName)
                .toList();
        // then
        assertThat(atomicLongs).containsExactly("invariantViolationTotal");
    }

    @Test
    void supportTypesAreNotPublic() {
        // when/then
        assertThat(Modifier.isPublic(SpanPermitTracker.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(TraceWatchdogState.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(WatchdogTraceIndex.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(SpanWatchdogProcessor.class.getModifiers())).isTrue();
    }
}
