package space.br1440.platform.metrics.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import space.br1440.platform.metrics.PlatformMetrics;

@RequiredArgsConstructor
public class CustomServerInterceptor implements ServerInterceptor {

    private final PlatformMetrics platformMetrics;
    private final MeterRegistry meterRegistry;

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                 Metadata headers,
                                                                 ServerCallHandler<ReqT, RespT> next) {
        Timer.Sample sampler = Timer.start(meterRegistry);
        GrpcMonitoringServerCall<ReqT, RespT> monitoringCall = new GrpcMonitoringServerCall<>(
                call,
                platformMetrics,
                call.getMethodDescriptor().getBareMethodName(),
                call.getMethodDescriptor().getServiceName());

        return new MetricCollectingServerCallListener<>(
                next.startCall(monitoringCall, headers),
                monitoringCall::getResponseCode,
                sampler,
                platformMetrics,
                call.getMethodDescriptor());
    }
}
