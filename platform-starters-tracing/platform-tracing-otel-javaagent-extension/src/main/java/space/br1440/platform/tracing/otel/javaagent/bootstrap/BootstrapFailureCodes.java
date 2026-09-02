package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import lombok.experimental.UtilityClass;

@UtilityClass
final class BootstrapFailureCodes {

    static final String FAILED_ROLLBACK_INCOMPLETE = "FAILED_ROLLBACK_INCOMPLETE";

    static final String TRANSPORT_STALE = "JMX_TRANSPORT_STALE";

}
