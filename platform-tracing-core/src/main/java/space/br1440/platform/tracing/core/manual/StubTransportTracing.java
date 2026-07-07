package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.manual.DatabaseSpanBuilder;
import space.br1440.platform.tracing.api.manual.DatabaseTracing;
import space.br1440.platform.tracing.api.manual.HttpClientSpanBuilder;
import space.br1440.platform.tracing.api.manual.HttpServerSpanBuilder;
import space.br1440.platform.tracing.api.manual.HttpTracing;
import space.br1440.platform.tracing.api.manual.KafkaConsumerSpanBuilder;
import space.br1440.platform.tracing.api.manual.KafkaProducerSpanBuilder;
import space.br1440.platform.tracing.api.manual.KafkaTracing;
import space.br1440.platform.tracing.api.manual.RpcClientSpanBuilder;
import space.br1440.platform.tracing.api.manual.RpcServerSpanBuilder;
import space.br1440.platform.tracing.api.manual.RpcTracing;
import space.br1440.platform.tracing.api.manual.TransportTracing;

/**
 * Slice 1A/1B skeleton transport grouping; semantic builders arrive in Slice 3A–3C.
 */
final class StubTransportTracing implements TransportTracing {

    static final StubTransportTracing INSTANCE = new StubTransportTracing();

    private StubTransportTracing() {
    }

    @Override
    @Nonnull
    public HttpTracing http() {
        return StubHttpTracing.INSTANCE;
    }

    @Override
    @Nonnull
    public DatabaseTracing database() {
        return StubDatabaseTracing.INSTANCE;
    }

    @Override
    @Nonnull
    public RpcTracing rpc() {
        return StubRpcTracing.INSTANCE;
    }

    @Override
    @Nonnull
    public KafkaTracing kafka() {
        return StubKafkaTracing.INSTANCE;
    }
}

final class StubHttpTracing implements HttpTracing {
    static final StubHttpTracing INSTANCE = new StubHttpTracing();

    private StubHttpTracing() {
    }

    @Override
    @Nonnull
    public HttpServerSpanBuilder server() {
        throw new UnsupportedOperationException("HTTP server builder is unavailable in noop transport stub");
    }

    @Override
    @Nonnull
    public HttpClientSpanBuilder client() {
        throw new UnsupportedOperationException("HTTP client builder is unavailable in noop transport stub");
    }
}

final class StubDatabaseTracing implements DatabaseTracing {
    static final StubDatabaseTracing INSTANCE = new StubDatabaseTracing();

    private StubDatabaseTracing() {
    }

    private UnsupportedOperationException unavailable() {
        return new UnsupportedOperationException("Database builder is unavailable in noop transport stub");
    }

    @Override
    @Nonnull
    public DatabaseSpanBuilder system(@Nonnull String dbSystem) {
        throw unavailable();
    }

    @Override
    @Nonnull
    public DatabaseSpanBuilder operation(@Nonnull String operation) {
        throw unavailable();
    }

    @Override
    @Nonnull
    public DatabaseSpanBuilder collection(@Nonnull String collection) {
        throw unavailable();
    }

    @Override
    @Nonnull
    public DatabaseSpanBuilder child() {
        throw unavailable();
    }

    @Override
    @Nonnull
    public DatabaseSpanBuilder root() {
        throw unavailable();
    }

    @Override
    @Nonnull
    public DatabaseSpanBuilder detached() {
        throw unavailable();
    }

    @Override
    @Nonnull
    public DatabaseSpanBuilder linkedTo(@Nonnull space.br1440.platform.tracing.api.span.SpanLinkContext... linkContexts) {
        throw unavailable();
    }

    @Override
    @Nonnull
    public DatabaseSpanBuilder fromRemoteContext(@Nonnull String... traceparents) {
        throw unavailable();
    }

    @Override
    @Nonnull
    public space.br1440.platform.tracing.api.span.spec.SpanHandle start() {
        throw unavailable();
    }

    @Override
    public void run(@Nonnull Runnable action) {
        throw unavailable();
    }

    @Override
    @Nonnull
    public <T> T call(@Nonnull java.util.function.Supplier<T> supplier) {
        throw unavailable();
    }

    @Override
    @Nonnull
    public <T> T callChecked(@Nonnull space.br1440.platform.tracing.api.util.ThrowingSupplier<T> supplier)
            throws Exception {
        throw unavailable();
    }
}

final class StubRpcTracing implements RpcTracing {
    static final StubRpcTracing INSTANCE = new StubRpcTracing();

    private StubRpcTracing() {
    }

    @Override
    @Nonnull
    public RpcServerSpanBuilder server() {
        throw new UnsupportedOperationException("RPC server builder is unavailable in noop transport stub");
    }

    @Override
    @Nonnull
    public RpcClientSpanBuilder client() {
        throw new UnsupportedOperationException("RPC client builder is unavailable in noop transport stub");
    }
}

final class StubKafkaTracing implements KafkaTracing {
    static final StubKafkaTracing INSTANCE = new StubKafkaTracing();

    private StubKafkaTracing() {
    }

    @Override
    @Nonnull
    public KafkaProducerSpanBuilder producer() {
        throw new UnsupportedOperationException("Kafka producer builder is unavailable in noop transport stub");
    }

    @Override
    @Nonnull
    public KafkaConsumerSpanBuilder consumer() {
        throw new UnsupportedOperationException("Kafka consumer builder is unavailable in noop transport stub");
    }
}
