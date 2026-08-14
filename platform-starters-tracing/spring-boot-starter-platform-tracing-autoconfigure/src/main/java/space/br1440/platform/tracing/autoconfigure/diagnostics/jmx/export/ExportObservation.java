package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;

public record ExportObservation(String agentInstanceId,
                                JmxOwnerState<ExportProcessorValues> processor,
                                JmxOwnerState<SafeExporterValues> exporter) {
}
