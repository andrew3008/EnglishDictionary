package space.br1440.platform.tracing.otel.javaagent.bootstrap.resources;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

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
        AssertionError fatal = new AssertionError("Fatal failure");
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
        AssertionError firstFatal = new AssertionError("First fatal failure");
        LinkageError secondFatal = new LinkageError("Second fatal failure");
        secondFatal.addSuppressed(firstFatal);

        // when
        Throwable outcome = BootstrapCleanupFailures.aggregate(firstFatal, secondFatal);

        // then
        assertThat(outcome).isSameAs(firstFatal);
        assertThat(firstFatal.getSuppressed()).isEmpty();
        assertThat(secondFatal.getSuppressed()).containsExactly(firstFatal);
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

    @Test
    void suppressPreservingPrimaryAddsExactFatalWithoutPromotion() {
        // given
        IllegalStateException primary = new IllegalStateException("Primary failure");
        AssertionError secondary = new AssertionError("Fatal secondary failure");

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
    void rollbackDoesNotAddEdgeBackToPrimaryFromCleanupCauseChain() {
        // given
        IllegalStateException primary = new IllegalStateException("Primary failure");
        IllegalArgumentException cleanup = new IllegalArgumentException("Cleanup failure", primary);
        BootstrapResources resources = new BootstrapResources();
        resources.push(() -> {
            throw cleanup;
        }, "runtime-controls");

        // when
        Throwable outcome = BootstrapCleanupFailures.rollback(resources, primary);

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
        AssertionError rawFatal = new AssertionError("Raw fatal failure");
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
}
