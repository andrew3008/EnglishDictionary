package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsDigest;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsUpdateTrigger;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;

class SecretCanaryAuditTest {

    private static final String CANARY_ROUTE = "/canary/secret/z9Qx7mK2vL4pR8nW";
    private static final String CANARY_HMAC = "CANARY_HMAC_KEY_z9Qx7mK2vL4pR8nW";
    private static final String CANARY_HEADER = "CANARY_AUTH_HEADER_Bearer_z9Qx7mK2";
    private static final String CANARY_TOKEN = "CANARY_TOKEN_z9Qx7mK2vL4pR8nW";
    private static final String CANARY_PATH = "CANARY_PATH_C:/secrets/z9Qx7mK2.key";

    private static final List<String> ALL_CANARIES = List.of(
            CANARY_ROUTE, CANARY_HMAC, CANARY_HEADER, CANARY_TOKEN, CANARY_PATH
    );

    @Test
    void diagnosticsSnapshot_afterApplyWithRouteCanary_containsOnlyMetadata() {
        // given
        TracingSettings policy = TracingSettings.of(
                true,
                0.5d,
                Map.of(CANARY_ROUTE, 0.25d),
                SpanAttributeValidationLevel.WARNING,
                true,
                true,
                AgentLogLevel.INFO
        );
        TracingSettingsStore store = new TracingSettingsStore(
                AcceptedPolicyTestFixtures.RUNTIME_INSTANCE_ID,
                policy
        );
        String digest = TracingSettingsDigest.sha256(policy);
        assertThat(digest).doesNotContain(CANARY_ROUTE);

        LastSettingsUpdate diagnostics = new LastSettingsUpdate();
        SettingsUpdateAttempt attempt = new SettingsUpdateAttempt(
                UUID.randomUUID().toString(),
                Instant.parse("2026-07-29T12:00:00Z"),
                SettingsSourceAuthority.CONFIG_SERVER,
                SettingsUpdateTrigger.REFRESH,
                "revision-canary"
        );
        SettingsUpdateResult result = new SettingsUpdateResult(
                SettingsUpdateStatus.UNCHANGED,
                store.current(),
                digest,
                SettingsRejectionReason.NONE
        );
        diagnostics.record(attempt, result);

        // when
        String rendered = diagnostics.snapshot().toString();

        // then
        for (String canary : ALL_CANARIES) {
            assertThat(rendered)
                    .as("diagnostics snapshot must not contain canary %s", canary)
                    .doesNotContain(canary);
        }
        assertThat(rendered).contains("UNCHANGED");
    }
}
