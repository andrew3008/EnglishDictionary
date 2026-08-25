package space.br1440.platform.tracing.otel.javaagent.jmx.diagnostics;

import javax.management.openmbean.CompositeData;

public interface DiagnosticsControlMBean {

    CompositeData getDiagnosticsObservation();

}
