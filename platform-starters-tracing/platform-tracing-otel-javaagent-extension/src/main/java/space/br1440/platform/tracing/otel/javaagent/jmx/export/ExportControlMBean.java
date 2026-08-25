package space.br1440.platform.tracing.otel.javaagent.jmx.export;

import javax.management.openmbean.CompositeData;

public interface ExportControlMBean {

    CompositeData getExportObservation();

}
