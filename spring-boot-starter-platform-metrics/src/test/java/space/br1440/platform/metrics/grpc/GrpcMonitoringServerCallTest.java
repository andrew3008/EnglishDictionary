package space.br1440.platform.metrics.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.Status;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.metrics.MetricTags;
import space.br1440.platform.metrics.PlatformMetrics;
import space.br1440.platform.metrics.adr.support.MetricsTestSupport;
import space.br1440.platform.metrics.adr.support.PlatformMetricsFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("GrpcMonitoringServerCall: platform.grpc.server.stream.messages.sent")
class GrpcMonitoringServerCallTest {

    private static final String SERVICE_NAME = "helloworld.Greeter";
    private static final String METHOD_NAME  = "SayHello";

    private MeterRegistry registry;
    private PlatformMetrics platformMetrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        platformMetrics = PlatformMetricsFactory.create(registry);
    }

    @SuppressWarnings("unchecked")
    private GrpcMonitoringServerCall<Object, Object> monitoringCall() {
        ServerCall<Object, Object> delegate = mock(ServerCall.class);
        return new GrpcMonitoringServerCall<>(delegate, platformMetrics, METHOD_NAME, SERVICE_NAME);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Test A — sendMessage() increments new stream messages sent counter
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("sendMessage() дважды → platform.grpc.server.stream.messages.sent == 2.0")
    void sendMessageTwiceIncrementsStreamMessagesSentCounterTwice() {
        GrpcMonitoringServerCall<Object, Object> call = monitoringCall();

        call.sendMessage(new Object());
        call.sendMessage(new Object());

        Counter counter = MetricsTestSupport.findCounter(registry,
                "platform.grpc.server.stream.messages.sent",
                MetricTags.RPC_METHOD, METHOD_NAME,
                MetricTags.SERVICE, SERVICE_NAME);

        assertThat(counter.count())
                .as("stream.messages.sent must equal 2.0 after two sendMessage() calls")
                .isEqualTo(2.0);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Test A variant — sendMessage() also delegates to underlying ServerCall
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("sendMessage() делегирует вызов оригинальному ServerCall")
    void sendMessageDelegatesToUnderlyingServerCall() {
        @SuppressWarnings("unchecked")
        ServerCall<Object, Object> delegate = mock(ServerCall.class);
        GrpcMonitoringServerCall<Object, Object> call =
                new GrpcMonitoringServerCall<>(delegate, platformMetrics, METHOD_NAME, SERVICE_NAME);

        Object msg = new Object();
        call.sendMessage(msg);

        verify(delegate, times(1)).sendMessage(msg);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Tag check — no rpc_status_code on stream.messages.sent
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("stream.messages.sent не имеет тега rpc_status_code")
    void streamMessagesSentHasNoRpcStatusCodeTag() {
        GrpcMonitoringServerCall<Object, Object> call = monitoringCall();
        call.sendMessage(new Object());

        assertThat(MetricsTestSupport.tagValue(registry,
                "platform.grpc.server.stream.messages.sent",
                MetricTags.RPC_STATUS_CODE))
                .as("stream.messages.sent must not carry rpc_status_code tag " +
                    "(status is unknown at message send time)")
                .isNull();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Test B — existing platform.grpc.server.sent NOT incremented by sendMessage()
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("sendMessage() не инкрементирует legacy platform.grpc.server.sent")
    void sendMessageDoesNotIncrementLegacySentCounter() {
        GrpcMonitoringServerCall<Object, Object> call = monitoringCall();

        call.sendMessage(new Object());
        call.sendMessage(new Object());

        double legacySentTotal = registry.find("platform.grpc.server.sent")
                .counters()
                .stream()
                .mapToDouble(Counter::count)
                .sum();

        assertThat(legacySentTotal)
                .as("legacy platform.grpc.server.sent must remain 0 after sendMessage() calls " +
                    "(it is only incremented in report() on call completion)")
                .isEqualTo(0.0);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Test C — close() still captures Status.Code (existing behaviour)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("close() по-прежнему фиксирует Status.Code (обратная совместимость)")
    void closeStillCapturesResponseCode() {
        GrpcMonitoringServerCall<Object, Object> call = monitoringCall();

        assertThat(call.getResponseCode())
                .as("initial response code must be UNKNOWN")
                .isEqualTo(Status.Code.UNKNOWN);

        call.close(Status.OK, new Metadata());

        assertThat(call.getResponseCode())
                .as("after close(OK), response code must be OK")
                .isEqualTo(Status.Code.OK);
    }
}
