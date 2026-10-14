package space.br1440.platform.tracing.autoconfigure.control.jmx;

import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsContractViolationException;

interface MutationFailureSink {

    TracingConfigurationGuardException terminalGuardFailure(JmxClientFailureCode code,
                                                            String message,
                                                            Throwable cause);

    TracingSettingsContractViolationException terminalContractViolation(JmxClientFailureCode code,
                                                                        String message,
                                                                        Throwable cause);

    void invalidateAndNotify(JmxClientFailureCode code, Throwable primaryFailure);

    void publishTerminalFailure(String failureCode, Throwable primaryFailure);

}
