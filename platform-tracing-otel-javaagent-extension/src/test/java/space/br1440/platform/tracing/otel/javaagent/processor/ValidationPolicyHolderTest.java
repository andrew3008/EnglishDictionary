package space.br1440.platform.tracing.otel.javaagent.processor;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.core.validation.ValidationSnapshot;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-8A: agent-side validation policy holder (CAS + last-known-good).
 */
class ValidationPolicyHolderTest {

    @Test
    void current_returns_initial_snapshot() {
        ValidationSnapshot initial = ValidationSnapshot.fromPolicy(true, false, 1, Instant.now(), "startup");
        ValidationPolicyHolder holder = new ValidationPolicyHolder(initial);

        assertThat(holder.current()).isSameAs(initial);
        assertThat(holder.version()).isEqualTo(1);
    }

    @Test
    void tryApplyPolicyUpdate_applies_lenient_snapshot_and_bumps_version() {
        ValidationPolicyHolder holder = new ValidationPolicyHolder(
                ValidationSnapshot.fromPolicy(true, false, 1, Instant.now(), "startup"));

        boolean applied = holder.tryApplyPolicyUpdate(true, false, "test-update");

        assertThat(applied).isTrue();
        assertThat(holder.version()).isEqualTo(2);
        assertThat(holder.current().enabled()).isTrue();
        assertThat(holder.current().strict()).isFalse();
        assertThat(holder.current().source()).isEqualTo("test-update");
    }

    @Test
    void tryApplyPolicyUpdate_rejects_strict_when_guard_disabled() {
        ValidationPolicyHolder holder = new ValidationPolicyHolder(
                ValidationSnapshot.fromPolicy(true, false, 1, Instant.now(), "startup"),
                false);

        boolean applied = holder.tryApplyPolicyUpdate(false, true, "test-update");

        assertThat(applied).isFalse();
        assertThat(holder.version()).isEqualTo(1);
        assertThat(holder.current().strict()).isFalse();
    }

    @Test
    void tryApplyPolicyUpdate_allows_strict_when_guard_enabled() {
        ValidationPolicyHolder holder = new ValidationPolicyHolder(
                ValidationSnapshot.fromPolicy(true, false, 1, Instant.now(), "startup"),
                true);

        boolean applied = holder.tryApplyPolicyUpdate(false, true, "test-update");

        assertThat(applied).isTrue();
        assertThat(holder.version()).isEqualTo(2);
        assertThat(holder.current().enabled()).isFalse();
        assertThat(holder.current().strict()).isTrue();
    }

    @Test
    void tryUpdate_rejects_invalid_and_keeps_last_known_good() {
        ValidationPolicyHolder holder = new ValidationPolicyHolder(
                ValidationSnapshot.fromPolicy(true, false, 3, Instant.now(), "startup"));
        ValidationSnapshot before = holder.current();

        boolean applied = holder.tryUpdate(prev -> {
            throw new IllegalArgumentException("invalid validation update");
        });

        assertThat(applied).isFalse();
        assertThat(holder.current()).isSameAs(before);
        assertThat(holder.version()).isEqualTo(3);
    }

    @Test
    void mutating_external_reference_does_not_affect_published_snapshot() {
        ValidationSnapshot initial = ValidationSnapshot.fromPolicy(true, false, 1, Instant.now(), "startup");
        ValidationPolicyHolder holder = new ValidationPolicyHolder(initial);
        ValidationSnapshot published = holder.current();

        ValidationSnapshot rebuilt = ValidationSnapshot.fromPolicy(
                !published.enabled(), !published.strict(), published.version(), published.updatedAt(), "mutated");

        assertThat(holder.current()).isSameAs(published);
        assertThat(rebuilt.enabled()).isNotEqualTo(published.enabled());
    }
}
