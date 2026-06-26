package space.br1440.platform.metrics.grpc;

import io.grpc.ForwardingServerCallListener;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.Status;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.metrics.PlatformMetrics;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@Slf4j
public class MetricCollectingServerCallListener<Q> extends ForwardingServerCallListener.SimpleForwardingServerCallListener<Q> {

    private final Supplier<Status.Code> responseCodeSupplier;
    private final Timer.Sample sample;
    private final PlatformMetrics platformMetrics;
    private final MethodDescriptor<?, ?> methodDescriptor;

    private final AtomicBoolean reported = new AtomicBoolean(false);

    public MetricCollectingServerCallListener(final ServerCall.Listener<Q> delegate,
                                              Supplier<Status.Code> responseCodeSupplier,
                                              Timer.Sample sample,
                                              PlatformMetrics platformMetrics,
                                              MethodDescriptor<?, ?> methodDescriptor) {
        super(delegate);

        this.responseCodeSupplier = responseCodeSupplier;
        this.sample = sample;
        this.platformMetrics = platformMetrics;
        this.methodDescriptor = methodDescriptor;
    }

    @Override
    public void onMessage(final Q requestMessage) {
        final String methodName = methodDescriptor.getBareMethodName();
        final String serviceName = methodDescriptor.getServiceName();

        platformMetrics.grpcReceived(methodName, serviceName).increment();
        platformMetrics.grpcStreamMessageReceived(methodName, serviceName).increment();

        super.onMessage(requestMessage);
    }

    @Override
    public void onHalfClose() {
        try {
            super.onHalfClose();
        } catch (RuntimeException ex) {
            log.warn("Необработанное исключение в gRPC-обработчике [{}#{}], метрика зафиксирована как INTERNAL",
                    methodDescriptor.getServiceName(), methodDescriptor.getBareMethodName(), ex);
            report(Status.Code.INTERNAL);
            throw ex;
        }
    }

    @Override
    public void onComplete() {
        report(responseCodeSupplier.get());
        super.onComplete();
    }

    @Override
    public void onCancel() {
        report(Status.Code.CANCELLED);
        super.onCancel();
    }

    private void report(final Status.Code code) {
        if (!reported.compareAndSet(false, true)) {
            return;
        }

        final String methodName = methodDescriptor.getBareMethodName();
        final String serviceName = methodDescriptor.getServiceName();
        // code.name() вместо code.value() — читаемые метки: "OK", "CANCELLED", "INTERNAL" и т.д.
        final String statusName = code.name();

        // Гистограмма задержки запроса
        sample.stop(platformMetrics.grpcTimer(methodName, serviceName, statusName));

        // Счётчик отправленных ответов
        platformMetrics.grpcSent(methodName, serviceName, statusName).increment();
    }
}
