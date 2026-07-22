package space.br1440.platform.tracing.core.validation;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-8A / PR-9C: side-effect-free validation policy update builder for CAS publish.
 */
class ValidationPolicyUpdateTest {

    @Test
    void buildNext_increments_version_and_applies_flags() {
        ValidationSnapshot previous = ValidationSnapshot.fromPolicy(true, false, 3, Instant.EPOCH, "startup");

        ValidationSnapshot next = ValidationPolicyUpdate.buildNext(previous, false, true, "test-update");

        assertThat(next.version()).isEqualTo(4);
        assertThat(next.enabled()).isFalse();
        assertThat(next.strict()).isTrue();
        assertThat(next.source()).isEqualTo("test-update");
        assertThat(next.updatedAt()).isAfter(Instant.EPOCH);
    }

    @Test
    void normalizeSource_blank_defaults_to_jmx() {
        assertThat(ValidationPolicyUpdate.normalizeSource(null)).isEqualTo("JMX");
        assertThat(ValidationPolicyUpdate.normalizeSource("  ")).isEqualTo("JMX");
    }

    @Test
    void normalizeSource_trims_non_blank() {
        assertThat(ValidationPolicyUpdate.normalizeSource("  spring-runtime-config  "))
                .isEqualTo("spring-runtime-config");
    }
}
