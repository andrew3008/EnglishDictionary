package space.br1440.platform.tracing.otel.validation;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-8A / PR-9C: immutable validation policy snapshot foundation.
 */
class ValidationSnapshotTest {

    @Test
    void fromPolicy_normalizes_source() {
        ValidationSnapshot snapshot = ValidationSnapshot.fromPolicy(
                true, false, 1, Instant.now(), "  spring-runtime-config  ");

        assertThat(snapshot.source()).isEqualTo("spring-runtime-config");
    }

    @Test
    void fromPolicy_blank_source_defaults_to_jmx() {
        ValidationSnapshot snapshot = ValidationSnapshot.fromPolicy(true, false, 1, Instant.now(), "  ");

        assertThat(snapshot.source()).isEqualTo("JMX");
    }

    @Test
    void enabled_false_passthrough_snapshot() {
        ValidationSnapshot snapshot = ValidationSnapshot.fromPolicy(
                false, true, 2, Instant.now(), "test");

        assertThat(snapshot.enabled()).isFalse();
        assertThat(snapshot.strict()).isTrue();
    }

    @Test
    void strict_flag_captured() {
        ValidationSnapshot lenient = ValidationSnapshot.fromPolicy(true, false, 1, Instant.now(), "test");
        ValidationSnapshot strict = ValidationSnapshot.fromPolicy(true, true, 1, Instant.now(), "test");

        assertThat(lenient.strict()).isFalse();
        assertThat(strict.strict()).isTrue();
    }

    @Test
    void record_is_immutable() {
        ValidationSnapshot snapshot = ValidationSnapshot.fromPolicy(true, false, 5, Instant.EPOCH, "startup");

        assertThat(snapshot.enabled()).isTrue();
        assertThat(snapshot.strict()).isFalse();
        assertThat(snapshot.version()).isEqualTo(5);
        assertThat(snapshot.updatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(snapshot.source()).isEqualTo("startup");
    }
}
