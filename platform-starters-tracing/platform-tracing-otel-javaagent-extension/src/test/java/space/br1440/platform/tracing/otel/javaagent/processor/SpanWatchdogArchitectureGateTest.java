package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class SpanWatchdogArchitectureGateTest {

    @Test
    void g9_watchdog_имеет_одного_authoritative_trace_owner() {
        List<Field> outerMaps = Arrays.stream(SpanWatchdogProcessor.class.getDeclaredFields())
                .filter(field -> ConcurrentHashMap.class.isAssignableFrom(field.getType()))
                .toList();

        assertThat(outerMaps).extracting(Field::getName).containsExactly("activeTraces");
        assertThat(outerMaps.get(0).getGenericType()).isInstanceOf(ParameterizedType.class);
        assertThat(outerMaps.get(0).getGenericType().getTypeName())
                .contains("java.lang.String", "SpanWatchdogProcessor$TraceWatchdogState");

        List<String> atomicCounters = Arrays.stream(SpanWatchdogProcessor.class.getDeclaredFields())
                .filter(field -> AtomicLong.class.isAssignableFrom(field.getType()))
                .map(Field::getName)
                .toList();
        assertThat(atomicCounters).containsExactlyInAnyOrder("forcedSpanCloses", "forcedTraceCloses");
    }
}
