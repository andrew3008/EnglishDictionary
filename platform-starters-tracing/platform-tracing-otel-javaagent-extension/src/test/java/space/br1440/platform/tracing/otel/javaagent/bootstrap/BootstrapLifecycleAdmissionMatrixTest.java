package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.FAILED_ROLLBACK_INCOMPLETE;

import java.util.stream.Stream;

import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class BootstrapLifecycleAdmissionMatrixTest {

    private static final String PRIMARY_FAILURE = "PRIMARY_FAILURE";
    private static final String REENTRANT_OR_OUT_OF_ORDER = "REENTRANT_OR_OUT_OF_ORDER";
    private static final String SECOND_SDK_BUILD = "SECOND_SDK_BUILD";
    private static final String DUPLICATE_ACTIVATION = "DUPLICATE_ACTIVATION";
    private static final String BOOTSTRAP_TERMINAL = "BOOTSTRAP_TERMINAL";

    @ParameterizedTest(name = "{0}")
    @MethodSource("beginPrepareCases")
    void beginPrepareFollowsAdmissionMatrix(AdmissionCase admissionCase) {
        // given
        LifecycleFixture fixture = fixtureIn(admissionCase.initialState());

        if (admissionCase.accepted()) {
            // when
            fixture.lifecycle().beginPrepare();

            // then
            assertThat(fixture.lifecycle().state()).isEqualTo(admissionCase.expectedState());
            assertNoRecordedFailure(fixture.lifecycle());
            return;
        }

        // when
        Throwable thrown = catchThrowable(fixture.lifecycle()::beginPrepare);

        // then
        assertRejectedWithoutStateChange(fixture, admissionCase, thrown);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("beginActivationCases")
    void beginActivationFollowsAdmissionMatrix(AdmissionCase admissionCase) {
        // given
        LifecycleFixture fixture = fixtureIn(admissionCase.initialState());

        if (admissionCase.accepted()) {
            // when
            AutoconfigureSession activated = fixture.lifecycle().beginActivation();

            // then
            assertThat(activated).isSameAs(fixture.session());
            assertThat(fixture.lifecycle().state()).isEqualTo(admissionCase.expectedState());
            assertNoRecordedFailure(fixture.lifecycle());
            return;
        }

        // when
        Throwable thrown = catchThrowable(fixture.lifecycle()::beginActivation);

        // then
        assertRejectedWithoutStateChange(fixture, admissionCase, thrown);
    }

    private static Stream<AdmissionCase> beginPrepareCases() {
        return Stream.of(
                AdmissionCase.accepted(BootstrapState.NEW, BootstrapState.PREPARING),
                AdmissionCase.rejected(BootstrapState.PREPARING, REENTRANT_OR_OUT_OF_ORDER),
                AdmissionCase.rejected(BootstrapState.PREPARED, SECOND_SDK_BUILD),
                AdmissionCase.rejected(BootstrapState.ACTIVATING, SECOND_SDK_BUILD),
                AdmissionCase.rejected(BootstrapState.ACTIVE, SECOND_SDK_BUILD),
                AdmissionCase.rejected(
                        BootstrapState.FAILED,
                        BootstrapState.FAILED,
                        PRIMARY_FAILURE,
                        FAILED_ROLLBACK_INCOMPLETE
                ),
                AdmissionCase.rejected(BootstrapState.CLOSING, BOOTSTRAP_TERMINAL),
                AdmissionCase.rejected(BootstrapState.CLOSED, BOOTSTRAP_TERMINAL)
        );
    }

    private static Stream<AdmissionCase> beginActivationCases() {
        return Stream.of(
                AdmissionCase.rejected(BootstrapState.NEW, REENTRANT_OR_OUT_OF_ORDER),
                AdmissionCase.rejected(BootstrapState.PREPARING, REENTRANT_OR_OUT_OF_ORDER),
                AdmissionCase.accepted(BootstrapState.PREPARED, BootstrapState.ACTIVATING),
                AdmissionCase.rejected(BootstrapState.ACTIVATING, DUPLICATE_ACTIVATION),
                AdmissionCase.rejected(BootstrapState.ACTIVE, DUPLICATE_ACTIVATION),
                AdmissionCase.rejected(
                        BootstrapState.FAILED,
                        BootstrapState.FAILED,
                        PRIMARY_FAILURE,
                        FAILED_ROLLBACK_INCOMPLETE
                ),
                AdmissionCase.rejected(BootstrapState.CLOSING, BOOTSTRAP_TERMINAL),
                AdmissionCase.rejected(BootstrapState.CLOSED, BOOTSTRAP_TERMINAL)
        );
    }

    private static LifecycleFixture fixtureIn(BootstrapState initialState) {
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        AutoconfigureSession session = mock(AutoconfigureSession.class);

        switch (initialState) {
            case NEW -> {
            }
            case PREPARING -> lifecycle.beginPrepare();
            case PREPARED -> prepare(lifecycle, session);
            case ACTIVATING -> {
                prepare(lifecycle, session);
                assertThat(lifecycle.beginActivation()).isSameAs(session);
            }
            case ACTIVE -> {
                prepare(lifecycle, session);
                assertThat(lifecycle.beginActivation()).isSameAs(session);
                lifecycle.commitActive();
            }
            case FAILED -> {
                IllegalStateException primaryCause = new IllegalStateException("Primary failure");
                assertThat(lifecycle.fail(PRIMARY_FAILURE, "prepare", "prepare", null, primaryCause).getCause())
                        .isSameAs(primaryCause);
                lifecycle.escalateRollbackIncomplete(
                        "rollback",
                        new IllegalStateException("Cleanup failure")
                );
            }
            case CLOSING -> lifecycle.acquireClose();
            case CLOSED -> {
                BootstrapLifecycleState.CloseAcquisition acquisition = lifecycle.acquireClose();
                lifecycle.markClosed();
                lifecycle.completeClose(acquisition.flight(), null);
            }
        }

        assertThat(lifecycle.state()).isEqualTo(initialState);
        BootstrapFailure recordedFailure =
                initialState == BootstrapState.FAILED ? lifecycle.failure() : null;
        return new LifecycleFixture(lifecycle, session, recordedFailure);
    }

    private static void prepare(BootstrapLifecycleState lifecycle, AutoconfigureSession session) {
        lifecycle.beginPrepare();
        lifecycle.completePrepare(session);
    }

    private static void assertRejectedWithoutStateChange(LifecycleFixture fixture,
                                                         AdmissionCase admissionCase,
                                                         Throwable thrown) {
        assertThat(thrown).isInstanceOf(BootstrapException.class);
        BootstrapException rejection = (BootstrapException) thrown;
        assertThat(rejection.failure().primaryCode()).isEqualTo(admissionCase.expectedPrimaryCode());
        assertThat(rejection.failure().effectiveCode()).isEqualTo(admissionCase.expectedEffectiveCode());
        assertThat(fixture.lifecycle().state()).isEqualTo(admissionCase.expectedState());

        BootstrapFailure recordedFailure = fixture.recordedFailure();
        if (recordedFailure == null) {
            assertNoRecordedFailure(fixture.lifecycle());
            return;
        }

        assertThat(fixture.lifecycle().failure()).isSameAs(recordedFailure);
        assertThat(rejection.failure().cause()).isSameAs(recordedFailure.cause());
        assertThat(fixture.lifecycle().failure().primaryCode()).isEqualTo(recordedFailure.primaryCode());
        assertThat(fixture.lifecycle().failure().effectiveCode()).isEqualTo(recordedFailure.effectiveCode());
    }

    private static void assertNoRecordedFailure(BootstrapLifecycleState lifecycle) {
        Throwable missing = catchThrowable(lifecycle::failure);
        assertThat(missing)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap failure is not recorded");
    }

    private record LifecycleFixture(BootstrapLifecycleState lifecycle,
                                    AutoconfigureSession session,
                                    BootstrapFailure recordedFailure) {
    }

    private record AdmissionCase(BootstrapState initialState,
                                 BootstrapState expectedState,
                                 String expectedPrimaryCode,
                                 String expectedEffectiveCode) {

        private static AdmissionCase accepted(BootstrapState initialState,
                                              BootstrapState expectedState) {
            return new AdmissionCase(initialState, expectedState, null, null);
        }

        private static AdmissionCase rejected(BootstrapState initialState,
                                              String expectedCode) {
            return rejected(initialState, initialState, expectedCode, expectedCode);
        }

        private static AdmissionCase rejected(BootstrapState initialState,
                                              BootstrapState expectedState,
                                              String expectedPrimaryCode,
                                              String expectedEffectiveCode) {
            return new AdmissionCase(
                    initialState,
                    expectedState,
                    expectedPrimaryCode,
                    expectedEffectiveCode
            );
        }

        private boolean accepted() {
            return expectedPrimaryCode == null;
        }

        @Override
        public String toString() {
            return initialState + " -> " + expectedState;
        }
    }
}
