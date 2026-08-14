package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export;

import java.util.Map;

public record SafeExporterValues(boolean exportEnabled,
                                 long lastExportDurationNanos,
                                 Map<String, Long> metrics) {
}
