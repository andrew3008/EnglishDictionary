package space.br1440.platform.metrics.grpc;

import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.Status;

class GrpcMonitoringServerCall<ReqT, RespT> extends ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT> {

    private volatile Status.Code responseCode = Status.Code.UNKNOWN;

    GrpcMonitoringServerCall(final ServerCall<ReqT, RespT> delegate) {
        super(delegate);
    }

    Status.Code getResponseCode() {
        return responseCode;
    }

    @Override
    public void close(final Status status, final Metadata responseHeaders) {
        responseCode = status.getCode();
        super.close(status, responseHeaders);
    }
}
