package space.br1440.platform.tracing.test.junit;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import space.br1440.platform.tracing.test.junit.internal.ScopeMode;
import space.br1440.platform.tracing.test.junit.internal.SdkResource;
import space.br1440.platform.tracing.test.junit.internal.StoreKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class OtelSdkExtension implements BeforeAllCallback, BeforeEachCallback, AfterAllCallback, ParameterResolver {

    private static final Namespace NAMESPACE = Namespace.create(OtelSdkExtension.class);

    private final ScopeMode scope;
    private final Sampler sampler;
    private final List<SpanProcessor> extraProcessors;

    private OtelSdkExtension(ScopeMode scope, @Nullable Sampler sampler, List<SpanProcessor> extraProcessors) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.sampler = sampler;
        this.extraProcessors = extraProcessors;
    }

    public OtelSdkExtension() {
        this(ScopeMode.METHOD, null, List.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static OtelSdkExtension methodScope() {
        return builder().scope(ScopeMode.METHOD).build();
    }

    public static OtelSdkExtension classScope() {
        return builder().scope(ScopeMode.CLASS).build();
    }

    public static OtelSdkExtension sharedAcrossNested() {
        return builder().scope(ScopeMode.SHARED_NESTED).build();
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        if (scope.isClassScoped()) {
            getOrCreateSdkResource(context);
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        SdkResource resource = getOrCreateSdkResource(context);
        resource.resetBetweenTestsIfNeeded();
    }

    @Override
    public void afterAll(ExtensionContext context) {
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        Class<?> type = parameterContext.getParameter().getType();
        return type == OpenTelemetrySdk.class
                || type == InMemorySpanExporter.class
                || type == Sampler.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return resolveByType(parameterContext.getParameter().getType(), extensionContext);
    }

    public Object resolveByType(Class<?> type, ExtensionContext extensionContext) {
        SdkResource resource = getOrCreateSdkResource(extensionContext);

        if (type == OpenTelemetrySdk.class) {
            return resource.sdk();
        }

        if (type == InMemorySpanExporter.class) {
            return resource.exporter();
        }

        if (type == Sampler.class) {
            if (sampler == null) {
                throw new ParameterResolutionException("Sampler is not configured in OtelSdkExtension builder — Sampler parameter cannot be resolved");
            }

            return sampler;
        }

        throw new ParameterResolutionException("Unsupported parameter type: " + type);
    }

    private SdkResource getOrCreateSdkResource(ExtensionContext context) {
        ExtensionContext target = scope.isClassScoped() ? rootClassContext(context) : context;

        Store store = target.getStore(NAMESPACE);
        return store.getOrComputeIfAbsent(
                StoreKeys.SDK,
                k -> SdkResource.build(scope, sampler, extraProcessors),
                SdkResource.class);
    }

    private static ExtensionContext rootClassContext(ExtensionContext context) {
        ExtensionContext current = context;
        while (current.getParent().isPresent()
                && current.getParent().get().getTestClass().isPresent()) {
            current = current.getParent().get();
        }

        return current;
    }

    public static final class Builder {

        private ScopeMode scope;
        private Sampler sampler;
        private final List<SpanProcessor> extraProcessors = new ArrayList<>();

        private Builder() {
        }

        public Builder scope(ScopeMode scope) {
            this.scope = Objects.requireNonNull(scope, "scope");
            return this;
        }

        public Builder sampler(Sampler sampler) {
            this.sampler = Objects.requireNonNull(sampler, "sampler");
            return this;
        }

        public Builder addSpanProcessor(SpanProcessor processor) {
            this.extraProcessors.add(Objects.requireNonNull(processor, "processor"));
            return this;
        }

        public OtelSdkExtension build() {
            if (scope == null) {
                throw new IllegalStateException(
                        """
                          OtelSdkExtension.Builder: scope is required.
                          Use one of the following:
                            - OtelSdkExtension.methodScope()
                            - OtelSdkExtension.classScope()
                            - OtelSdkExtension.sharedAcrossNested()
                            - OtelSdkExtension.builder().scope(...).build()
                        """);
            }

            return new OtelSdkExtension(scope, sampler, SdkResource.defensiveCopy(extraProcessors));
        }
    }
}
