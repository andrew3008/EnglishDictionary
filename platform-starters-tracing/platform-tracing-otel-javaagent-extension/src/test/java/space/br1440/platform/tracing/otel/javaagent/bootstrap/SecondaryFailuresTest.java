package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecondaryFailuresTest {

    @Test
    void first_fatal_wins_and_later_fatal_is_only_attached() {
        // given
        IllegalStateException root = new IllegalStateException("Primary failure");
        SecondaryFailures failures = new SecondaryFailures(root, root);
        LinkageError firstFatal = new LinkageError("First fatal failure");
        OutOfMemoryError laterFatal = new OutOfMemoryError("Later fatal failure");

        // when
        failures.record(firstFatal);
        failures.record(laterFatal);

        // then
        assertThat(failures.firstFatal()).isSameAs(firstFatal);
        assertThat(root.getSuppressed()).containsExactly(firstFatal, laterFatal);
    }

    @Test
    void non_fatal_is_attached_but_never_selected() {
        // given
        IllegalStateException root = new IllegalStateException("Primary failure");
        SecondaryFailures failures = new SecondaryFailures(root, root);
        IllegalArgumentException secondary = new IllegalArgumentException("Secondary failure");

        // when
        failures.record(secondary);

        // then
        assertThat(failures.firstFatal()).isNull();
        assertThat(root.getSuppressed()).containsExactly(secondary);
    }

    @Test
    void inspection_failure_that_is_fatal_becomes_candidate() {
        // given
        LinkageError accessorFailure = new LinkageError("Cause accessor failure");
        FatalCauseAccessorException root = new FatalCauseAccessorException("Primary failure", accessorFailure);
        SecondaryFailures failures = new SecondaryFailures(root, root);
        IllegalArgumentException secondary = new IllegalArgumentException("Secondary failure");

        // when
        failures.suppress(secondary);

        // then
        assertThat(failures.firstFatal()).isSameAs(accessorFailure);
        assertThat(root.getSuppressed()).isEmpty();
    }

    private static final class FatalCauseAccessorException extends IllegalStateException {

        private final transient LinkageError accessorFailure;

        private FatalCauseAccessorException(String message, LinkageError accessorFailure) {
            super(message);
            this.accessorFailure = accessorFailure;
        }

        @Override
        public synchronized Throwable getCause() {
            throw accessorFailure;
        }
    }
}
