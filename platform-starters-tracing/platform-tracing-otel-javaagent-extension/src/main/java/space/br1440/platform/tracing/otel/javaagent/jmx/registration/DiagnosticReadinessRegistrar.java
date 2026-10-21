package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import java.util.Objects;
import java.util.function.Supplier;

import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessDynamicMBean;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;

final class DiagnosticReadinessRegistrar {

    private final MBeanRegistrationScope scope;
    private MBeanBatchRegistrar.RegisteredBatch batch;

    DiagnosticReadinessRegistrar(MBeanRegistrationScope scope) {
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    AutoCloseable register(Supplier<ExtensionReadinessWireSnapshot> projection) {
        Objects.requireNonNull(projection, "projection");

        return scope.callSerialized(() -> {
            if (batch != null) {
                throw new IllegalStateException("PlatformTracingMBeanRegistrar already has an active readiness registration");
            }

            batch = scope.registerOne(MBeanBatchRegistrar.Registration.dynamic(
                    DomainObjectNames.EXTENSION_READINESS,
                    new ExtensionReadinessDynamicMBean(projection)
            ));

            return new Handle();
        });
    }

    private void closeLocked() {
        if (batch == null) {
            scope.reclaimUnreclaimed();
            return;
        }

        try {
            batch.close();
        } finally {
            batch = null;
        }

        scope.reclaimUnreclaimed();
    }

    private final class Handle implements AutoCloseable {

        private boolean closed;

        @Override
        public void close() {
            scope.runSerialized(() -> {
                if (closed) {
                    return;
                }

                closed = true;
                closeLocked();
            });
        }
    }
}
