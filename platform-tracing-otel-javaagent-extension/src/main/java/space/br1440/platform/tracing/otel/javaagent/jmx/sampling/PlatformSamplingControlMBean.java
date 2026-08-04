package space.br1440.platform.tracing.otel.javaagent.jmx.sampling;

import javax.management.openmbean.CompositeData;

public interface PlatformSamplingControlMBean {

    CompositeData getSamplingObservation();

}
