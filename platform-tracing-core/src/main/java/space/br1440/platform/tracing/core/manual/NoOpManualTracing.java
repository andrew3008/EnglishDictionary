package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.manual.ManualTracing;
import space.br1440.platform.tracing.api.manual.OperationSpanBuilder;
import space.br1440.platform.tracing.api.manual.TransportTracing;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpecifiedSpan;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;

import java.util.Objects;
import java.util.function.Supplier;

public final class NoOpManualTracing implements ManualTracing {

    public static final NoOpManualTracing INSTANCE = new NoOpManualTracing();

    private NoOpManualTracing() {
    }

    @Override
    @Nonnull
    public OperationSpanBuilder operation(@Nonnull String name) {
        return NoOpOperationSpanBuilder.INSTANCE;
    }

    @Override
    @Nonnull
    public TransportTracing transport() {
        return StubTransportTracing.INSTANCE;
    }

    @Override
    @Nonnull
    public SpecifiedSpan spanFromSpec(@Nonnull SpanSpec spec) {
        return NoOpSpecifiedSpan.INSTANCE;
    }
}

final class NoOpOperationSpanBuilder implements OperationSpanBuilder {

    static final NoOpOperationSpanBuilder INSTANCE = new NoOpOperationSpanBuilder();

    private NoOpOperationSpanBuilder() {
    }

    @Override
    @Nonnull
    public OperationSpanBuilder child() {
        return this;
    }

    @Override
    @Nonnull
    public OperationSpanBuilder root() {
        return this;
    }

    @Override
    @Nonnull
    public OperationSpanBuilder detached() {
        return this;
    }

    @Override
    @Nonnull
    public OperationSpanBuilder linkedTo(@Nonnull space.br1440.platform.tracing.api.span.SpanLinkContext... links) {
        return this;
    }

    @Override
    @Nonnull
    public OperationSpanBuilder fromRemoteContext(@Nonnull String... traceparents) {
        return this;
    }

    @Override
    @Nonnull
    public SpanHandle start() {
        return NoOpSpanHandle.INSTANCE;
    }

    @Override
    public void run(@Nonnull Runnable action) {
        Objects.requireNonNull(action, "action").run();
    }

    @Override
    @Nonnull
    public <T> T call(@Nonnull Supplier<T> supplier) {
        return Objects.requireNonNull(supplier, "supplier").get();
    }

    @Override
    @Nonnull
    public <T> T callChecked(@Nonnull ThrowingSupplier<T> supplier) throws Exception {
        return Objects.requireNonNull(supplier, "supplier").get();
    }
}

final class NoOpSpecifiedSpan implements SpecifiedSpan {

    static final NoOpSpecifiedSpan INSTANCE = new NoOpSpecifiedSpan();

    private NoOpSpecifiedSpan() {
    }

    @Override
    @Nonnull
    public SpanHandle start() {
        return NoOpSpanHandle.INSTANCE;
    }

    @Override
    public void run(@Nonnull Runnable action) {
        Objects.requireNonNull(action, "action").run();
    }

    @Override
    @Nonnull
    public <T> T call(@Nonnull Supplier<T> supplier) {
        return Objects.requireNonNull(supplier, "supplier").get();
    }

    @Override
    @Nonnull
    public <T> T callChecked(@Nonnull ThrowingSupplier<T> supplier) throws Exception {
        return Objects.requireNonNull(supplier, "supplier").get();
    }
}

