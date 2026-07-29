package space.br1440.platform.tracing.otel.javaagent.control.state;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5 TEST §20 P5.5 — Agent audit ring must not retain raw route/secret canaries.
 *
 * <p>{@link PolicyAuditEntry} is metadata-only (attempt, outcome, messageCode,
 * sequence, payloadDigest). Route prefixes and Category B secrets must never
 * appear in stringified audit entries.
 */
class P55SecretCanaryAuditTest {

    private static final String CANARY_ROUTE = "/canary/secret/z9Qx7mK2vL4pR8nW";
    private static final String CANARY_HMAC = "CANARY_HMAC_KEY_z9Qx7mK2vL4pR8nW";
    private static final String CANARY_HEADER = "CANARY_AUTH_HEADER_Bearer_z9Qx7mK2";
    private static final String CANARY_TOKEN = "CANARY_TOKEN_z9Qx7mK2vL4pR8nW";
    private static final String CANARY_PATH = "CANARY_PATH_C:/secrets/z9Qx7mK2.key";

    private static final List<String> ALL_CANARIES = List.of(
            CANARY_ROUTE, CANARY_HMAC, CANARY_HEADER, CANARY_TOKEN, CANARY_PATH);

    @Test
    void auditRing_afterApplyWithRouteCanary_containsOnlyMetadata() {
        EffectiveTracingPolicy policy = EffectiveTracingPolicy.of(
                true,
                0.5d,
                Map.of(CANARY_ROUTE, 0.25d),
                true,
                false,
                true,
                true,
                PolicyLogLevel.INFO);
        AcceptedPolicyStateStore store = new AcceptedPolicyStateStore(
                AcceptedPolicyTestFixtures.RUNTIME_INSTANCE_ID,
                policy);
        String digest = EffectiveTracingPolicyDigest.sha256(policy);
        assertThat(digest).doesNotContain(CANARY_ROUTE);

        PolicyAuditBuffer audit = new PolicyAuditBuffer();
        PolicyApplyAttempt attempt = new PolicyApplyAttempt(
                UUID.randomUUID().toString(),
                Instant.parse("2026-07-29T12:00:00Z"),
                PolicySourceAuthority.CONFIG_SERVER,
                PolicyApplyTrigger.REFRESH,
                "revision-canary");
        PolicyApplyResult result = new PolicyApplyResult(
                PolicyApplyOutcome.UNCHANGED,
                store.current(),
                digest,
                PolicyMessageCode.NONE);
        audit.record(attempt, result);

        String rendered = audit.recent().toString();
        for (String canary : ALL_CANARIES) {
            assertThat(rendered)
                    .as("audit must not contain canary %s", canary)
                    .doesNotContain(canary);
        }
        assertThat(rendered).contains(digest);
        assertThat(rendered).contains("UNCHANGED");
    }
}
