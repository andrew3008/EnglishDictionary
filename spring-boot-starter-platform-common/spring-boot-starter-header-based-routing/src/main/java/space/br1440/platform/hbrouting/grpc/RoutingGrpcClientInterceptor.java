package space.br1440.platform.hbrouting.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import space.br1440.platform.hbrouting.context.MeshHeaders;
import space.br1440.platform.hbrouting.context.RoutingContextHolder;

public class RoutingGrpcClientInterceptor implements ClientInterceptor {

    private static final Metadata.Key<String> FEATURE_BRANCH_KEY =
            Metadata.Key.of(MeshHeaders.FEATURE_BRANCH, Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next
    ) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                String branch = RoutingContextHolder.get();

                if (branch != null && !branch.isBlank()) {
                    headers.put(FEATURE_BRANCH_KEY, branch);
                }

                super.start(responseListener, headers);
            }
        };
    }
}