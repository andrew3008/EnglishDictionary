package space.br1440.platform.metrics.adr;

import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.Status;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import space.br1440.platform.metrics.MetricTags;
import space.br1440.platform.metrics.adr.support.MetricsTestSupport;
import space.br1440.platform.metrics.adr.support.PlatformMetricsFactory;
import space.br1440.platform.metrics.grpc.MetricCollectingServerCallListener;
import space.br1440.platform.metrics.PlatformMetrics;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DisplayName("ADR: gRPC-метрики")
class GrpcMetricsAdrTest {

    private static final String SERVICE_NAME = "helloworld.Greeter";
    private static final String METHOD_NAME  = "SayHello";

    private MeterRegistry registry;
    private PlatformMetrics platformMetrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        platformMetrics = PlatformMetricsFactory.create(registry);
    }

    private MetricCollectingServerCallListener<Object> listener(Supplier<Status.Code> codeSupplier) {
        Timer.Sample sample = Timer.start(registry);
        MethodDescriptor<?, ?> descriptor = methodDescriptor(SERVICE_NAME, METHOD_NAME);
        @SuppressWarnings("unchecked")
        ServerCall.Listener<Object> delegate = mock(ServerCall.Listener.class);
        return new MetricCollectingServerCallListener<>(delegate, codeSupplier, sample, platformMetrics, descriptor);
    }

    private static MethodDescriptor<?, ?> methodDescriptor(String service, String method) {
        return MethodDescriptor.newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(service + "/" + method)
                .setRequestMarshaller(mock(MethodDescriptor.Marshaller.class))
                .setResponseMarshaller(mock(MethodDescriptor.Marshaller.class))
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 0. platform_grpc_server_stream_messages_received_total (S2)
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("platform_grpc_server_stream_messages_received_total")
    class StreamMessagesReceivedCounter {

        @Test
        @DisplayName("onMessage() дважды → stream.messages.received == 2.0 с тегами rpc_method и service")
        void onMessageTwiceIncrementsStreamMessagesReceivedTwice() {
            MetricCollectingServerCallListener<Object> l = listener(() -> Status.Code.OK);

            l.onMessage(new Object());
            l.onMessage(new Object());

            Counter counter = MetricsTestSupport.findCounter(registry,
                    "platform.grpc.server.stream.messages.received",
                    MetricTags.RPC_METHOD, METHOD_NAME,
                    MetricTags.SERVICE, SERVICE_NAME);

            assertThat(counter.count())
                    .as("stream.messages.received must equal 2.0 after two onMessage() calls")
                    .isEqualTo(2.0);
        }

        @Test
        @DisplayName("stream.messages.received не имеет тега rpc_status_code")
        void streamMessagesReceivedHasNoRpcStatusCodeTag() {
            MetricCollectingServerCallListener<Object> l = listener(() -> Status.Code.OK);
            l.onMessage(new Object());

            assertThat(MetricsTestSupport.tagValue(registry,
                    "platform.grpc.server.stream.messages.received",
                    MetricTags.RPC_STATUS_CODE))
                    .as("stream.messages.received must not carry rpc_status_code tag " +
                        "(status is unknown at message receive time)")
                    .isNull();
        }

        @Test
        @DisplayName("Test B: legacy platform.grpc.server.received также == 2.0 (оба инкрементируются)")
        void legacyReceivedAlsoIncrementsPerMessage() {
            MetricCollectingServerCallListener<Object> l = listener(() -> Status.Code.OK);

            l.onMessage(new Object());
            l.onMessage(new Object());

            Counter legacyReceived = MetricsTestSupport.findCounter(registry,
                    "platform.grpc.server.received",
                    MetricTags.RPC_METHOD, METHOD_NAME,
                    MetricTags.SERVICE, SERVICE_NAME);
            assertThat(legacyReceived.count())
                    .as("legacy platform.grpc.server.received must still equal 2.0 (backward compat)")
                    .isEqualTo(2.0);

            Counter streamReceived = MetricsTestSupport.findCounter(registry,
                    "platform.grpc.server.stream.messages.received",
                    MetricTags.RPC_METHOD, METHOD_NAME,
                    MetricTags.SERVICE, SERVICE_NAME);
            assertThat(streamReceived.count())
                    .as("stream.messages.received must also equal 2.0")
                    .isEqualTo(2.0);
        }

        @Test
        @DisplayName("Test C: onMessage() не инкрементирует S1 stream.messages.sent")
        void onMessageDoesNotIncrementStreamMessagesSent() {
            MetricCollectingServerCallListener<Object> l = listener(() -> Status.Code.OK);

            l.onMessage(new Object());
            l.onMessage(new Object());

            double sentTotal = registry.find("platform.grpc.server.stream.messages.sent")
                    .counters()
                    .stream()
                    .mapToDouble(Counter::count)
                    .sum();

            assertThat(sentTotal)
                    .as("inbound onMessage() must not touch outbound stream.messages.sent counter")
                    .isEqualTo(0.0);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. platform_grpc_server_received_total
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("platform_grpc_server_received_total")
    class ReceivedTotal {

        @Test
        @DisplayName("инкрементируется при onMessage с тегами rpc_method и service")
        void incrementsOnMessage() {
            MetricCollectingServerCallListener<Object> l = listener(() -> Status.Code.OK);

            l.onMessage(new Object());
            l.onMessage(new Object());

            Counter counter = MetricsTestSupport.findCounter(registry, "platform.grpc.server.received",
                    MetricTags.RPC_METHOD, METHOD_NAME,
                    MetricTags.SERVICE, SERVICE_NAME);

            assertThat(counter.count()).isEqualTo(2.0);
        }

        @Test
        @DisplayName("тег rpc_status_code отсутствует (ADR: received не несёт код статуса)")
        void noStatusCodeTag() {
            MetricCollectingServerCallListener<Object> l = listener(() -> Status.Code.OK);
            l.onMessage(new Object());

            // Тег rpc_status_code не должен присутствовать в received-счётчике
            assertThat(MetricsTestSupport.tagValue(registry, "platform.grpc.server.received", MetricTags.RPC_STATUS_CODE))
                    .isNull();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. platform_grpc_server_sent_total + platform_grpc_server_seconds
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("platform_grpc_server_sent_total + platform_grpc_server_seconds")
    class SentAndTimer {

        @Test
        @DisplayName("onComplete(OK): sent_total и timer имеют теги rpc_method, rpc_status_code=OK, service")
        void onCompleteOk() {
            MetricCollectingServerCallListener<Object> l = listener(() -> Status.Code.OK);
            l.onComplete();

            Counter sent = MetricsTestSupport.findCounter(registry, "platform.grpc.server.sent",
                    MetricTags.RPC_METHOD, METHOD_NAME,
                    MetricTags.RPC_STATUS_CODE, "OK",
                    MetricTags.SERVICE, SERVICE_NAME);
            assertThat(sent.count()).isEqualTo(1.0);

            Timer timer = MetricsTestSupport.findTimer(registry, "platform.grpc.server",
                    MetricTags.RPC_METHOD, METHOD_NAME,
                    MetricTags.RPC_STATUS_CODE, "OK",
                    MetricTags.SERVICE, SERVICE_NAME);
            assertThat(timer.count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("ADR: rpc_status_code — символическое имя, не числовой код (OK не 0, INTERNAL не 13)")
        void statusCodeIsSymbolicName() {
            listener(() -> Status.Code.OK).onComplete();
            listener(() -> Status.Code.INTERNAL).onComplete();

            // OK — символическое
            assertThat(MetricsTestSupport.findCounter(registry, "platform.grpc.server.sent",
                    MetricTags.RPC_METHOD, METHOD_NAME,
                    MetricTags.RPC_STATUS_CODE, "OK",
                    MetricTags.SERVICE, SERVICE_NAME).count()).isEqualTo(1.0);

            // INTERNAL — символическое
            assertThat(MetricsTestSupport.findCounter(registry, "platform.grpc.server.sent",
                    MetricTags.RPC_METHOD, METHOD_NAME,
                    MetricTags.RPC_STATUS_CODE, "INTERNAL",
                    MetricTags.SERVICE, SERVICE_NAME).count()).isEqualTo(1.0);

            // Числовые значения "0" и "13" НЕ должны существовать
            assertThat(MetricsTestSupport.findCounterOrNull(registry, "platform.grpc.server.sent",
                    MetricTags.RPC_METHOD, METHOD_NAME,
                    MetricTags.RPC_STATUS_CODE, "0",
                    MetricTags.SERVICE, SERVICE_NAME)).isNull();
        }

        @Test
        @DisplayName("onCancel: sent_total и timer имеют rpc_status_code=CANCELLED")
        void onCancelSetsCancelledStatus() {
            MetricCollectingServerCallListener<Object> l = listener(() -> Status.Code.OK);
            l.onCancel();

            Counter sent = MetricsTestSupport.findCounter(registry, "platform.grpc.server.sent",
                    MetricTags.RPC_METHOD, METHOD_NAME,
                    MetricTags.RPC_STATUS_CODE, "CANCELLED",
                    MetricTags.SERVICE, SERVICE_NAME);
            assertThat(sent.count()).isEqualTo(1.0);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3. Идемпотентность report()
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Идемпотентность report()")
    class Idempotency {

        @Test
        @DisplayName("onComplete + onCancel: метрика записывается ровно один раз")
        void onCompleteAndCancelRecordsOnce() {
            MetricCollectingServerCallListener<Object> l = listener(() -> Status.Code.OK);
            l.onComplete();
            l.onCancel(); // второй вызов — должен быть проигнорирован

            // Суммируем все sent-серии: должна быть ровно одна запись
            double totalSent = registry.find("platform.grpc.server.sent").counters()
                    .stream().mapToDouble(Counter::count).sum();
            assertThat(totalSent).isEqualTo(1.0);

            long totalTimerCount = registry.find("platform.grpc.server").timers()
                    .stream().mapToLong(Timer::count).sum();
            assertThat(totalTimerCount).isEqualTo(1L);
        }

        @Test
        @DisplayName("onCancel + onComplete: порядок не влияет — метрика ровно одна")
        void onCancelAndCompleteRecordsOnce() {
            MetricCollectingServerCallListener<Object> l = listener(() -> Status.Code.OK);
            l.onCancel();
            l.onComplete();

            double totalSent = registry.find("platform.grpc.server.sent").counters()
                    .stream().mapToDouble(Counter::count).sum();
            assertThat(totalSent).isEqualTo(1.0);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4. onHalfClose: бизнес-исключение → INTERNAL
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("onHalfClose: бизнес-исключение")
    class HalfClose {

        @Test
        @DisplayName("исключение в delegate.onHalfClose() → rpc_status_code=INTERNAL + исключение пробрасывается")
        void exceptionInHalfCloseRecordsInternalAndRethrows() {
            @SuppressWarnings("unchecked")
            ServerCall.Listener<Object> throwingDelegate = new ServerCall.Listener<>() {
                @Override
                public void onHalfClose() {
                    throw new RuntimeException("бизнес-ошибка");
                }
            };

            Timer.Sample sample = Timer.start(registry);
            MethodDescriptor<?, ?> descriptor = methodDescriptor(SERVICE_NAME, METHOD_NAME);
            MetricCollectingServerCallListener<Object> l = new MetricCollectingServerCallListener<>(
                    throwingDelegate, () -> Status.Code.OK, sample, platformMetrics, descriptor);

            assertThatThrownBy(l::onHalfClose)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("бизнес-ошибка");

            // Несмотря на исключение, метрика должна быть зафиксирована
            Counter sent = MetricsTestSupport.findCounter(registry, "platform.grpc.server.sent",
                    MetricTags.RPC_METHOD, METHOD_NAME,
                    MetricTags.RPC_STATUS_CODE, "INTERNAL",
                    MetricTags.SERVICE, SERVICE_NAME);
            assertThat(sent.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("после исключения в onHalfClose последующий onComplete не дублирует метрику")
        void afterHalfCloseExceptionOnCompleteIsNoop() {
            AtomicBoolean threw = new AtomicBoolean(false);

            ServerCall.Listener<Object> throwingDelegate = new ServerCall.Listener<>() {
                @Override
                public void onHalfClose() {
                    if (!threw.getAndSet(true)) {
                        throw new RuntimeException("бизнес-ошибка");
                    }
                }
            };

            Timer.Sample sample = Timer.start(registry);
            MethodDescriptor<?, ?> descriptor = methodDescriptor(SERVICE_NAME, METHOD_NAME);
            MetricCollectingServerCallListener<Object> l = new MetricCollectingServerCallListener<>(
                    throwingDelegate, () -> Status.Code.OK, sample, platformMetrics, descriptor);

            try { l.onHalfClose(); } catch (RuntimeException ignored) { }
            l.onComplete();

            double totalSent = registry.find("platform.grpc.server.sent").counters()
                    .stream().mapToDouble(Counter::count).sum();
            assertThat(totalSent).isEqualTo(1.0);
        }
    }
}
