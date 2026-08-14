package space.br1440.platform.tracing.otel.javaagent.control;

import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsDigest;

/**
 * Test/benchmark white-box bridge to package-private {@link TracingSettingsDigest}.
 *
 * <p>Lives in {@code src/test/java} in the same package so it can see
 * package-private members without exports or reflection.
 */
public final class EffectiveTracingPolicyDigestAccess {

    private EffectiveTracingPolicyDigestAccess() {
    }

    /**
     * Delegates to {@code TracingSettingsDigest.sha256(TracingSettings)}
     * using the policy carried inside the provided {@link TracingSettingsRevision}.
     *
     * <p>The view must be an {@link TracingSettingsRevision} — the only production
     * implementation that holds a typed {@code TracingSettings} reference.
     *
     * @param view snapshot obtained from {@code TracingSettingsSource.current()}
     * @return lowercase-hex SHA-256 digest string (64 characters)
     * @throws IllegalArgumentException if {@code view} is not an {@link TracingSettingsRevision}
     */
    public static String sha256(TracingSettingsRevision view) {
        if (!(view instanceof TracingSettingsRevision envelope)) {
            throw new IllegalArgumentException(
                    "view must be TracingSettingsRevision, got: " + view.getClass().getName());
        }
        return TracingSettingsDigest.sha256(envelope.policy());
    }
}
