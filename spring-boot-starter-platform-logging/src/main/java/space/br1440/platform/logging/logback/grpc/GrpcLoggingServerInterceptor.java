package space.br1440.platform.logging.logback.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;


public class GrpcLoggingServerInterceptor implements ServerInterceptor {



    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        GrpcLoggingServerCall grpcLoggingServerCall = new GrpcLoggingServerCall(call);


        return new GrpcCollectingServerCallListener<>(
                next.startCall(grpcLoggingServerCall, headers),
                grpcLoggingServerCall::getResponseCode,
                call.getMethodDescriptor());
    }
}
