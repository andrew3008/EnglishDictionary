package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.logs.ConfigurableLogRecordExporterProvider;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;

public final class FailingLogRecordExporterProvider implements ConfigurableLogRecordExporterProvider {

    static final String NAME = "platform-test-failing-log-exporter";

    @Override
    public LogRecordExporter createExporter(ConfigProperties config) {
        throw new IllegalStateException("Failing log record exporter");
    }

    @Override
    public String getName() {
        return NAME;
    }
}
