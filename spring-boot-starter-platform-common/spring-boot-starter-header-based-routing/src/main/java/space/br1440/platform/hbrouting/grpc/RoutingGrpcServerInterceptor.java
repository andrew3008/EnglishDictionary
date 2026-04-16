package space.br1440.platform.hbrouting.grpc;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import space.br1440.platform.hbrouting.config.RoutingProperties;
import space.br1440.platform.hbrouting.context.MeshHeaders;
import space.br1440.platform.hbrouting.context.RoutingContextHolder;
import space.br1440.platform.hbrouting.support.RoutingHeaderValidator;

public class RoutingGrpcServerInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> FEATURE_BRANCH_KEY =
            Metadata.Key.of(MeshHeaders.FEATURE_BRANCH, Metadata.ASCII_STRING_MARSHALLER);

    private final RoutingProperties properties;
    private final RoutingHeaderValidator validator;

    public RoutingGrpcServerInterceptor(RoutingProperties properties, RoutingHeaderValidator validator) {
        this.properties = properties;
        this.validator = validator;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next
    ) {
        String branch = headers.get(FEATURE_BRANCH_KEY);

        if (branch != null && !branch.isBlank()) {
            if (!properties.isValidateHeader() || validator.isValid(branch)) {
                RoutingContextHolder.set(branch.trim());
            }
        }

        ServerCall.Listener<ReqT> delegate = next.startCall(call, headers);

        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
            @Override
            public void onComplete() {
                try {
                    super.onComplete();
                } finally {
                    RoutingContextHolder.clear();
                }
            }

            @Override
            public void onCancel() {
                try {
                    super.onCancel();
                } finally {
                    RoutingContextHolder.clear();
                }
            }
        };
    }
}