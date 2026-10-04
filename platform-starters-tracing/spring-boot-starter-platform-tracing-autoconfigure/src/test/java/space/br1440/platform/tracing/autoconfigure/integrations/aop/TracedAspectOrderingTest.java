package space.br1440.platform.tracing.autoconfigure.integrations.aop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.core.Ordered;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.annotation.Traced;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.autoconfigure.integrations.kafka.KafkaRequestIdentityAspect;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.span.enrich.DefaultSpanEnricher;
import space.br1440.platform.tracing.test.TraceOperationsTestExtension;

/**
 * Порядок advice в {@link AspectJProxyFactory}.
 * {@link TransactionBoundaryAspect} — stub с {@link Ordered#LOWEST_PRECEDENCE}, не {@code TransactionInterceptor}.
 * Порядок production beans относительно настоящего transaction advice доказывает
 * {@code KafkaRequestIdentityOrderAndSeedTest}.
 */
@ExtendWith(TraceOperationsTestExtension.class)
class TracedAspectOrderingTest {

    @Test
    void tracedAspectIsOuterRelativeToLowestPrecedenceStub(TraceOperations tracing) {
        // given
        List<String> events = new ArrayList<>();
        RecordingTransactionManager txManager = new RecordingTransactionManager(events);
        AspectJProxyFactory factory = new AspectJProxyFactory(new TxService(events));
        factory.addAspect(new RecordingTracedAspect(new TracedAspect(tracing), events));
        factory.addAspect(new TransactionBoundaryAspect(txManager, events));
        TxService proxy = factory.getProxy();

        // when
        proxy.work();

        // then
        assertThat(events).containsExactly(
                "traced:enter",
                "tx:begin",
                "business",
                "tx:commit",
                "traced:exit");
    }

    @Test
    void tracedAspectIsOuterRelativeToKafkaRequestIdentityAspect(TraceOperations tracing) {
        // given
        List<String> events = new ArrayList<>();
        TracingRuntime runtime = NoOpTracingRuntime.noop();
        KafkaRequestIdentityAspect kafkaAspect = new KafkaRequestIdentityAspect(
                new RequestIdentityBoundarySupport(runtime),
                new DefaultSpanEnricher()
        );
        AspectJProxyFactory factory = new AspectJProxyFactory(new KafkaListenerService(events));
        factory.addAspect(new RecordingTracedAspect(new TracedAspect(tracing), events));
        factory.addAspect(new RecordingKafkaAspect(kafkaAspect, events));
        KafkaListenerService proxy = factory.getProxy();
        ConsumerRecord<String, String> record = new ConsumerRecord<>("t", 0, 0L, "k", "v");

        // when
        proxy.onMessage(record);

        // then
        assertThat(events).containsExactly(
                "traced:enter",
                "kafka:enter",
                "business",
                "kafka:exit",
                "traced:exit");
    }

    @Test
    void tracedAspectRecordsExceptionThatRollsBackTransaction(TraceOperations tracing) {
        // given
        List<String> events = new ArrayList<>();
        RecordingTransactionManager txManager = new RecordingTransactionManager(events);
        AspectJProxyFactory factory = new AspectJProxyFactory(new FailingTxService());
        factory.addAspect(new RecordingTracedAspect(new TracedAspect(tracing), events));
        factory.addAspect(new TransactionBoundaryAspect(txManager, events));
        FailingTxService proxy = factory.getProxy();

        // when
        assertThatThrownBy(proxy::fail)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rollback");

        // then
        assertThat(events).containsExactly(
                "traced:enter",
                "tx:begin",
                "tx:rollback",
                "traced:exit");
    }

    static class TxService {
        private final List<String> events;

        TxService(List<String> events) {
            this.events = events;
        }

        @Traced("tx.work")
        @Transactional
        public void work() {
            events.add("business");
        }
    }

    static class FailingTxService {
        @Traced("tx.fail")
        @Transactional
        public void fail() {
            throw new IllegalStateException("rollback");
        }
    }

    static class KafkaListenerService {
        private final List<String> events;

        KafkaListenerService(List<String> events) {
            this.events = events;
        }

        @Traced("kafka.work")
        @KafkaListener(topics = "t")
        public void onMessage(@SuppressWarnings("unused") ConsumerRecord<String, String> record) {
            events.add("business");
        }
    }

    /**
     * Записывает enter/exit вокруг делегирования в реальный {@link TracedAspect}, сохраняя его order.
     */
    @Aspect
    static final class RecordingTracedAspect implements Ordered {
        private final TracedAspect delegate;
        private final List<String> events;

        RecordingTracedAspect(TracedAspect delegate, List<String> events) {
            this.delegate = delegate;
            this.events = events;
        }

        @Around("@annotation(space.br1440.platform.tracing.api.annotation.Traced) "
                + "|| @within(space.br1440.platform.tracing.api.annotation.Traced)")
        public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
            events.add("traced:enter");
            try {
                return delegate.aroundTraced(joinPoint);
            } finally {
                events.add("traced:exit");
            }
        }

        @Override
        public int getOrder() {
            return delegate.getOrder();
        }
    }

    /**
     * Записывает enter/exit вокруг {@link KafkaRequestIdentityAspect}, сохраняя его order.
     */
    @Aspect
    static final class RecordingKafkaAspect implements Ordered {
        private final KafkaRequestIdentityAspect delegate;
        private final List<String> events;

        RecordingKafkaAspect(KafkaRequestIdentityAspect delegate, List<String> events) {
            this.delegate = delegate;
            this.events = events;
        }

        @Around("@annotation(org.springframework.kafka.annotation.KafkaListener)")
        public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
            events.add("kafka:enter");
            try {
                return delegate.bindRequestIdentity(joinPoint);
            } finally {
                events.add("kafka:exit");
            }
        }

        @Override
        public int getOrder() {
            return delegate.getOrder();
        }
    }

    /**
     * Stub transaction advice с {@link Ordered#LOWEST_PRECEDENCE}.
     */
    @Aspect
    static final class TransactionBoundaryAspect implements Ordered {
        private final PlatformTransactionManager txManager;
        private final List<String> events;

        TransactionBoundaryAspect(PlatformTransactionManager txManager, List<String> events) {
            this.txManager = txManager;
            this.events = events;
        }

        @Around("@annotation(org.springframework.transaction.annotation.Transactional)")
        public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
            events.add("tx:begin");
            TransactionStatus status = txManager.getTransaction(TransactionDefinition.withDefaults());
            try {
                Object result = joinPoint.proceed();
                txManager.commit(status);
                return result;
            } catch (Throwable t) {
                txManager.rollback(status);
                throw t;
            }
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }

    static final class RecordingTransactionManager implements PlatformTransactionManager {
        private final List<String> events;

        RecordingTransactionManager(List<String> events) {
            this.events = events;
        }

        @Override
        public @NotNull TransactionStatus getTransaction(TransactionDefinition definition)
                throws TransactionException {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(@NotNull TransactionStatus status) throws TransactionException {
            events.add("tx:commit");
        }

        @Override
        public void rollback(@NotNull TransactionStatus status) throws TransactionException {
            events.add("tx:rollback");
        }
    }
}
