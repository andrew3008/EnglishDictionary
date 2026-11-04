package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;

/**
 * Результат terminal transition lifecycle.
 *
 * <p>{@code claimedSession} передаёт вызывающему владение session, изъятой из lifecycle в той же
 * критической секции, что и запись отказа. {@code projectionFailure} — отказ best-effort публикации
 * {@code FAILED} в wire; lifecycle о нём не знает, его добавляют transitions.
 */
record FailureAdmission(Kind kind,
                        BootstrapFailure failure,
                        AutoconfigureSession claimedSession,
                        Throwable projectionFailure) {

    FailureAdmission {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(failure, "failure");
        if (kind != Kind.RECORDED && (claimedSession != null || projectionFailure != null)) {
            throw new IllegalArgumentException("Only a recorded failure admission can own a session or a projection failure");
        }
    }

    static FailureAdmission recorded(BootstrapFailure failure, AutoconfigureSession claimedSession) {
        return new FailureAdmission(Kind.RECORDED, failure, claimedSession, null);
    }

    static FailureAdmission alreadyFailed(BootstrapFailure stickyFailure) {
        return new FailureAdmission(Kind.ALREADY_FAILED, stickyFailure, null, null);
    }

    static FailureAdmission rejected(BootstrapFailure unrecordedFailure) {
        return new FailureAdmission(Kind.REJECTED, unrecordedFailure, null, null);
    }

    FailureAdmission withProjectionFailure(Throwable failedProjection) {
        Objects.requireNonNull(failedProjection, "failedProjection");
        return new FailureAdmission(kind, failure, claimedSession, failedProjection);
    }

    boolean recorded() {
        return kind == Kind.RECORDED;
    }

    enum Kind {
        /** Отказ записан этим вызовом; вызывающий владеет settlement. */
        RECORDED,
        /** Sticky-отказ уже записан; новый cause не заменяет его, cleanup lifecycle не выполняется. */
        ALREADY_FAILED,
        /** Lifecycle закрывается или закрыт; состояние не меняется, failure не записан. */
        REJECTED
    }
}
