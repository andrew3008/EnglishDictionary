package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;

public record SettingsUpdateResult(SettingsUpdateStatus outcome,
                                   TracingSettingsRevision accepted,
                                   SettingsRejectionReason messageCode) {

    public SettingsUpdateResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(messageCode, "messageCode");

        if (isInconsistent(outcome, messageCode)) {
            throw new IllegalArgumentException(
                    "Apply outcome and message code are inconsistent: outcome=%s, messageCode=%s"
                            .formatted(outcome, messageCode)
            );
        }
    }

    private static boolean isInconsistent(SettingsUpdateStatus outcome, SettingsRejectionReason messageCode) {
        boolean succeededWithoutClearingReason = outcome.isSuccessful() && (messageCode != SettingsRejectionReason.NONE);
        boolean failedWithoutRejectionReason = !outcome.isSuccessful() && (messageCode == SettingsRejectionReason.NONE);
        return succeededWithoutClearingReason || failedWithoutRejectionReason;
    }
}
