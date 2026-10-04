package space.br1440.platform.tracing.autoconfigure.integrations.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.span.enrich.SpanEnricher;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;

@ExtendWith(OutputCaptureExtension.class)
class KafkaRequestIdentitySeedFailureLogTest {

    private static final String WARN_LINE =
            "Failed to seed request id on the current span: java.lang.IllegalStateException";

    @Test
    void oneInstanceLogsTheFirstEnrichmentFailureOnly(CapturedOutput output) throws Throwable {
        // given
        KafkaRequestIdentityAspect first = aspect();
        ConsumerRecord<String, String> record = record();

        // when
        first.bindRequestIdentity(joinPoint(record));
        first.bindRequestIdentity(joinPoint(record));

        // then
        assertThat(occurrences(output.getAll(), WARN_LINE)).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("request-b");

        // when
        KafkaRequestIdentityAspect second = aspect();
        second.bindRequestIdentity(joinPoint(record));

        // then
        assertThat(occurrences(output.getAll(), WARN_LINE)).isEqualTo(2);
        assertThat(output.getAll()).doesNotContain("request-b");
    }

    private static KafkaRequestIdentityAspect aspect() {
        SpanEnricher failing = enrichment -> {
            throw new IllegalStateException("enrichment failed");
        };
        return new KafkaRequestIdentityAspect(
                new RequestIdentityBoundarySupport(NoOpTracingRuntime.noop()),
                failing);
    }

    private static ConsumerRecord<String, String> record() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("orders", 0, 0L, "key", "value");
        record.headers().add(PlatformHeaders.X_REQUEST_ID, "request-b".getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private static ProceedingJoinPoint joinPoint(ConsumerRecord<String, String> record) throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{record});
        when(joinPoint.proceed()).thenReturn("kept");
        return joinPoint;
    }

    @SuppressWarnings("SameParameterValue")
    private static int occurrences(String text, String line) {
        int count = 0;
        int from = 0;

        while (true) {
            int found = text.indexOf(line, from);
            if (found < 0) {
                return count;
            }

            count++;
            from = (found + line.length());
        }
    }
}
