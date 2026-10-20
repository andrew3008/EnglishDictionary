package space.br1440.platform.tracing.otel.javaagent.bootstrap.verification;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class VerificationTestFixtures {

    public static VerificationException failure(VerificationFailureCode code) {
        return new VerificationException(code, "Simulated runtime verification failure");
    }
}
