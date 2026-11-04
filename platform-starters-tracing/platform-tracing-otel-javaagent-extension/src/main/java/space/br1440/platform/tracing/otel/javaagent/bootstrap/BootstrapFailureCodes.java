package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.verification.VerificationFailureCode;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.SanitizerBootstrapException;

import lombok.experimental.UtilityClass;

@UtilityClass
final class BootstrapFailureCodes {

    static final String CONFIGURATION_INITIALIZATION_FAILED = "CONFIGURATION_INITIALIZATION_FAILED";

    static final String PLATFORM_ACP_NOT_EXECUTED = "PLATFORM_ACP_NOT_EXECUTED";

    static final String DIAGNOSTIC_MBEAN_REGISTRATION_FAILED = "DIAGNOSTIC_MBEAN_REGISTRATION_FAILED";

    static final String PLATFORM_SAMPLER_REPLACED = "PLATFORM_SAMPLER_REPLACED";

    static final String ACTIVATION_FAILED = "ACTIVATION_FAILED";

    static final String AGENT_STARTUP_FAILED = "AGENT_STARTUP_FAILED";

    static final String FAILED_ROLLBACK_INCOMPLETE = "FAILED_ROLLBACK_INCOMPLETE";

    static final String SHUTDOWN_CLEANUP_FAILED = "SHUTDOWN_CLEANUP_FAILED";

    static final String REENTRANT_OR_OUT_OF_ORDER = "REENTRANT_OR_OUT_OF_ORDER";

    static final String SECOND_SDK_BUILD = "SECOND_SDK_BUILD";

    static final String DUPLICATE_ACTIVATION = "DUPLICATE_ACTIVATION";

    static final String BOOTSTRAP_TERMINAL = "BOOTSTRAP_TERMINAL";

    /**
     * Классифицирует отказ допущенной операции. Отказ в допуске вложенного вызова, прервавший внешнюю
     * операцию, сохраняет свой код.
     */
    static String classify(Throwable failure, String fallbackCode) {
        return switch (failure) {
            case LinkageError ignored -> VerificationFailureCode.EXTENSION_LINKAGE_ERROR.name();
            case SanitizerBootstrapException sanitizer -> sanitizer.code();
            case BootstrapAdmissionRejectedException rejection -> rejection.primaryCode();
            default -> fallbackCode;
        };
    }
}
