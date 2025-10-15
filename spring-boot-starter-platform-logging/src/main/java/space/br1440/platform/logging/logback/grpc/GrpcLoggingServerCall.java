package space.br1440.platform.logging.logback.grpc;

import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.Status;

public class GrpcLoggingServerCall<Q,A> extends ForwardingServerCall.SimpleForwardingServerCall<Q, A> {


    private Status.Code responseCode = Status.Code.UNKNOWN;


    public GrpcLoggingServerCall(final ServerCall<Q, A> delegate) {

        super(delegate);
    }

    public Status.Code getResponseCode() {
        return this.responseCode;
    }

    @Override
    public void close(final Status status, final Metadata responseHeaders) {
        this.responseCode = status.getCode();
        super.close(status, responseHeaders);
    }

    @Override
    public void sendMessage(final A responseMessage) {
        super.sendMessage(responseMessage);
    }

}


