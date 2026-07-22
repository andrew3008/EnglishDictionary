package space.br1440.platform.tracing.autoconfigure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.autoconfigure.support.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

class KafkaRequestIdentityAspectTest {

    private TracingRuntime runtime;
    private KafkaRequestIdentityAspect aspect;

    @BeforeEach
    void setUp() {
        runtime = NoOpTracingRuntime.noop();
        aspect = new KafkaRequestIdentityAspect(new RequestIdentityBoundarySupport(runtime));
    }

    @Test
    void preservesValidInboundRequestIdAndCleansAfterSuccess() throws Throwable {
        ConsumerRecord<String, String> record = record("value");
        record.headers().add(PlatformHeaders.X_REQUEST_ID, bytes("request-42"));
        ProceedingJoinPoint joinPoint = joinPoint(record, () -> {
            assertThat(runtime.currentRequestId()).contains("request-42");
            assertThat(runtime.currentCorrelationId()).isEmpty();
            return "ok";
        });

        assertThat(aspect.bindRequestIdentity(joinPoint)).isEqualTo("ok");
        assertThat(runtime.currentRequestId()).isEmpty();
    }

    @Test
    void generatesOnceForMissingOrMalformedHeaderAndKeepsSameDeliveryStable() throws Throwable {
        ConsumerRecord<String, String> missing = record("missing");
        ProceedingJoinPoint first = joinPoint(missing, () -> runtime.currentRequestId().orElseThrow());

        String generated = (String) aspect.bindRequestIdentity(first);
        String repeated = (String) aspect.bindRequestIdentity(first);

        assertThat(generated).isEqualTo(repeated);
        assertThat(header(missing, PlatformHeaders.X_REQUEST_ID)).isEqualTo(generated);

        ConsumerRecord<String, String> malformed = record("malformed");
        malformed.headers().add(PlatformHeaders.X_REQUEST_ID, bytes("bad request"));
        String replacement = (String) aspect.bindRequestIdentity(
                joinPoint(malformed, () -> runtime.currentRequestId().orElseThrow()));
        assertThat(replacement).isNotEqualTo("bad request");
        assertThat(header(malformed, PlatformHeaders.X_REQUEST_ID)).isEqualTo(replacement);
    }

    @Test
    void cleansAfterErrorAndNeverInfersBusinessCorrelation() {
        ConsumerRecord<String, String> record = record("failure");
        record.headers().add("correlation_id", bytes("native-42"));
        record.headers().add("baggage", bytes("platform.correlation.id=spoofed"));
        ProceedingJoinPoint joinPoint = joinPoint(record, () -> {
            assertThat(runtime.currentCorrelationId()).isEmpty();
            throw new IllegalStateException("boom");
        });

        assertThatThrownBy(() -> aspect.bindRequestIdentity(joinPoint))
                .isInstanceOf(IllegalStateException.class);
        assertThat(runtime.currentRequestId()).isEmpty();
        assertThat(runtime.currentCorrelationId()).isEmpty();
    }

    @Test
    void batchDoesNotInstallOneMessageIdentityForAllRecords() throws Throwable {
        ConsumerRecord<String, String> first = record("first");
        ConsumerRecord<String, String> second = record("second");
        first.headers().add(PlatformHeaders.X_REQUEST_ID, bytes("request-first"));
        second.headers().add(PlatformHeaders.X_REQUEST_ID, bytes("request-second"));
        ProceedingJoinPoint joinPoint = joinPoint(List.of(first, second), () -> {
            assertThat(runtime.currentRequestId()).isEmpty();
            return null;
        });

        aspect.bindRequestIdentity(joinPoint);

        assertThat(header(first, PlatformHeaders.X_REQUEST_ID)).isEqualTo("request-first");
        assertThat(header(second, PlatformHeaders.X_REQUEST_ID)).isEqualTo("request-second");
    }

    @Test
    void concurrentMessagesKeepIndependentExecutionState() throws Exception {
        ConsumerRecord<String, String> first = record("first");
        ConsumerRecord<String, String> second = record("second");
        first.headers().add(PlatformHeaders.X_REQUEST_ID, bytes("request-first"));
        second.headers().add(PlatformHeaders.X_REQUEST_ID, bytes("request-second"));
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> firstResult = executor.submit(() -> invokeWhileOverlapping(first, bothEntered, release));
            Future<String> secondResult = executor.submit(() -> invokeWhileOverlapping(second, bothEntered, release));

            assertThat(bothEntered.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(firstResult.get(5, TimeUnit.SECONDS)).isEqualTo("request-first");
            assertThat(secondResult.get(5, TimeUnit.SECONDS)).isEqualTo("request-second");
        }
        assertThat(runtime.currentRequestId()).isEmpty();
    }

    private String invokeWhileOverlapping(ConsumerRecord<String, String> record,
                                          CountDownLatch bothEntered,
                                          CountDownLatch release) throws Exception {
        ProceedingJoinPoint joinPoint = joinPoint(record, () -> {
            bothEntered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent listener timed out");
            }
            return runtime.currentRequestId().orElseThrow();
        });
        try {
            return (String) aspect.bindRequestIdentity(joinPoint);
        } catch (Exception exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new AssertionError(throwable);
        }
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("orders", 0, 1L, "key", value);
    }

    private static ProceedingJoinPoint joinPoint(Object argument, ThrowingAction action) {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{argument});
        try {
            when(joinPoint.proceed()).thenAnswer(ignored -> action.run());
        } catch (Throwable impossible) {
            throw new AssertionError(impossible);
        }
        return joinPoint;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface ThrowingAction {
        Object run() throws Throwable;
    }
}
