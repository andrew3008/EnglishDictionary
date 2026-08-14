package space.br1440.platform.tracing.otel.javaagent.processor;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.opentelemetry.sdk.trace.SpanProcessor;

import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.IpPrefixTruncator;

class ProcessorDefaultLifecycleContractTest {

    private static final List<SpanProcessor> STATELESS_LIFECYCLE_PROCESSORS = List.of(
            new BaggageSpanProcessor(Set.of(), List.of()),
            new EnrichingSpanProcessor(),
            new ScrubbingSpanProcessor(List.of()),
            new ValidatingSpanProcessor(TracingSettingsSource.isolatedValidation(true, false, false)),
            new ClassificationSpanProcessor(Duration.ofSeconds(5), Duration.ofSeconds(1))
    );

    @Test
    void statelessProcessorsInheritSuccessfulOtelLifecycleDefaults() throws NoSuchMethodException {
        for (SpanProcessor processor : STATELESS_LIFECYCLE_PROCESSORS) {
            Class<?> processorType = processor.getClass();

            assertThat(processorType.getMethod("forceFlush").getDeclaringClass())
                    .as("forceFlush owner for %s", processorType.getSimpleName())
                    .isEqualTo(SpanProcessor.class);
            assertThat(processorType.getMethod("shutdown").getDeclaringClass())
                    .as("shutdown owner for %s", processorType.getSimpleName())
                    .isEqualTo(SpanProcessor.class);
            assertThat(processor.forceFlush().isSuccess())
                    .as("forceFlush result for %s", processorType.getSimpleName())
                    .isTrue();
            assertThat(processor.shutdown().isSuccess())
                    .as("shutdown result for %s", processorType.getSimpleName())
                    .isTrue();
        }
    }

    @Test
    void classificationProcessorIsFinal() {
        assertThat(Modifier.isFinal(ClassificationSpanProcessor.class.getModifiers())).isTrue();
    }

    @Test
    void ipPrefixTruncatorResidesInScrubbingEnginePackage() {
        assertThat(IpPrefixTruncator.class.getPackageName())
                .isEqualTo("space.br1440.platform.tracing.otel.javaagent.scrubbing.engine");
        assertThatThrownBy(() -> Class.forName(
                "space.br1440.platform.tracing.otel.javaagent.processor.utils.IpPrefixTruncator"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
