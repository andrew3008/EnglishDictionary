package space.br1440.platform.tracing.otel.javaagent.bootstrap.failure;

import java.util.Objects;

public final class FailureSite {

    private static final String PREPARE_PHASE = "prepare";
    private static final String CUSTOMIZATION_PHASE = "customization";
    private static final String STARTUP_PHASE = "startup";
    private static final String ACTIVATE_PHASE = "activate";

    private final String phase;
    private final String subphase;
    private final String propertyName;

    private FailureSite(String phase, String subphase, String propertyName) {
        this.phase = Objects.requireNonNull(phase, "phase");
        this.subphase = Objects.requireNonNull(subphase, "subphase");
        this.propertyName = propertyName;
    }

    public static FailureSite prepareReadinessPublication() {
        return new FailureSite(PREPARE_PHASE, "readiness-publication", null);
    }

    public static FailureSite prepareConfiguration() {
        return new FailureSite(PREPARE_PHASE, "configuration-initialization", null);
    }

    public static FailureSite prepareValidation(String propertyName) {
        return new FailureSite(
                PREPARE_PHASE,
                "configuration-validation",
                Objects.requireNonNull(propertyName, "propertyName")
        );
    }

    public static FailureSite prepareDiagnosticRegistration() {
        return new FailureSite(PREPARE_PHASE, "diagnostic-registration", null);
    }

    public static FailureSite customization(CustomizationStep step) {
        return new FailureSite(CUSTOMIZATION_PHASE, Objects.requireNonNull(step, "step").subphase(), null);
    }

    public static FailureSite startup() {
        return new FailureSite(STARTUP_PHASE, STARTUP_PHASE, null);
    }

    public static FailureSite activation(String subphase) {
        return new FailureSite(ACTIVATE_PHASE, subphase, null);
    }

    public String phase() {
        return phase;
    }

    public String subphase() {
        return subphase;
    }

    String propertyName() {
        return propertyName;
    }
}
