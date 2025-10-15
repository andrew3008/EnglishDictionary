package space.br1440.platform.metrics.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.micrometer.core.instrument.Timer;
import space.br1440.platform.metrics.MetricBuilder;


public class CustomServerInterceptor implements ServerInterceptor {

    private final MetricBuilder metricBuilder;

    public CustomServerInterceptor(MetricBuilder metricBuilder) {
        this.metricBuilder = metricBuilder;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        var sampler = Timer.start();

        GrpcMotitoringServerCall grpcMotitoringServerCall = new GrpcMotitoringServerCall(call);


        return new MetricCollectingServerCallListener<>(
                next.startCall(grpcMotitoringServerCall, headers),
                grpcMotitoringServerCall::getResponseCode,
                sampler,
                metricBuilder,
                call.getMethodDescriptor());
    }
}
