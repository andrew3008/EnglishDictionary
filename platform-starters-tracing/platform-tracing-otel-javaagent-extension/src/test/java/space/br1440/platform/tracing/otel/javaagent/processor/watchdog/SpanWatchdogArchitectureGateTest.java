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

/**
 * Гейты, зафиксированные ADR 04 и планом декомпозиции {@code processor.watchdog}: единственный
 * владелец карты трасс, единственный владелец CAS-бюджета span'ов, отсутствие утечки
 * {@link ConcurrentHashMap} наружу индекса.
 */
class SpanWatchdogArchitectureGateTest {

    @Test
    void facadeDoesNotOwnTraceMapOrLedgerFieldsDirectly() {
        List<Field> facadeMaps = Arrays.stream(SpanWatchdogProcessor.class.getDeclaredFields())
                .filter(field -> Map.class.isAssignableFrom(field.getType()))
                .toList();
        assertThat(facadeMaps).as("facade must not hold the trace map directly").isEmpty();

        List<Field> facadeLedgerCounters = Arrays.stream(SpanWatchdogProcessor.class.getDeclaredFields())
                .filter(field -> AtomicInteger.class.isAssignableFrom(field.getType()))
                .toList();
        assertThat(facadeLedgerCounters).as("facade must not hold the permit ledger directly").isEmpty();

        List<String> facadeAtomicLongs = Arrays.stream(SpanWatchdogProcessor.class.getDeclaredFields())
                .filter(field -> AtomicLong.class.isAssignableFrom(field.getType()))
                .map(Field::getName)
                .toList();
        assertThat(facadeAtomicLongs)
                .as("facade keeps only its own OTel-side-effect counters")
                .containsExactlyInAnyOrder("forcedSpanCloses", "forcedTraceCloses");
    }

    @Test
    void watchdogTraceIndexIsTheSingleAuthoritativeTraceOwner() {
        List<Field> outerMaps = Arrays.stream(WatchdogTraceIndex.class.getDeclaredFields())
                .filter(field -> ConcurrentHashMap.class.isAssignableFrom(field.getType()))
                .toList();

        assertThat(outerMaps).extracting(Field::getName).containsExactly("traces");
        assertThat(outerMaps.getFirst().getGenericType()).isInstanceOf(ParameterizedType.class);
        assertThat(outerMaps.getFirst().getGenericType().getTypeName())
                .contains("java.lang.String", "TraceWatchdogState");
    }

    @Test
    void watchdogTraceIndexNeverLeaksTheRawMapThroughItsPublicSurface() {
        for (Method method : WatchdogTraceIndex.class.getDeclaredMethods()) {
            assertThat(Map.class.isAssignableFrom(method.getReturnType()))
                    .as("method %s must not return the raw trace map", method.getName())
                    .isFalse();
        }
    }

    @Test
    void spanPermitLedgerIsTheSingleOwnerOfTheCasBudget() {
        List<String> atomicIntegers = Arrays.stream(SpanPermitLedger.class.getDeclaredFields())
                .filter(field -> AtomicInteger.class.isAssignableFrom(field.getType()))
                .map(Field::getName)
                .toList();
        assertThat(atomicIntegers).containsExactly("trackedSpanCount");

        List<String> atomicLongs = Arrays.stream(SpanPermitLedger.class.getDeclaredFields())
                .filter(field -> AtomicLong.class.isAssignableFrom(field.getType()))
                .map(Field::getName)
                .toList();
        assertThat(atomicLongs).containsExactly("invariantViolationTotal");
    }

    @Test
    void supportTypesAreNotPublic() {
        assertThat(Modifier.isPublic(SpanPermitLedger.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(TraceWatchdogState.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(WatchdogTraceIndex.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(SpanWatchdogProcessor.class.getModifiers())).isTrue();
    }
}
