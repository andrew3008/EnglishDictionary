package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsValidationConstraints;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class TracingSettingsValidator {

    static SettingsRejectionReason validate(TracingSettings policy,
                                            SettingsValidationConstraints constraints) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(constraints, "constraints");

        if (policy.validationStrict() && !constraints.strictRuntimeAllowed()) {
            return SettingsRejectionReason.STRICT_RUNTIME_NOT_ALLOWED;
        }

        return SettingsRejectionReason.NONE;
    }
}
