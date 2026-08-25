package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;

import java.util.Objects;

public record SettingsUpdateResult(SettingsUpdateStatus outcome,
                                   TracingSettingsRevision accepted,
                                   String payloadDigest,
                                   SettingsRejectionReason messageCode) {

    public SettingsUpdateResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(payloadDigest, "payloadDigest");
        Objects.requireNonNull(messageCode, "messageCode");

        if (!payloadDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("payloadDigest must be lowercase SHA-256 hexadecimal");
        }

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
