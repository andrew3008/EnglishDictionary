package space.br1440.platform.tracing.otel.javaagent.bootstrap.failure;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.verification.VerificationFailureCode;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.SanitizerBootstrapException;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class BootstrapFailureCodes {

    public static final String CONFIGURATION_INITIALIZATION_FAILED = "CONFIGURATION_INITIALIZATION_FAILED";

    public static final String PLATFORM_ACP_NOT_EXECUTED = "PLATFORM_ACP_NOT_EXECUTED";

    public static final String DIAGNOSTIC_MBEAN_REGISTRATION_FAILED = "DIAGNOSTIC_MBEAN_REGISTRATION_FAILED";

    public static final String PLATFORM_SAMPLER_REPLACED = "PLATFORM_SAMPLER_REPLACED";

    public static final String ACTIVATION_FAILED = "ACTIVATION_FAILED";

    public static final String AGENT_STARTUP_FAILED = "AGENT_STARTUP_FAILED";

    public static final String FAILED_ROLLBACK_INCOMPLETE = "FAILED_ROLLBACK_INCOMPLETE";

    public static final String SHUTDOWN_CLEANUP_FAILED = "SHUTDOWN_CLEANUP_FAILED";

    public static final String REENTRANT_OR_OUT_OF_ORDER = "REENTRANT_OR_OUT_OF_ORDER";

    public static final String SECOND_SDK_BUILD = "SECOND_SDK_BUILD";

    public static final String DUPLICATE_ACTIVATION = "DUPLICATE_ACTIVATION";

    public static final String BOOTSTRAP_TERMINAL = "BOOTSTRAP_TERMINAL";

    public static String classify(Throwable failure, String fallbackCode) {
        return switch (failure) {
            case LinkageError ignored -> VerificationFailureCode.EXTENSION_LINKAGE_ERROR.name();
            case SanitizerBootstrapException sanitizer -> sanitizer.code();
            case BootstrapAdmissionRejectedException rejection -> rejection.primaryCode();
            default -> fallbackCode;
        };
    }
}
