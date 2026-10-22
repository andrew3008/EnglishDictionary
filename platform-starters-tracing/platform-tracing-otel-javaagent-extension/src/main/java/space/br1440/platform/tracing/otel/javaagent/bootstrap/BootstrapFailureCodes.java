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

    static final String SHUTDOWN_CLEANUP_FAILED = "SHUTDOWN_CLEANUP_FAILED";

    static final String REENTRANT_OR_OUT_OF_ORDER = "REENTRANT_OR_OUT_OF_ORDER";

    static final String SECOND_SDK_BUILD = "SECOND_SDK_BUILD";

    static final String DUPLICATE_ACTIVATION = "DUPLICATE_ACTIVATION";

    static final String BOOTSTRAP_TERMINAL = "BOOTSTRAP_TERMINAL";

}
