package space.br1440.platform.tracing.otel.javaagent.bootstrap.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BootstrapCleanupFailuresTest {

    @Test
    void aggregateTreatsMissingFailureAsNeutral() {
        // given
        IllegalStateException aggregate = new IllegalStateException("Aggregate failure");

        // when
        Throwable unchanged = BootstrapCleanupFailures.aggregate(aggregate, null);

        // then
        assertThat(unchanged).isSameAs(aggregate);
    }

    @Test
    void aggregatePromotesReachableFatalWithoutCreatingBacklinkCycle() {
        // given
        IllegalStateException aggregate = new IllegalStateException("Aggregate failure");
        LinkageError fatal = new LinkageError("Fatal failure");
        aggregate.addSuppressed(fatal);

        // when
        Throwable outcome = BootstrapCleanupFailures.aggregate(aggregate, fatal);

        // then
        assertThat(outcome).isSameAs(fatal);
        assertThat(fatal.getSuppressed()).isEmpty();
        assertThat(BootstrapCleanupFailures.contains(aggregate, fatal)).isTrue();
        assertThat(BootstrapCleanupFailures.contains(fatal, aggregate)).isFalse();
    }

    @Test
    void aggregateKeepsFirstFatalWhenIncomingFatalContainsIt() {
        // given
        NoClassDefFoundError firstFatal = new NoClassDefFoundError("First fatal failure");
        LinkageError secondFatal = new LinkageError("Second fatal failure");
        secondFatal.addSuppressed(firstFatal);

        // when
        Throwable outcome = BootstrapCleanupFailures.aggregate(firstFatal, secondFatal);

        // then
        assertThat(outcome).isSameAs(firstFatal);
        assertThat(firstFatal.getSuppressed()).isEmpty();
        assertThat(secondFatal.getSuppressed()).containsExactly(firstFatal);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("promotionOrders")
    void aggregatePromotesOnlyPlatformFatalOverAssertionError(String order, boolean assertionFirst) {
        // given
        AssertionError assertion = new AssertionError("Assertion failure");
        LinkageError linkage = new LinkageError("Linkage failure");

        // when
        Throwable outcome = assertionFirst
                ? BootstrapCleanupFailures.aggregate(assertion, linkage)
                : BootstrapCleanupFailures.aggregate(linkage, assertion);

        // then
        assertThat(outcome).isSameAs(linkage);
        assertThat(linkage.getSuppressed()).containsExactly(assertion);
        assertThat(assertion.getSuppressed()).isEmpty();
    }

    @Test
    void aggregateDoesNotPromoteAssertionErrorOverOrdinaryFailure() {
        // given
        IllegalStateException ordinary = new IllegalStateException("Ordinary failure");
        AssertionError assertion = new AssertionError("Assertion failure");

        // when
        Throwable outcome = BootstrapCleanupFailures.aggregate(ordinary, assertion);

        // then
        assertThat(outcome).isSameAs(ordinary);
        assertThat(ordinary.getSuppressed()).containsExactly(assertion);
    }

    @Test
    void aggregateKeepsFirstOfTwoFatalFailures() {
        // given
        OutOfMemoryError first = new OutOfMemoryError("First fatal failure");
        LinkageError second = new LinkageError("Second fatal failure");

        // when
        Throwable outcome = BootstrapCleanupFailures.aggregate(first, second);

        // then
        assertThat(outcome).isSameAs(first);
        assertThat(first.getSuppressed()).containsExactly(second);
    }

    @Test
    void aggregateSelectsRootIndependentlyOfDisabledSuppression() {
        // given
        NonSuppressingException nonSuppressing = new NonSuppressingException("Non-suppressing failure");
        IllegalStateException ordinary = new IllegalStateException("Ordinary failure");
        LinkageError fatal = new LinkageError("Fatal failure");

        // when
        Throwable ordinaryOutcome = BootstrapCleanupFailures.aggregate(nonSuppressing, ordinary);
        Throwable fatalOutcome = BootstrapCleanupFailures.aggregate(nonSuppressing, fatal);

        // then
        assertThat(ordinaryOutcome).isSameAs(nonSuppressing);
        assertThat(nonSuppressing.getSuppressed()).isEmpty();
        assertThat(fatalOutcome).isSameAs(fatal);
        assertThat(fatal.getSuppressed()).containsExactly(nonSuppressing);
    }

    @Test
    void aggregateTerminatesOnPreexistingCycle() {
        // given
        IllegalStateException first = new IllegalStateException("First failure");
        UnsupportedOperationException second = new UnsupportedOperationException("Second failure");
        IllegalArgumentException independent = new IllegalArgumentException("Independent failure");
        first.addSuppressed(second);
        second.addSuppressed(first);

        // when
        Throwable outcome = BootstrapCleanupFailures.aggregate(first, independent);

        // then
        assertThat(outcome).isSameAs(first);
        assertThat(first.getSuppressed()).containsExactly(second, independent);
        assertThat(BootstrapCleanupFailures.contains(first, second)).isTrue();
        assertThat(BootstrapCleanupFailures.contains(second, first)).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("throwingCausePositions")
    void aggregateKeepsRootWhenOrdinaryCauseAccessorFails(String position, boolean throwingPrimary) {
        // given
        Throwable primary = throwingPrimary
                ? new ThrowingCauseException("Primary failure", () -> new IllegalStateException("Cause inspection failed"))
                : new IllegalStateException("Primary failure");
        Throwable secondary = throwingPrimary
                ? new IllegalArgumentException("Secondary failure")
                : new ThrowingCauseException("Secondary failure", () -> new IllegalStateException("Cause inspection failed"));

        // when
        Throwable outcome = BootstrapCleanupFailures.aggregate(primary, secondary);

        // then
        assertThat(outcome).isSameAs(primary);
        assertThat(primary.getSuppressed()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("throwingCausePositions")
    void aggregateKeepsOriginalFatalWhenFatalCauseAccessorFails(String position, boolean throwingPrimary) {
        // given
        LinkageError accessorFailure = new LinkageError("Fatal cause inspection failure");
        OutOfMemoryError primary = throwingPrimary
                ? new ThrowingCauseOutOfMemoryError("Original fatal failure", accessorFailure)
                : new OutOfMemoryError("Original fatal failure");
        Throwable cleanup = throwingPrimary
                ? new IllegalStateException("Cleanup failure")
                : new ThrowingCauseException("Cleanup failure", () -> accessorFailure);

        // when
        Throwable outcome = BootstrapCleanupFailures.aggregate(primary, cleanup);

        // then
        assertThat(outcome).isSameAs(primary);
        assertThat(primary.getSuppressed()).isEmpty();
    }

    @Test
    void aggregateKeepsIncomingCleanupFatalWhenFatalInspectionFails() {
        // given
        LinkageError accessorFailure = new LinkageError("Fatal cause inspection failure");
        ThrowingCauseException original = new ThrowingCauseException("Original failure", () -> accessorFailure);
        OutOfMemoryError cleanupFatal = new OutOfMemoryError("Cleanup fatal failure");

        // when
        Throwable outcome = BootstrapCleanupFailures.aggregate(original, cleanupFatal);

        // then
        assertThat(outcome).isSameAs(cleanupFatal);
        assertThat(cleanupFatal.getSuppressed()).isEmpty();
    }

    @Test
    void aggregateSelectsFatalInspectionFailureWhenNoFatalWasSelected() {
        // given
        LinkageError accessorFailure = new LinkageError("Fatal cause inspection failure");
        ThrowingCauseException original = new ThrowingCauseException("Original failure", () -> accessorFailure);
        IllegalStateException cleanup = new IllegalStateException("Cleanup failure");

        // when
        Throwable outcome = BootstrapCleanupFailures.aggregate(original, cleanup);

        // then
        assertThat(outcome).isSameAs(accessorFailure);
        assertThat(original.getSuppressed()).isEmpty();
    }

    @Test
    void attachmentDoesNotCreateCycleThroughSuppressedBackEdgeOfThrowingCauseNode() {
        // given
        IllegalStateException first = new IllegalStateException("First failure");
        ThrowingCauseException second = new ThrowingCauseException(
                "Second failure",
                () -> new AssertionError("Cause inspection failed")
        );
        second.addSuppressed(first);

        // when
        Throwable aggregated = BootstrapCleanupFailures.aggregate(first, second);
        BootstrapCleanupFailures.suppressPreservingPrimary(first, second);

        // then
        assertThat(aggregated).isSameAs(first);
        assertThat(first.getSuppressed()).isEmpty();
        assertThat(second.getSuppressed()).containsExactly(first);
    }

    @Test
    void attachmentIsSkippedWhenOrdinaryCauseInspectionIsIncomplete() {
        // given
        IllegalStateException primary = new IllegalStateException("Primary failure");
        ThrowingCauseException secondary = new ThrowingCauseException(
                "Secondary failure",
                () -> new IllegalStateException("Cause inspection failed")
        );

        // when
        BootstrapCleanupFailures.suppressPreservingPrimary(primary, secondary);

        // then
        assertThat(primary.getSuppressed()).isEmpty();
        assertThat(BootstrapCleanupFailures.contains(secondary, primary)).isFalse();
    }

    @Test
    void containsTraversesSuppressedEdgesOfThrowingCauseNode() {
        // given
        IllegalArgumentException target = new IllegalArgumentException("Target failure");
        ThrowingCauseException root = new ThrowingCauseException(
                "Root failure",
                () -> new IllegalStateException("Cause inspection failed")
        );
        root.addSuppressed(target);

        // when
        boolean found = BootstrapCleanupFailures.contains(root, target);

        // then
        assertThat(found).isTrue();
    }

    @Test
    void suppressPreservingPrimaryPropagatesOnlyFatalInspectionFailure() {
        // given
        LinkageError accessorFailure = new LinkageError("Fatal cause inspection failure");
        IllegalStateException primary = new IllegalStateException("Primary failure");
        ThrowingCauseException fatalSecondary = new ThrowingCauseException("Secondary failure", () -> accessorFailure);
        ThrowingCauseException ordinarySecondary = new ThrowingCauseException(
                "Secondary failure",
                () -> new IllegalStateException("Cause inspection failed")
        );

        // when/then
        assertThatThrownBy(() -> BootstrapCleanupFailures.suppressPreservingPrimary(primary, fatalSecondary))
                .isSameAs(accessorFailure);
        assertThatCode(() -> BootstrapCleanupFailures.suppressPreservingPrimary(primary, ordinarySecondary))
                .doesNotThrowAnyException();
        assertThat(primary.getSuppressed()).isEmpty();
    }

    @Test
    void suppressPreservingPrimaryAddsExactAssertionErrorWithoutPromotion() {
        // given
        IllegalStateException primary = new IllegalStateException("Primary failure");
        AssertionError secondary = new AssertionError("Assertion secondary failure");

        // when
        BootstrapCleanupFailures.suppressPreservingPrimary(primary, secondary);

        // then
        assertThat(primary.getSuppressed()).containsExactly(secondary);
        assertThat(secondary.getSuppressed()).isEmpty();
    }

    @Test
    void suppressPreservingPrimaryRejectsSelfDuplicateAndCycle() {
        // given
        IllegalStateException primary = new IllegalStateException("Primary failure");
        UnsupportedOperationException secondary = new UnsupportedOperationException("Secondary failure");
        BootstrapCleanupFailures.suppressPreservingPrimary(primary, secondary);
        IllegalArgumentException cyclicSecondary = new IllegalArgumentException("Cyclic secondary failure", primary);

        // when
        BootstrapCleanupFailures.suppressPreservingPrimary(primary, primary);
        BootstrapCleanupFailures.suppressPreservingPrimary(primary, secondary);
        BootstrapCleanupFailures.suppressPreservingPrimary(primary, cyclicSecondary);

        // then
        assertThat(primary.getSuppressed()).containsExactly(secondary);
        assertThat(cyclicSecondary.getCause()).isSameAs(primary);
    }

    @Test
    void rollbackOutcomeSuppressionDoesNotAddEdgeBackToPrimaryFromCleanupCauseChain() {
        // given
        IllegalStateException primary = new IllegalStateException("Primary failure");
        IllegalArgumentException cleanup = new IllegalArgumentException("Cleanup failure", primary);
        Throwable outcome = catchThrowable(() -> {
            try (BootstrapResources resources = new BootstrapResources()) {
                resources.push(() -> {
                    throw cleanup;
                }, "runtime-controls");
            }
        });

        // when
        BootstrapCleanupFailures.suppressPreservingPrimary(primary, outcome);

        // then
        assertThat(outcome)
                .isInstanceOf(BootstrapResourceCleanupException.class)
                .cause()
                .isSameAs(cleanup);
        assertThat(primary.getSuppressed()).isEmpty();
        assertThat(BootstrapCleanupFailures.contains(outcome, primary)).isTrue();
        assertThat(BootstrapCleanupFailures.contains(primary, outcome)).isFalse();
    }

    @Test
    void resourceLabelUsesOnlyPrimaryCauseChain() {
        // given
        BootstrapResourceCleanupException causeLabel = new BootstrapResourceCleanupException(
                "cause-resource",
                new IllegalStateException("Cause failure")
        );
        IllegalStateException root = new IllegalStateException("Root failure", causeLabel);
        BootstrapResourceCleanupException suppressedLabel = new BootstrapResourceCleanupException(
                "suppressed-resource",
                new IllegalStateException("Suppressed failure")
        );
        LinkageError rawFatal = new LinkageError("Raw fatal failure");
        rawFatal.addSuppressed(suppressedLabel);

        // when
        String causeResource = BootstrapCleanupFailures.resourceLabel(root);
        String suppressedResource = BootstrapCleanupFailures.resourceLabel(rawFatal);

        // then
        assertThat(causeResource).isEqualTo("cause-resource");
        assertThat(suppressedResource).isEqualTo("unknown");
    }

    @Test
    void resourceLabelTerminatesOnCauseCycle() {
        // given
        IllegalStateException first = new IllegalStateException("First failure");
        IllegalArgumentException second = new IllegalArgumentException("Second failure", first);
        first.initCause(second);

        // when
        String resourceLabel = BootstrapCleanupFailures.resourceLabel(first);

        // then
        assertThat(resourceLabel).isEqualTo("unknown");
    }

    @Test
    void resourceLabelPropagatesOnlyFatalCauseAccessorFailure() {
        // given
        LinkageError accessorFailure = new LinkageError("Fatal cause inspection failure");
        ThrowingCauseException fatalAccessor = new ThrowingCauseException("Fatal accessor failure", () -> accessorFailure);
        ThrowingCauseException ordinaryAccessor = new ThrowingCauseException(
                "Ordinary accessor failure",
                () -> new IllegalStateException("Cause inspection failed")
        );

        // when
        Throwable fatalOutcome = catchThrowable(() -> BootstrapCleanupFailures.resourceLabel(fatalAccessor));
        String ordinaryLabel = BootstrapCleanupFailures.resourceLabel(ordinaryAccessor);

        // then
        assertThat(fatalOutcome).isSameAs(accessorFailure);
        assertThat(ordinaryLabel).isEqualTo("unknown");
    }

    @Test
    void rethrowWrapsCheckedFailureInsteadOfCastingIt() {
        // given
        Exception checked = new Exception("Checked cleanup failure");

        // when
        Throwable outcome = catchThrowable(() -> BootstrapCleanupFailures.rethrow(checked));

        // then
        assertThat(outcome)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Checked failure cannot be rethrown by bootstrap cleanup")
                .cause()
                .isSameAs(checked);
    }

    private static Stream<Arguments> promotionOrders() {
        return Stream.of(
                Arguments.of("assertion then linkage", true),
                Arguments.of("linkage then assertion", false)
        );
    }

    private static Stream<Arguments> throwingCausePositions() {
        return Stream.of(
                Arguments.of("throwing primary", true),
                Arguments.of("throwing secondary", false)
        );
    }

    private static final class ThrowingCauseException extends IllegalStateException {

        private final transient Supplier<? extends Throwable> accessorFailure;

        private ThrowingCauseException(String message, Supplier<? extends Throwable> accessorFailure) {
            super(message);
            this.accessorFailure = accessorFailure;
        }

        @Override
        public synchronized Throwable getCause() {
            Throwable failure = accessorFailure.get();
            if (failure instanceof Error error) {
                throw error;
            }

            throw (RuntimeException) failure;
        }
    }

    private static final class ThrowingCauseOutOfMemoryError extends OutOfMemoryError {

        private final transient LinkageError accessorFailure;

        private ThrowingCauseOutOfMemoryError(String message, LinkageError accessorFailure) {
            super(message);
            this.accessorFailure = accessorFailure;
        }

        @Override
        public synchronized Throwable getCause() {
            throw accessorFailure;
        }
    }

    private static final class NonSuppressingException extends RuntimeException {

        private NonSuppressingException(String message) {
            super(message, null, false, true);
        }
    }
}
