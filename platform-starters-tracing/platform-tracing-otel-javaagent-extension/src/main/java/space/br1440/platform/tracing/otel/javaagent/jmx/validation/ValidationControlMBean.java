package space.br1440.platform.tracing.otel.javaagent.jmx.validation;

import javax.management.openmbean.CompositeData;

public interface ValidationControlMBean {

    CompositeData getValidationObservation();

}
