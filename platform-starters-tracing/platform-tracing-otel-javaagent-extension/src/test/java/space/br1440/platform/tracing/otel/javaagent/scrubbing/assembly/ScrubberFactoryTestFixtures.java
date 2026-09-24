package space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly;

import java.util.List;
import java.util.function.Supplier;

import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;

public final class ScrubberFactoryTestFixtures {

    private ScrubberFactoryTestFixtures() {
    }

    public static PlatformScrubberFactory factory(Supplier<List<SpanAttributeScrubbingRule>> builtInRulesSupplier) {
        return new PlatformScrubberFactory(builtInRulesSupplier);
    }
}
