package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JmxRegistrationExceptionTest {

    @Test
    void failureCodeIsExplicitAndNullable() {
        // given
        Throwable cause = new IllegalStateException("registration failure");

        // when
        JmxRegistrationException classified = new JmxRegistrationException(
                "Runtime registration failed",
                cause,
                "JMX_BATCH_REGISTRATION_FAILED"
        );
        JmxRegistrationException unclassified = new JmxRegistrationException("Diagnostic registration failed", cause);

        // then
        assertThat(classified.failureCode()).isEqualTo("JMX_BATCH_REGISTRATION_FAILED");
        assertThat(unclassified.failureCode()).isNull();
    }

    @Test
    void rollbackIncompleteDetectionChecksFailureAndDirectSuppressed() {
        // given
        JmxRegistrationException marked = new JmxRegistrationException("MBean cleanup failed", new IllegalStateException("cleanup failure"));
        marked.markRollbackIncomplete();
        RuntimeException withDirectSuppressed = new RuntimeException("registration failure");
        withDirectSuppressed.addSuppressed(marked);
        RuntimeException withNestedCause = new RuntimeException("outer failure", withDirectSuppressed);

        // when
        boolean selfDetected = JmxRegistrationException.rollbackIncompleteIn(marked);
        boolean directSuppressedDetected = JmxRegistrationException.rollbackIncompleteIn(withDirectSuppressed);
        boolean nestedCauseDetected = JmxRegistrationException.rollbackIncompleteIn(withNestedCause);

        // then
        assertThat(selfDetected).isTrue();
        assertThat(directSuppressedDetected).isTrue();
        assertThat(nestedCauseDetected).isFalse();
        assertThat(JmxRegistrationException.rollbackIncompleteIn(null)).isFalse();
    }
}
