package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;

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
        RECORDED,
        ALREADY_FAILED,
        REJECTED
    }
}
