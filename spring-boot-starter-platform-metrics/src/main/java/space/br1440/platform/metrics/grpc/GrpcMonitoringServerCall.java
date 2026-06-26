package space.br1440.platform.metrics.grpc;

import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.Status;
import space.br1440.platform.metrics.PlatformMetrics;

class GrpcMonitoringServerCall<ReqT, RespT> extends ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT> {

    private volatile Status.Code responseCode = Status.Code.UNKNOWN;

    private final PlatformMetrics platformMetrics;
    private final String methodName;
    private final String serviceName;

    GrpcMonitoringServerCall(final ServerCall<ReqT, RespT> delegate,
                              final PlatformMetrics platformMetrics,
                              final String methodName,
                              final String serviceName) {
        super(delegate);
        this.platformMetrics = platformMetrics;
        this.methodName = methodName;
        this.serviceName = serviceName;
    }

    Status.Code getResponseCode() {
        return responseCode;
    }

    @Override
    public void sendMessage(RespT message) {
        platformMetrics.grpcStreamMessageSent(methodName, serviceName).increment();
        super.sendMessage(message);
    }

    @Override
    public void close(final Status status, final Metadata responseHeaders) {
        responseCode = status.getCode();
        super.close(status, responseHeaders);
    }
}
