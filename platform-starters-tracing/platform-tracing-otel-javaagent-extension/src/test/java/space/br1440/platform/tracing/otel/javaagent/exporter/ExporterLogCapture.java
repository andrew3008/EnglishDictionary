package space.br1440.platform.tracing.otel.javaagent.exporter;

import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

final class ExporterLogCapture extends Handler implements AutoCloseable {

    private final Logger logger;
    private final Level previousLevel;
    private final List<String> messages;
    private final SimpleFormatter formatter = new SimpleFormatter();

    ExporterLogCapture(Class<?> exporterType, List<String> messages) {
        this.logger = Logger.getLogger(exporterType.getName());
        this.previousLevel = logger.getLevel();
        this.messages = messages;
        logger.addHandler(this);
        logger.setLevel(Level.ALL);
    }

    @Override
    public void publish(LogRecord record) {
        messages.add(formatter.formatMessage(record));
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
        logger.removeHandler(this);
        logger.setLevel(previousLevel);
    }
}
