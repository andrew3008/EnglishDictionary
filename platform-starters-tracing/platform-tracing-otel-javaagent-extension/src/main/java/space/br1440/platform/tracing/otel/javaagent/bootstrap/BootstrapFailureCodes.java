package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import lombok.experimental.UtilityClass;

@UtilityClass
final class BootstrapFailureCodes {

    static final String CONFIGURATION_INITIALIZATION_FAILED = "CONFIGURATION_INITIALIZATION_FAILED";

    static final String PLATFORM_ACP_NOT_EXECUTED = "PLATFORM_ACP_NOT_EXECUTED";

    static final String DIAGNOSTIC_MBEAN_REGISTRATION_FAILED = "DIAGNOSTIC_MBEAN_REGISTRATION_FAILED";

    static final String PLATFORM_SAMPLER_REPLACED = "PLATFORM_SAMPLER_REPLACED";

    static final String ACTIVATION_FAILED = "ACTIVATION_FAILED";

    static final String FAILED_ROLLBACK_INCOMPLETE = "FAILED_ROLLBACK_INCOMPLETE";

    static final String TRANSPORT_STALE = "JMX_TRANSPORT_STALE";

}
