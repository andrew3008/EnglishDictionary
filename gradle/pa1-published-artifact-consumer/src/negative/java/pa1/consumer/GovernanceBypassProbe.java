package pa1.consumer;

import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.otel.span.spec.DefaultSpanSpecFactory;

/**
 * Negative compile probe: governance bypass {@code executionFromGovernedSpec} не должен существовать.
 */
final class GovernanceBypassProbe {

    private void bypass(DefaultSpanSpecFactory factory, SpanSpec spec) {
        factory.executionFromGovernedSpec(spec);
    }
}
