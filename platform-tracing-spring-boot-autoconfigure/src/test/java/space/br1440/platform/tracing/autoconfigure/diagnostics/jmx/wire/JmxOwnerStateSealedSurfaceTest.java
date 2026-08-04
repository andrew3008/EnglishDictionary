package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Закрытая поверхность {@link JmxOwnerState}: ровно два permits, generation-инварианты
 * Present/Absent — breaking-изменение набора состояний без bump wire-схемы запрещено.
 */
class JmxOwnerStateSealedSurfaceTest {

    @Test
    void sealedPermitsExactlyPresentAndAbsent() {
        assertThat(JmxOwnerState.class.isSealed()).isTrue();
        assertThat(JmxOwnerState.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(JmxOwnerState.Present.class, JmxOwnerState.Absent.class);
    }

    @Test
    void presentRejectsNonPositiveGeneration() {
        assertThatThrownBy(() -> new JmxOwnerState.Present<>("v", 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JmxOwnerState.Present<>("v", -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void absentRejectsNegativeGeneration() {
        assertThatThrownBy(() -> new JmxOwnerState.Absent<>(-1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new JmxOwnerState.Absent<String>(0L).generation()).isZero();
    }
}
