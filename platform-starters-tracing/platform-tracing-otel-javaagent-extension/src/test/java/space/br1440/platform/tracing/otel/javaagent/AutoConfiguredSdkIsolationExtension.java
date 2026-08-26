package space.br1440.platform.tracing.otel.javaagent;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public final class AutoConfiguredSdkIsolationExtension
        implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        SharedBootstrapBindingsReset.reset();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        SharedBootstrapBindingsReset.reset();
    }
}
