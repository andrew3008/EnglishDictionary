package space.br1440.platform.devtools.opusmcp.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LimitsGuardTest {

    private final LimitsGuard guard = new LimitsGuard(10, 5, 8);

    @Test
    void rejectsOversizedContext() {
        assertThat(guard.checkContextSize("12345678901").exceeded()).isTrue();
    }

    @Test
    void rejectsOversizedConstraints() {
        assertThat(guard.checkConstraintsSize("123456").exceeded()).isTrue();
    }

    @Test
    void truncatesOutput() {
        LimitsGuard.TruncationResult result = guard.truncateOutput("123456789");

        assertThat(result.truncated()).isTrue();
        assertThat(result.value()).hasSize(8);
    }
}
