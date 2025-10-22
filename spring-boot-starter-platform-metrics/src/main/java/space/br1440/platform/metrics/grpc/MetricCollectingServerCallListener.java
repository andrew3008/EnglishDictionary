package space.br1440.platform.metrics.grpc;

import io.grpc.ForwardingServerCallListener;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.Status;
import io.micrometer.core.instrument.Timer;
import space.br1440.platform.metrics.MetricBuilder;

import java.util.function.Supplier;

public class MetricCollectingServerCallListener<Q> extends ForwardingServerCallListener.SimpleForwardingServerCallListener<Q> {


    private final Supplier<Status.Code> responseCodeSupplier;
    private final Timer.Sample sample;
    private final MetricBuilder metricBuilder;
    private final MethodDescriptor methodDescriptor;


    public MetricCollectingServerCallListener(
            final ServerCall.Listener<Q> delegate,
            Supplier<Status.Code> responseCodeSupplier,
            Timer.Sample sample,
            MetricBuilder metricBuilder,
            MethodDescriptor methodDescriptor
    ) {

        super(delegate);
        this.responseCodeSupplier = responseCodeSupplier;
        this.sample = sample;
        this.metricBuilder = metricBuilder;
        this.methodDescriptor = methodDescriptor;
    }

    @Override
    public void onMessage(final Q requestMessage) {
        metricBuilder.grpcResieved(methodDescriptor.getBareMethodName(), methodDescriptor.getServiceName()).increment();
        super.onMessage(requestMessage);
    }

    @Override
    public void onComplete() {
        report(this.responseCodeSupplier.get());
        super.onComplete();
    }

    @Override
    public void onCancel() {
        report(Status.Code.CANCELLED);
        super.onCancel();
    }

    private void report(final Status.Code code) {
        //histogram metrics
        sample.stop(metricBuilder.grpcTimer(
                        methodDescriptor.getBareMethodName(),
                        methodDescriptor.getServiceName(),
                        String.valueOf(code.value())
                )
        );

        //sent response statistic
        metricBuilder.grpcSent(
                        methodDescriptor.getBareMethodName(),
                        methodDescriptor.getServiceName(),
                        String.valueOf(code.value()))
                .increment();

    }

}

