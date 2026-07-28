package space.br1440.platform.tracing.otel.javaagent.control.state;

import java.util.Objects;

record PolicyValidationResult(boolean valid, PolicyMessageCode messageCode) {

    PolicyValidationResult {
        Objects.requireNonNull(messageCode, "messageCode");
        if (valid != (messageCode == PolicyMessageCode.NONE)) {
            throw new IllegalArgumentException("Validation result and message code are inconsistent");
        }
    }

    static PolicyValidationResult accepted() {
        return new PolicyValidationResult(true, PolicyMessageCode.NONE);
    }

    static PolicyValidationResult rejected(PolicyMessageCode messageCode) {
        if (messageCode == PolicyMessageCode.NONE) {
            throw new IllegalArgumentException("Rejected validation requires a message code");
        }
        return new PolicyValidationResult(false, messageCode);
    }
}
