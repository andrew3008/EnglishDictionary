package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaConstructor.CONSTRUCTOR_NAME;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapResources;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.JvmShutdownHookRegistry;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.ShutdownHookRegistry;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadinessSnapshot;

@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.otel.javaagent",
        importOptions = ImportOption.DoNotIncludeTests.class)
class BootstrapFailureOwnershipArchTest {

    private static final DescribedPredicate<JavaAccess<?>> LIFECYCLE_FAIL_ACCESS =
            targetIs(BootstrapLifecycleState.class, "fail");
    private static final DescribedPredicate<JavaAccess<?>> LIFECYCLE_ESCALATION_ACCESS =
            targetIs(BootstrapLifecycleState.class, "escalateRollbackIncomplete");
    private static final DescribedPredicate<JavaAccess<?>> JOURNAL_FAILURE_ACCESS =
            targetIs(BootstrapReadinessJournal.class, "publishFailed");
    private static final DescribedPredicate<JavaAccess<?>> READINESS_FAILURE_ACCESS =
            targetIs(PlatformExtensionReadiness.class, "fail");
    private static final DescribedPredicate<JavaAccess<?>> FAILURE_CONSTRUCTION_ACCESS =
            targetIs(BootstrapFailure.class, CONSTRUCTOR_NAME);
    private static final DescribedPredicate<JavaAccess<?>> DIAGNOSTIC_REGISTRATION_ACCESS =
            targetIs(DiagnosticReadinessHandle.class, "registerIfAbsent");
    private static final DescribedPredicate<JavaAccess<?>> LIFECYCLE_CLOSE_ACQUISITION_ACCESS =
            targetIs(BootstrapLifecycleState.class, "acquireClose");
    private static final DescribedPredicate<JavaAccess<?>> LIFECYCLE_CLOSE_COMPLETION_ACCESS =
            targetIs(BootstrapLifecycleState.class, "completeClose");
    private static final DescribedPredicate<JavaAccess<?>> LIFECYCLE_MARK_CLOSED_ACCESS =
            targetIs(BootstrapLifecycleState.class, "markClosed");
    private static final DescribedPredicate<JavaAccess<?>> DIAGNOSTIC_CLOSE_ACCESS =
            targetIs(DiagnosticReadinessHandle.class, "close");
    private static final DescribedPredicate<JavaAccess<?>> SHUTDOWN_HOOK_REGISTRATION_ACCESS =
            targetIs(ShutdownHookRegistry.class, "register");
    private static final DescribedPredicate<JavaAccess<?>> JVM_HOOK_INSTALLATION_ACCESS =
            targetIs(Runtime.class, "addShutdownHook");
    private static final DescribedPredicate<JavaAccess<?>> JVM_HOOK_RELEASE_ACCESS =
            targetIs(Runtime.class, "removeShutdownHook");

    @ArchTest
    @SuppressWarnings("unused")
    static void only_terminal_transitions_can_fail_or_escalate_lifecycle(JavaClasses classes) {
        // given
        ArchRule rule = lifecycleFailureRule();

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertAllowedProductionAccessExists(
                classes,
                BootstrapTerminalTransitions.class,
                LIFECYCLE_FAIL_ACCESS
        );
        assertAllowedProductionAccessExists(
                classes,
                BootstrapTerminalTransitions.class,
                LIFECYCLE_ESCALATION_ACCESS
        );
        assertThat(result.getFailureReport().getDetails()).isEmpty();
    }

    @ArchTest
    @SuppressWarnings("unused")
    static void only_terminal_transitions_can_publish_failed_readiness(JavaClasses classes) {
        // given
        ArchRule rule = journalFailureRule();

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertAllowedProductionAccessExists(
                classes,
                BootstrapTerminalTransitions.class,
                JOURNAL_FAILURE_ACCESS
        );
        assertThat(result.getFailureReport().getDetails()).isEmpty();
    }

    @ArchTest
    @SuppressWarnings("unused")
    static void only_readiness_journal_can_fail_platform_readiness(JavaClasses classes) {
        // given
        ArchRule rule = readinessFailureRule();

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertAllowedProductionAccessExists(
                classes,
                BootstrapReadinessJournal.class,
                READINESS_FAILURE_ACCESS
        );
        assertThat(result.getFailureReport().getDetails()).isEmpty();
    }

    @ArchTest
    @SuppressWarnings("unused")
    static void only_lifecycle_can_construct_bootstrap_failure(JavaClasses classes) {
        // given
        ArchRule rule = failureConstructionRule();

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertAllowedProductionAccessExists(
                classes,
                BootstrapLifecycleState.class,
                FAILURE_CONSTRUCTION_ACCESS
        );
        assertThat(result.getFailureReport().getDetails()).isEmpty();
    }

    @ArchTest
    @SuppressWarnings("unused")
    static void only_coordinator_can_register_diagnostic_readiness(JavaClasses classes) {
        // given
        ArchRule rule = diagnosticRegistrationRule();

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertAllowedProductionAccessExists(
                classes,
                BootstrapCoordinator.class,
                DIAGNOSTIC_REGISTRATION_ACCESS
        );
        assertThat(result.getFailureReport().getDetails()).isEmpty();
    }

    @ArchTest
    @SuppressWarnings("unused")
    static void only_coordinator_can_orchestrate_lifecycle_close(JavaClasses classes) {
        // given
        ArchRule rule = closeOwnershipRule();

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertAllowedProductionAccessExists(
                classes,
                BootstrapCoordinator.class,
                LIFECYCLE_CLOSE_ACQUISITION_ACCESS
        );
        assertAllowedProductionAccessExists(
                classes,
                BootstrapCoordinator.class,
                LIFECYCLE_CLOSE_COMPLETION_ACCESS
        );
        assertAllowedProductionAccessExists(
                classes,
                BootstrapCoordinator.class,
                LIFECYCLE_MARK_CLOSED_ACCESS
        );
        assertAllowedProductionAccessExists(
                classes,
                BootstrapCoordinator.class,
                DIAGNOSTIC_CLOSE_ACCESS
        );
        assertThat(classes.get(BootstrapLifecycleState.class).getFields())
                .anySatisfy(field -> assertThat(field.getRawType()
                        .isEquivalentTo(BootstrapLifecycleState.CloseFlight.class)).isTrue());
        assertThat(result.getFailureReport().getDetails()).isEmpty();
    }

    @ArchTest
    @SuppressWarnings("unused")
    static void only_coordinator_can_register_the_process_lifetime_hook(JavaClasses classes) {
        // given
        ArchRule rule = shutdownHookRegistrationRule();

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertCoordinatorRegistersShutdownHookOnce(classes);
        assertThat(result.getFailureReport().getDetails()).isEmpty();
    }

    @ArchTest
    @SuppressWarnings("unused")
    static void process_lifetime_hook_has_no_release_path(JavaClasses classes) {
        // given
        ArchRule rule = shutdownHookReleaseRule();

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertAllowedProductionAccessExists(
                classes,
                JvmShutdownHookRegistry.class,
                JVM_HOOK_INSTALLATION_ACCESS
        );
        assertThat(ShutdownHookRegistry.class.getDeclaredMethods()).singleElement().satisfies(method -> {
            assertThat(method.getName()).isEqualTo("register");
            assertThat(method.getReturnType()).isEqualTo(void.class);
            assertThat(method.getParameterTypes()).containsExactly(String.class, Runnable.class);
        });
        assertThat(result.getFailureReport().getDetails()).isEmpty();
    }

    @ArchTest
    @SuppressWarnings("unused")
    static void activation_and_resources_do_not_depend_on_shutdown_hook_registry(JavaClasses classes) {
        // given
        ArchRule rule = noShutdownHookRegistryDependencyRule(
                BootstrapActivation.class,
                BootstrapResources.class
        );

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertProductionClassesExist(
                classes,
                BootstrapActivation.class,
                BootstrapResources.class,
                ShutdownHookRegistry.class
        );
        assertThat(result.getFailureReport().getDetails()).isEmpty();
    }

    @ArchTest
    @SuppressWarnings("unused")
    static void resources_do_not_depend_on_completion_callback_or_bootstrap_logger(JavaClasses classes) {
        // given
        ArchRule rule = noResourceCompletionDependencyRule(BootstrapResources.class);

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertProductionClassesExist(
                classes,
                BootstrapResources.class,
                PlatformAgentLogger.class
        );
        assertThat(result.getFailureReport().getDetails()).isEmpty();
    }

    @Test
    void lifecycle_rule_detects_direct_call_origin() {
        // given
        ArchRule rule = lifecycleFailureRule();
        JavaClasses classes = importFixture(
                DirectLifecycleFailureAccessFixture.class,
                BootstrapLifecycleState.class
        );

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertViolation(
                classes,
                result,
                BootstrapTerminalTransitions.class,
                DirectLifecycleFailureAccessFixture.class,
                DirectLifecycleFailureAccessFixture.class.getName()
        );
    }

    @Test
    void lifecycle_rule_detects_method_reference_origin() {
        // given
        ArchRule rule = lifecycleFailureRule();
        JavaClasses classes = importFixture(
                LifecycleEscalationMethodReferenceFixture.class,
                BootstrapLifecycleState.class
        );

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertViolation(
                classes,
                result,
                BootstrapTerminalTransitions.class,
                LifecycleEscalationMethodReferenceFixture.class,
                LifecycleEscalationMethodReferenceFixture.class.getName()
        );
    }

    @Test
    void journal_rule_detects_lambda_origin() {
        // given
        ArchRule rule = journalFailureRule();
        JavaClasses classes = importFixture(
                JournalFailureLambdaFixture.class,
                BootstrapReadinessJournal.class
        );

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertViolation(
                classes,
                result,
                BootstrapTerminalTransitions.class,
                JournalFailureLambdaFixture.class,
                JournalFailureLambdaFixture.class.getName()
        );
    }

    @Test
    void readiness_rule_detects_anonymous_class_origin() {
        // given
        Class<?> fixture = ReadinessFailureAnonymousFixture.access().getClass();
        ArchRule rule = readinessFailureRule();
        JavaClasses classes = importFixture(
                fixture,
                PlatformExtensionReadiness.class
        );

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertViolation(
                classes,
                result,
                BootstrapReadinessJournal.class,
                fixture,
                fixture.getName()
        );
    }

    @Test
    void constructor_rule_detects_constructor_reference_origin() {
        // given
        ArchRule rule = failureConstructionRule();
        JavaClasses classes = importFixture(
                BootstrapFailureConstructorReferenceFixture.class,
                BootstrapFailure.class
        );

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertViolation(
                classes,
                result,
                BootstrapLifecycleState.class,
                BootstrapFailureConstructorReferenceFixture.class,
                BootstrapFailureConstructorReferenceFixture.class.getName()
        );
    }

    @Test
    void diagnostic_registration_rule_detects_direct_call_origin() {
        // given
        ArchRule rule = diagnosticRegistrationRule();
        JavaClasses classes = importFixture(
                DiagnosticReadinessRegistrationFixture.class,
                DiagnosticReadinessHandle.class
        );

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertViolation(
                classes,
                result,
                BootstrapCoordinator.class,
                DiagnosticReadinessRegistrationFixture.class,
                DiagnosticReadinessRegistrationFixture.class.getName()
        );
    }

    @Test
    void close_ownership_rule_detects_direct_call_origin() {
        // given
        ArchRule rule = closeOwnershipRule();
        JavaClasses classes = new ClassFileImporter().importClasses(
                CloseOrchestrationAccessFixture.class,
                BootstrapLifecycleState.class,
                DiagnosticReadinessHandle.class
        );

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertViolation(
                classes,
                result,
                BootstrapCoordinator.class,
                CloseOrchestrationAccessFixture.class,
                CloseOrchestrationAccessFixture.class.getName()
        );
    }

    @Test
    void shutdown_hook_registration_rule_detects_direct_call_origin() {
        // given
        ArchRule rule = shutdownHookRegistrationRule();
        JavaClasses classes = importFixture(
                ShutdownHookRegistrationFixture.class,
                ShutdownHookRegistry.class
        );

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertViolation(
                classes,
                result,
                BootstrapCoordinator.class,
                ShutdownHookRegistrationFixture.class,
                ShutdownHookRegistrationFixture.class.getName()
        );
    }

    @Test
    void shutdown_hook_release_rule_detects_direct_call_origin() {
        // given
        ArchRule rule = shutdownHookReleaseRule();
        JavaClasses classes = importFixture(
                ShutdownHookReleaseFixture.class,
                Runtime.class
        );

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertViolation(
                classes,
                result,
                JvmShutdownHookRegistry.class,
                ShutdownHookReleaseFixture.class,
                ShutdownHookReleaseFixture.class.getName()
        );
    }

    @Test
    void shutdown_registry_dependency_rule_detects_field_origin() {
        // given
        ArchRule rule = noShutdownHookRegistryDependencyRule(ShutdownHookRegistryDependencyFixture.class);
        JavaClasses classes = importFixture(
                ShutdownHookRegistryDependencyFixture.class,
                ShutdownHookRegistry.class
        );

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertRuleViolation(
                classes,
                result,
                ShutdownHookRegistryDependencyFixture.class,
                ShutdownHookRegistryDependencyFixture.class.getName()
        );
    }

    @Test
    void resource_dependency_rule_detects_completion_and_logger_origins() {
        // given
        ArchRule rule = noResourceCompletionDependencyRule(ResourceCompletionDependencyFixture.class);
        JavaClasses classes = new ClassFileImporter().importClasses(
                ResourceCompletionDependencyFixture.class,
                Consumer.class,
                PlatformAgentLogger.class
        );

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertRuleViolation(
                classes,
                result,
                ResourceCompletionDependencyFixture.class,
                ResourceCompletionDependencyFixture.class.getName()
        );
    }

    @Test
    void access_builder_detects_field_access_origin() {
        // given
        ArchRule rule = onlyOwner(
                TestOnlyOwner.class,
                targetIs(FieldAccessOwnedTarget.class, "guarded")
        );
        JavaClasses classes = importFixture(
                FieldAccessFixture.class,
                FieldAccessOwnedTarget.class
        );

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertViolation(
                classes,
                result,
                TestOnlyOwner.class,
                FieldAccessFixture.class,
                FieldAccessFixture.class.getName()
        );
    }

    @Test
    void nested_owner_is_included_in_allowed_owner_boundary() {
        // given
        DescribedPredicate<JavaAccess<?>> access = targetIs(TestOnlyOwnedTarget.class, "touch");
        ArchRule rule = onlyOwner(TestOnlyOwner.class, access);
        JavaClasses classes = new ClassFileImporter().importClasses(
                TestOnlyOwner.class,
                TestOnlyOwner.NestedOwner.class,
                TestOnlyOwnedTarget.class
        );

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertThat(classes.get(TestOnlyOwner.NestedOwner.class).getAccessesFromSelf())
                .anyMatch(access);
        assertThat(belongToAnyOf(TestOnlyOwner.class).test(classes.get(TestOnlyOwner.NestedOwner.class)))
                .isTrue();
        assertThat(result.getFailureReport().getDetails()).isEmpty();
    }

    private static ArchRule lifecycleFailureRule() {
        return onlyOwner(
                BootstrapTerminalTransitions.class,
                LIFECYCLE_FAIL_ACCESS.or(LIFECYCLE_ESCALATION_ACCESS)
        );
    }

    private static ArchRule journalFailureRule() {
        return onlyOwner(
                BootstrapTerminalTransitions.class,
                JOURNAL_FAILURE_ACCESS
        );
    }

    private static ArchRule readinessFailureRule() {
        return onlyOwner(
                BootstrapReadinessJournal.class,
                READINESS_FAILURE_ACCESS
        );
    }

    private static ArchRule failureConstructionRule() {
        return onlyOwner(
                BootstrapLifecycleState.class,
                FAILURE_CONSTRUCTION_ACCESS
        );
    }

    private static ArchRule diagnosticRegistrationRule() {
        return onlyOwner(
                BootstrapCoordinator.class,
                DIAGNOSTIC_REGISTRATION_ACCESS
        );
    }

    private static ArchRule closeOwnershipRule() {
        return onlyOwner(
                BootstrapCoordinator.class,
                LIFECYCLE_CLOSE_ACQUISITION_ACCESS
                        .or(LIFECYCLE_CLOSE_COMPLETION_ACCESS)
                        .or(LIFECYCLE_MARK_CLOSED_ACCESS)
                        .or(DIAGNOSTIC_CLOSE_ACCESS)
        );
    }

    private static ArchRule shutdownHookRegistrationRule() {
        return onlyOwner(
                BootstrapCoordinator.class,
                SHUTDOWN_HOOK_REGISTRATION_ACCESS
        );
    }

    private static ArchRule shutdownHookReleaseRule() {
        return noClasses()
                .should().accessTargetWhere(JVM_HOOK_RELEASE_ACCESS)
                .because("the bootstrap shutdown hook is a process-lifetime lifecycle guard");
    }

    private static ArchRule noShutdownHookRegistryDependencyRule(Class<?>... owners) {
        return noDependencyRule(owners, ShutdownHookRegistry.class);
    }

    private static ArchRule noResourceCompletionDependencyRule(Class<?>... owners) {
        return noDependencyRule(owners, Consumer.class, PlatformAgentLogger.class);
    }

    private static ArchRule noDependencyRule(Class<?>[] owners, Class<?>... forbiddenDependencies) {
        return noClasses()
                .that(belongToAnyOf(owners))
                .should().dependOnClassesThat(belongToAnyOf(forbiddenDependencies))
                .because("bootstrap resource ownership must not leak into completion or logging infrastructure");
    }

    private static ArchRule onlyOwner(Class<?> owner, DescribedPredicate<JavaAccess<?>> access) {
        return noClasses()
                .that(not(belongToAnyOf(owner)))
                .should().accessTargetWhere(access)
                .because(owner.getSimpleName() + " is the single owner of the guarded bootstrap transition");
    }

    private static DescribedPredicate<JavaAccess<?>> targetIs(Class<?> owner, String name) {
        return new DescribedPredicate<>("access %s.%s", owner.getName(), name) {

            @Override
            public boolean test(JavaAccess<?> access) {
                return owner.getName().equals(access.getTargetOwner().getName())
                        && name.equals(access.getTarget().getName());
            }
        };
    }

    private static JavaClasses importFixture(Class<?> fixture, Class<?> target) {
        return new ClassFileImporter().importClasses(fixture, target);
    }

    private static void assertAllowedProductionAccessExists(
            JavaClasses classes,
            Class<?> allowedOwner,
            DescribedPredicate<JavaAccess<?>> target) {
        assertThat(classes.stream()
                .flatMap(javaClass -> javaClass.getAccessesFromSelf().stream())
                .anyMatch(access -> belongToAnyOf(allowedOwner).test(access.getOriginOwner())
                        && target.test(access)))
                .as("production access from %s to %s", allowedOwner.getName(), target.getDescription())
                .isTrue();
    }

    private static void assertCoordinatorRegistersShutdownHookOnce(JavaClasses classes) {
        assertThat(classes.stream()
                .flatMap(javaClass -> javaClass.getAccessesFromSelf().stream())
                .filter(access -> belongToAnyOf(BootstrapCoordinator.class).test(access.getOriginOwner()))
                .filter(SHUTDOWN_HOOK_REGISTRATION_ACCESS)
                .count())
                .as("production access count from %s to %s",
                        BootstrapCoordinator.class.getName(),
                        SHUTDOWN_HOOK_REGISTRATION_ACCESS.getDescription())
                .isEqualTo(1L);
    }

    private static void assertProductionClassesExist(JavaClasses classes, Class<?>... expectedClasses) {
        for (Class<?> expectedClass : expectedClasses) {
            assertThat(classes.contain(expectedClass))
                    .as("production class %s is imported", expectedClass.getName())
                    .isTrue();
        }
    }

    private static void assertViolation(JavaClasses classes,
                                        EvaluationResult result,
                                        Class<?> allowedOwner,
                                        Class<?> fixture,
                                        String expectedOrigin) {
        assertThat(classes.contain(fixture)).isTrue();
        assertThat(belongToAnyOf(allowedOwner).test(classes.get(fixture))).isFalse();
        assertThat(result.hasViolation()).isTrue();
        assertThat(String.join("\n", result.getFailureReport().getDetails()))
                .contains(expectedOrigin);
    }

    private static void assertRuleViolation(JavaClasses classes,
                                            EvaluationResult result,
                                            Class<?> fixture,
                                            String expectedOrigin) {
        assertThat(classes.contain(fixture)).isTrue();
        assertThat(result.hasViolation()).isTrue();
        assertThat(String.join("\n", result.getFailureReport().getDetails()))
                .contains(expectedOrigin);
    }

    @SuppressWarnings("unused")
    private static final class TestOnlyOwner {

        static void touch(TestOnlyOwnedTarget target) {
            target.touch();
        }

        private static final class NestedOwner {

            static void touch(TestOnlyOwnedTarget target) {
                target.touch();
            }
        }
    }

    private static final class TestOnlyOwnedTarget {

        void touch() {
        }
    }
}

@FunctionalInterface
@SuppressWarnings("unused")
interface LifecycleEscalationAccess {

    void escalate(String phase, Throwable cleanupFailure);
}

@FunctionalInterface
@SuppressWarnings("unused")
interface JournalFailureAccess {

    void publish(BootstrapReadinessJournal journal,
                 BootstrapFailure failure,
                 String batchId,
                 ExtensionRuntimeEvidence evidence);
}

@FunctionalInterface
@SuppressWarnings("unused")
interface ReadinessFailureAccess {

    PlatformExtensionReadinessSnapshot fail(PlatformExtensionReadiness readiness,
                                            String primaryCode,
                                            String effectiveCode);
}

@FunctionalInterface
@SuppressWarnings("unused")
interface BootstrapFailureFactory {

    BootstrapFailure create(String primaryCode,
                            String effectiveCode,
                            String phase,
                            Throwable cause);
}

@SuppressWarnings("unused")
final class DirectLifecycleFailureAccessFixture {

    private DirectLifecycleFailureAccessFixture() {
    }

    static BootstrapException fail(BootstrapLifecycleState lifecycle, Throwable cause) {
        return lifecycle.fail("FIXTURE_FAILURE", "fixture", "fixture", null, cause);
    }
}

@SuppressWarnings("unused")
final class LifecycleEscalationMethodReferenceFixture {

    private LifecycleEscalationMethodReferenceFixture() {
    }

    static LifecycleEscalationAccess bind(BootstrapLifecycleState lifecycle) {
        return lifecycle::escalateRollbackIncomplete;
    }
}

@SuppressWarnings("unused")
final class JournalFailureLambdaFixture {

    private JournalFailureLambdaFixture() {
    }

    @SuppressWarnings("Convert2MethodRef")
    static JournalFailureAccess publisher() {
        return (journal, failure, batchId, evidence) -> journal.publishFailed(failure, batchId, evidence);
    }
}

@SuppressWarnings("unused")
final class ReadinessFailureAnonymousFixture {

    private ReadinessFailureAnonymousFixture() {
    }

    @SuppressWarnings({"Anonymous2MethodRef", "Convert2Lambda"})
    static ReadinessFailureAccess access() {
        return new ReadinessFailureAccess() {

            @Override
            public PlatformExtensionReadinessSnapshot fail(PlatformExtensionReadiness readiness,
                                                           String primaryCode,
                                                           String effectiveCode) {
                return readiness.fail(primaryCode, effectiveCode);
            }
        };
    }
}

@SuppressWarnings("unused")
final class BootstrapFailureConstructorReferenceFixture {

    private BootstrapFailureConstructorReferenceFixture() {
    }

    static BootstrapFailureFactory factory() {
        return BootstrapFailure::new;
    }
}

@SuppressWarnings("unused")
final class DiagnosticReadinessRegistrationFixture {

    private DiagnosticReadinessRegistrationFixture() {
    }

    static void register(DiagnosticReadinessHandle handle) {
        handle.registerIfAbsent(() -> () -> {
        });
    }
}

@SuppressWarnings("unused")
final class CloseOrchestrationAccessFixture {

    private CloseOrchestrationAccessFixture() {
    }

    static void close(BootstrapLifecycleState lifecycle, DiagnosticReadinessHandle diagnosticHandle) {
        BootstrapLifecycleState.CloseAcquisition acquisition = lifecycle.acquireClose();
        diagnosticHandle.close();
        lifecycle.markClosed();
        lifecycle.completeClose(acquisition.flight(), null);
    }
}

@SuppressWarnings("unused")
final class ShutdownHookRegistrationFixture {

    private ShutdownHookRegistrationFixture() {
    }

    static void register(ShutdownHookRegistry registry) {
        registry.register("fixture-shutdown-hook", () -> {
        });
    }
}

@SuppressWarnings("unused")
final class ShutdownHookReleaseFixture {

    private ShutdownHookReleaseFixture() {
    }

    static void release(Thread hook) {
        Runtime.getRuntime().removeShutdownHook(hook);
    }
}

@SuppressWarnings("unused")
final class ShutdownHookRegistryDependencyFixture {

    private ShutdownHookRegistry registry;
}

@SuppressWarnings("unused")
final class ResourceCompletionDependencyFixture {

    private Consumer<String> completion;
    private PlatformAgentLogger logger;
}

@SuppressWarnings("unused")
final class FieldAccessFixture {

    private FieldAccessFixture() {
    }

    static String read(FieldAccessOwnedTarget target) {
        return target.guarded;
    }
}

final class FieldAccessOwnedTarget {

    String guarded;
}
