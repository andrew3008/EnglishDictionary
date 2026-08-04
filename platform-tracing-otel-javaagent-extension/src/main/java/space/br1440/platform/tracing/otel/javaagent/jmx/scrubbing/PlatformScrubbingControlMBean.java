package space.br1440.platform.tracing.otel.javaagent.jmx.scrubbing;

import javax.management.openmbean.CompositeData;

public interface PlatformScrubbingControlMBean {

    CompositeData getScrubbingObservation();

}
