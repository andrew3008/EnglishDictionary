package space.br1440.platform.logging.logback.grpc;

import io.grpc.ForwardingServerCallListener;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.Status;
import org.slf4j.MDC;
import space.br1440.platform.logging.mdc.MDCLogKey;

import java.util.function.Supplier;

public class GrpcCollectingServerCallListener<Q> extends ForwardingServerCallListener.SimpleForwardingServerCallListener<Q> {


    private final Supplier<Status.Code> responseCodeSupplier;
    private final MethodDescriptor methodDescriptor;


    public GrpcCollectingServerCallListener(
            final ServerCall.Listener<Q> delegate,
            Supplier<Status.Code> responseCodeSupplier,
            MethodDescriptor methodDescriptor
    ) {

        super(delegate);
        this.responseCodeSupplier = responseCodeSupplier;
        this.methodDescriptor = methodDescriptor;
    }

    @Override
    public void onMessage(final Q requestMessage) {
        MDC.put(MDCLogKey.GRPC_METHOD.value(), methodDescriptor.getFullMethodName());
        super.onMessage(requestMessage);
    }

    @Override
    public void onComplete() {
        MDC.put(MDCLogKey.GRPC_STATUS_CODE.value(), responseCodeSupplier.get().name());
        super.onComplete();
    }

    @Override
    public void onCancel() {
        super.onCancel();
    }

}

