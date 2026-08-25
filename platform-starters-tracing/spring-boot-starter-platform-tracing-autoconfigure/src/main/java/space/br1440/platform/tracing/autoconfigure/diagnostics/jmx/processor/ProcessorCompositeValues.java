package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor;

import java.util.Map;

public record ProcessorCompositeValues(long errorsTotal,
                                       Map<String, Long> errorsByName) {
}
