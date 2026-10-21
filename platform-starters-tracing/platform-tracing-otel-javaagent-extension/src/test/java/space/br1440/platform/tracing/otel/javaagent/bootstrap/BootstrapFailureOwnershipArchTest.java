package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaConstructor.CONSTRUCTOR_NAME;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

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

import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadinessSnapshot;

@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.otel.javaagent",
        importOptions = ImportOption.DoNotIncludeTests.class)
class BootstrapFailureOwnershipArchTest {

    @ArchTest
    @SuppressWarnings("unused")
    static void only_terminal_transitions_can_fail_or_escalate_lifecycle(JavaClasses classes) {
        // given
        ArchRule rule = lifecycleFailureRule();

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
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
        DescribedPredicate<JavaAccess<?>> access = targetIs(BootstrapLifecycleState.class, "fail")
                .or(targetIs(BootstrapLifecycleState.class, "escalateRollbackIncomplete"));
        return onlyOwner(BootstrapTerminalTransitions.class, access);
    }

    private static ArchRule journalFailureRule() {
        return onlyOwner(
                BootstrapTerminalTransitions.class,
                targetIs(BootstrapReadinessJournal.class, "publishFailed")
        );
    }

    private static ArchRule readinessFailureRule() {
        return onlyOwner(
                BootstrapReadinessJournal.class,
                targetIs(PlatformExtensionReadiness.class, "fail")
        );
    }

    private static ArchRule failureConstructionRule() {
        return onlyOwner(
                BootstrapLifecycleState.class,
                targetIs(BootstrapFailure.class, CONSTRUCTOR_NAME)
        );
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
