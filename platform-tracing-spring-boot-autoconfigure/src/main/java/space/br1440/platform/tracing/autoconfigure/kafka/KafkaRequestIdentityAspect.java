package space.br1440.platform.tracing.autoconfigure.kafka;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.autoconfigure.support.RequestIdBoundarySupport;
import space.br1440.platform.tracing.autoconfigure.support.RequestIdentityBoundarySupport;

/** Привязывает технический requestId к execution одиночного Kafka listener. */
@Aspect
public final class KafkaRequestIdentityAspect {

    private final RequestIdentityBoundarySupport identityBoundary;

    public KafkaRequestIdentityAspect(RequestIdentityBoundarySupport identityBoundary) {
        this.identityBoundary = identityBoundary;
    }

    @Around("@annotation(org.springframework.kafka.annotation.KafkaListener)")
    public Object bindRequestIdentity(ProceedingJoinPoint joinPoint) throws Throwable {
        ConsumerRecord<?, ?> record = singleRecord(joinPoint.getArgs());
        if (record == null) {
            // Batch execution не получает ложный общий requestId: identity остаётся per-record.
            return joinPoint.proceed();
        }

        String requestId = resolveAndAttach(record);
        try (CorrelationScope ignored = identityBoundary.openRequestScope(requestId)) {
            return joinPoint.proceed();
        }
    }

    private static ConsumerRecord<?, ?> singleRecord(Object[] arguments) {
        ConsumerRecord<?, ?> found = null;
        for (Object argument : arguments) {
            if (argument instanceof ConsumerRecord<?, ?> record) {
                if (found != null) {
                    return null;
                }
                found = record;
            }
        }
        return found;
    }

    private static String resolveAndAttach(ConsumerRecord<?, ?> record) {
        Header header = record.headers().lastHeader(PlatformHeaders.X_REQUEST_ID);
        String incoming = header == null || header.value() == null
                ? null
                : new String(header.value(), StandardCharsets.UTF_8);
        String requestId = RequestIdBoundarySupport.resolve(incoming);
        if (incoming == null || !requestId.equals(incoming)) {
            record.headers()
                    .remove(PlatformHeaders.X_REQUEST_ID)
                    .add(PlatformHeaders.X_REQUEST_ID, requestId.getBytes(StandardCharsets.UTF_8));
        }
        return requestId;
    }
}
