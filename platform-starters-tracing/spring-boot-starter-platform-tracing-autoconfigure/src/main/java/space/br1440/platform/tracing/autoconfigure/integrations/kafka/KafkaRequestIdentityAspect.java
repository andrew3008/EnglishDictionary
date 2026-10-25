package space.br1440.platform.tracing.autoconfigure.integrations.kafka;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;

import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.span.enrich.SpanEnricher;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.runtime.propagation.RequestIdSupport;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Aspect
public final class KafkaRequestIdentityAspect implements Ordered {

    static final int ORDER = Ordered.LOWEST_PRECEDENCE - 100;

    private final RequestIdentityBoundarySupport identityBoundary;
    private final SpanEnricher spanEnricher;
    private final AtomicBoolean seedFailureLogged = new AtomicBoolean();

    public KafkaRequestIdentityAspect(RequestIdentityBoundarySupport identityBoundary,
                                      SpanEnricher spanEnricher) {
        this.identityBoundary = Objects.requireNonNull(identityBoundary, "identityBoundary must not be null");
        this.spanEnricher = Objects.requireNonNull(spanEnricher, "spanEnricher must not be null");
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Around("@annotation(org.springframework.kafka.annotation.KafkaListener)")
    public Object bindRequestIdentity(ProceedingJoinPoint joinPoint) throws Throwable {
        ConsumerRecord<?, ?> record = singleRecord(joinPoint.getArgs());
        if (record == null) {
            return joinPoint.proceed();
        }

        String requestId = resolveAndAttach(record);
        try (CorrelationScope ignored = identityBoundary.openRequestScope(requestId)) {
            seedRequestId(requestId);
            return joinPoint.proceed();
        }
    }

    private void seedRequestId(String requestId) {
        try {
            spanEnricher.enrichCurrentSpan(enrichment -> enrichment.requestId(requestId));
        } catch (RuntimeException failure) {
            if (seedFailureLogged.compareAndSet(false, true)) {
                log.warn("Failed to seed request id on the current span: {}", failure.getClass().getName());
            }
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
        String incoming = (header == null || header.value() == null)
                ? null
                : new String(header.value(), StandardCharsets.UTF_8);

        String requestId = RequestIdSupport.resolve(incoming);
        if (!requestId.equals(incoming)) {
            record.headers()
                    .remove(PlatformHeaders.X_REQUEST_ID)
                    .add(PlatformHeaders.X_REQUEST_ID, requestId.getBytes(StandardCharsets.UTF_8));
        }

        return requestId;
    }
}
