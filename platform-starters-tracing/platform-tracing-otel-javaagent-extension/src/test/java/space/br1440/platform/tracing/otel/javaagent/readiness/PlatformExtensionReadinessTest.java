package space.br1440.platform.tracing.otel.javaagent.readiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static space.br1440.platform.tracing.otel.javaagent.readiness.ReadinessSnapshotAssertions.failureOf;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;

class PlatformExtensionReadinessTest {

    private static final String EXTENSION_VERSION = "1.2.3";
    private static final String DEFAULT_FAILURE_CODE = "EXTENSION_INITIALIZATION_FAILED";

    @Test
    void readyRequiresEveryDeclaredCapability() {
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness(EXTENSION_VERSION);
        PlatformExtensionCapability[] capabilities = PlatformExtensionCapability.values();

        for (int index = 0; index < capabilities.length - 1; index++) {
            PlatformExtensionReadinessSnapshot snapshot = readiness.markInstalled(capabilities[index]);
            assertThat(snapshot.lifecycle()).isEqualTo(PlatformExtensionLifecycle.INITIALIZING);
        }

        PlatformExtensionReadinessSnapshot complete =
                readiness.markInstalled(capabilities[capabilities.length - 1]);

        assertThat(complete.lifecycle()).isEqualTo(PlatformExtensionLifecycle.INITIALIZING);
        assertThat(complete.missingRequiredCapabilities()).isEmpty();

        PlatformExtensionReadinessSnapshot ready = readiness.completeInitialization();
        assertThat(ready.lifecycle()).isEqualTo(PlatformExtensionLifecycle.READY);
        assertThat(ready.installedCapabilities()).containsExactlyInAnyOrder(capabilities);
        assertThat(ready.missingRequiredCapabilities()).isEmpty();
        assertThat(readiness.snapshot()).isSameAs(ready);
        assertThat(readiness.completeInitialization()).isSameAs(ready);
    }

    @Test
    void generatedWireContractRequiresExactlyTheDeclaredCapabilities() {
        List<String> declaredCapabilities = Arrays.stream(PlatformExtensionCapability.values())
                .map(Enum::name)
                .toList();

        assertThat(JmxWireContract.REQUIRED_CAPABILITIES)
                .containsExactlyInAnyOrderElementsOf(declaredCapabilities);
    }

    @Test
    void snapshotsAreImmutableStateGenerations() {
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness(EXTENSION_VERSION);
        PlatformExtensionReadinessSnapshot initial = readiness.snapshot();

        PlatformExtensionReadinessSnapshot updated =
                readiness.markInstalled(PlatformExtensionCapability.CONFIGURATION_LOADED);

        assertThat(updated).isNotSameAs(initial);
        assertThat(initial.extensionVersion()).isEqualTo(EXTENSION_VERSION);
        assertThat(initial.installedCapabilities()).isEmpty();
        assertThat(initial.missingRequiredCapabilities())
                .containsExactlyInAnyOrder(PlatformExtensionCapability.values());
        assertThat(updated.installedCapabilities())
                .containsExactly(PlatformExtensionCapability.CONFIGURATION_LOADED);
        assertThatThrownBy(() -> initial.installedCapabilities()
                .add(PlatformExtensionCapability.CONFIGURATION_LOADED))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> initial.missingRequiredCapabilities()
                .remove(PlatformExtensionCapability.CONFIGURATION_LOADED))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void snapshotDefensivelyCopiesCapabilitySets() {
        // given
        EnumSet<PlatformExtensionCapability> installed = EnumSet.of(PlatformExtensionCapability.CONFIGURATION_LOADED);

        // when
        PlatformExtensionReadinessSnapshot snapshot = new PlatformExtensionReadinessSnapshot.Initializing(EXTENSION_VERSION, installed);
        installed.clear();

        // then
        assertThat(snapshot.installedCapabilities())
                .containsExactly(PlatformExtensionCapability.CONFIGURATION_LOADED);
        assertThat(snapshot.missingRequiredCapabilities())
                .doesNotContain(PlatformExtensionCapability.CONFIGURATION_LOADED)
                .hasSize(PlatformExtensionCapability.values().length - 1);
    }

    @Test
    void readinessFailureRejectsMalformedCodes() {
        assertThatThrownBy(() -> new ReadinessFailure("", "EFFECTIVE_FAILURE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("primaryCode");
        assertThatThrownBy(() -> new ReadinessFailure("PRIMARY_FAILURE", "lower_case"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effectiveCode");
    }

    @Test
    void capabilityInstalledAfterReadyReusesTheReadyGeneration() {
        // given
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness(EXTENSION_VERSION);
        installAllCapabilities(readiness);
        PlatformExtensionReadinessSnapshot ready = readiness.completeInitialization();

        // when
        PlatformExtensionReadinessSnapshot late =
                readiness.markInstalled(PlatformExtensionCapability.SANITIZER_INSTALLED);

        // then
        assertThat(late).isSameAs(ready);
        assertThat(readiness.snapshot()).isSameAs(ready);
    }

    @Test
    void invalidFailureCodesFallBackWithoutMaskingTheFailureTransition() {
        String[] invalidCodes = {
                null,
                "",
                "   ",
                "lower_case",
                "1_INVALID",
                "INVALID-CODE",
                " INVALID_CODE",
                "A".repeat(129)
        };

        for (String invalidCode : invalidCodes) {
            PlatformExtensionReadiness readiness = new PlatformExtensionReadiness(EXTENSION_VERSION);

            PlatformExtensionReadinessSnapshot.Failed failed = readiness.fail(invalidCode, invalidCode);

            assertThat(failed.lifecycle()).isEqualTo(PlatformExtensionLifecycle.FAILED);
            assertThat(failed.failure().primaryCode()).isEqualTo(DEFAULT_FAILURE_CODE);
            assertThat(failed.failure().effectiveCode()).isEqualTo(DEFAULT_FAILURE_CODE);
        }

        PlatformExtensionReadiness boundary = new PlatformExtensionReadiness(EXTENSION_VERSION);
        PlatformExtensionReadinessSnapshot.Failed failed = boundary.fail("A".repeat(128), "B".repeat(128));
        assertThat(failed.failure().primaryCode()).hasSize(128);
        assertThat(failed.failure().effectiveCode()).hasSize(128);
    }

    @Test
    void repeatedFailurePairReusesCurrentGenerationAndLateCapabilitiesAreIgnored() {
        // given
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness(EXTENSION_VERSION);
        PlatformExtensionReadinessSnapshot first = readiness.fail("FIRST_FAILURE", "FIRST_FAILURE");

        // when
        PlatformExtensionReadinessSnapshot repeated = readiness.fail("FIRST_FAILURE", "FIRST_FAILURE");
        PlatformExtensionReadinessSnapshot late =
                readiness.markInstalled(PlatformExtensionCapability.SANITIZER_INSTALLED);
        Throwable completion = catchThrowable(readiness::completeInitialization);

        // then
        assertThat(repeated).isSameAs(first);
        assertThat(late).isSameAs(first);
        assertThat(completion)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lifecycle=FAILED");
        assertThat(readiness.snapshot()).isSameAs(first);
        assertThat(failureOf(readiness.snapshot())).isEqualTo(new ReadinessFailure("FIRST_FAILURE", "FIRST_FAILURE"));
        assertThat(readiness.snapshot().installedCapabilities()).isEmpty();
    }

    @Test
    void effectiveFailureCodeEscalatesWithoutReplacingPrimaryCode() {
        // given
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness(EXTENSION_VERSION);
        PlatformExtensionReadinessSnapshot preliminary = readiness.fail("ACTIVATION_FAILED", "ACTIVATION_FAILED");

        // when
        PlatformExtensionReadinessSnapshot.Failed terminal = readiness.fail(
                "ACTIVATION_FAILED",
                "FAILED_ROLLBACK_INCOMPLETE"
        );

        // then
        assertThat(terminal).isNotSameAs(preliminary);
        assertThat(terminal.lifecycle()).isEqualTo(PlatformExtensionLifecycle.FAILED);
        assertThat(terminal.failure().primaryCode()).isEqualTo("ACTIVATION_FAILED");
        assertThat(terminal.failure().effectiveCode()).isEqualTo("FAILED_ROLLBACK_INCOMPLETE");
    }

    @Test
    void completionReportsTheCapturedMissingCapabilities() {
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness(EXTENSION_VERSION);
        readiness.markInstalled(PlatformExtensionCapability.CONFIGURATION_LOADED);

        assertThatThrownBy(readiness::completeInitialization)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INITIALIZING")
                .hasMessageContaining("PLATFORM_SAMPLER_INSTALLED");

        installAllCapabilities(readiness);
        assertThatCode(readiness::completeInitialization).doesNotThrowAnyException();
    }

    @Test
    void unresolvedVersionCannotBecomeReady() {
        // given
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness("");
        installAllCapabilities(readiness);

        // when
        Throwable failure = catchThrowable(readiness::completeInitialization);

        // then
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Platform extension readiness is not READY: extensionVersion is missing");
    }

    @Test
    void readyStateCanStillTransitionToTerminalFailure() {
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness(EXTENSION_VERSION);
        installAllCapabilities(readiness);
        PlatformExtensionReadinessSnapshot ready = readiness.completeInitialization();

        PlatformExtensionReadinessSnapshot.Failed failed = readiness.fail("LATE_FAILURE", "LATE_FAILURE");

        assertThat(ready.lifecycle()).isEqualTo(PlatformExtensionLifecycle.READY);
        assertThat(failed.lifecycle()).isEqualTo(PlatformExtensionLifecycle.FAILED);
        assertThat(failed.failure()).isEqualTo(new ReadinessFailure("LATE_FAILURE", "LATE_FAILURE"));
        assertThat(failed.installedCapabilities()).containsExactlyInAnyOrder(PlatformExtensionCapability.values());
        assertThatThrownBy(readiness::completeInitialization).isInstanceOf(IllegalStateException.class);
        assertThat(readiness.snapshot()).isSameAs(failed);
    }

    @Test
    void duplicateInstallationReusesTheCurrentGeneration() {
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness(EXTENSION_VERSION);

        PlatformExtensionReadinessSnapshot installed =
                readiness.markInstalled(PlatformExtensionCapability.CONFIGURATION_LOADED);

        PlatformExtensionReadinessSnapshot duplicate =
                readiness.markInstalled(PlatformExtensionCapability.CONFIGURATION_LOADED);

        assertThat(duplicate).isSameAs(installed);
    }

    @Test
    @Timeout(10)
    void concurrentCapabilityInstallationDoesNotLoseUpdates() throws Exception {
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness(EXTENSION_VERSION);
        PlatformExtensionCapability[] capabilities = PlatformExtensionCapability.values();
        CountDownLatch poised = new CountDownLatch(capabilities.length);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(capabilities.length)) {
            try {
                List<Future<PlatformExtensionReadinessSnapshot>> futures = Arrays.stream(capabilities)
                        .map(capability -> executor.submit(() -> {
                            poised.countDown();
                            requireLatch(start);
                            return readiness.markInstalled(capability);
                        }))
                        .toList();

                assertThat(poised.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();

                for (Future<PlatformExtensionReadinessSnapshot> future : futures) {
                    future.get(5, TimeUnit.SECONDS);
                }
            } finally {
                start.countDown();
            }
        }

        PlatformExtensionReadinessSnapshot capabilitiesComplete = readiness.snapshot();
        assertThat(capabilitiesComplete.lifecycle()).isEqualTo(PlatformExtensionLifecycle.INITIALIZING);
        assertThat(capabilitiesComplete.missingRequiredCapabilities()).isEmpty();

        PlatformExtensionReadinessSnapshot completed = readiness.completeInitialization();
        assertThat(completed.lifecycle()).isEqualTo(PlatformExtensionLifecycle.READY);
        assertThat(completed.installedCapabilities()).containsExactlyInAnyOrder(capabilities);
        assertThat(completed.missingRequiredCapabilities()).isEmpty();
    }

    @Test
    @Timeout(15)
    void failureWinsEveryRaceWithTheLastCapabilityInstallation() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            for (int attempt = 0; attempt < 100; attempt++) {
                PlatformExtensionReadiness readiness = readinessMissingLastCapability();
                CountDownLatch poised = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);

                try {
                    Future<PlatformExtensionReadinessSnapshot> installation = executor.submit(() -> {
                        poised.countDown();
                        requireLatch(start);
                        return readiness.markInstalled(PlatformExtensionCapability.EXPORT_PATH_PROTECTED);
                    });
                    Future<PlatformExtensionReadinessSnapshot> failure = executor.submit(() -> {
                        poised.countDown();
                        requireLatch(start);
                        return readiness.fail("CONCURRENT_FAILURE", "CONCURRENT_FAILURE");
                    });

                    assertThat(poised.await(5, TimeUnit.SECONDS)).isTrue();
                    start.countDown();
                    installation.get(5, TimeUnit.SECONDS);
                    failure.get(5, TimeUnit.SECONDS);

                    PlatformExtensionReadinessSnapshot terminal = readiness.snapshot();
                    assertThat(terminal.lifecycle()).isEqualTo(PlatformExtensionLifecycle.FAILED);
                    assertThat(failureOf(terminal))
                            .isEqualTo(new ReadinessFailure("CONCURRENT_FAILURE", "CONCURRENT_FAILURE"));
                    assertThat(terminal.installedCapabilities().size())
                            .isIn(
                                    PlatformExtensionCapability.values().length - 1,
                                    PlatformExtensionCapability.values().length
                            );
                } finally {
                    start.countDown();
                }
            }
        }
    }

    @Test
    void nullCapabilityIsRejectedAtTheApiBoundary() {
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness(EXTENSION_VERSION);

        assertThatThrownBy(() -> readiness.markInstalled(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> readiness.snapshot().has(null))
                .isInstanceOf(NullPointerException.class);
    }

    private static PlatformExtensionReadiness readinessMissingLastCapability() {
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness(EXTENSION_VERSION);
        for (PlatformExtensionCapability capability : PlatformExtensionCapability.values()) {
            if (capability != PlatformExtensionCapability.EXPORT_PATH_PROTECTED) {
                readiness.markInstalled(capability);
            }
        }
        return readiness;
    }

    private static void installAllCapabilities(PlatformExtensionReadiness readiness) {
        for (PlatformExtensionCapability capability : PlatformExtensionCapability.values()) {
            readiness.markInstalled(capability);
        }
    }

    private static void requireLatch(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out while waiting for concurrent test start");
        }
    }
}
