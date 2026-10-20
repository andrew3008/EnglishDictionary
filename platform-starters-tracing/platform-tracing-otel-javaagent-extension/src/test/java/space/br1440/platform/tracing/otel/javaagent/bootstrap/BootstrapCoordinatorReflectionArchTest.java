package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.syntax.elements.GivenClassesConjunction;

class BootstrapCoordinatorReflectionArchTest {

    private static final String COORDINATOR = "space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinator";

    private static final JavaClasses MODULE_TEST_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
            .importPackages("space.br1440.platform.tracing.otel.javaagent");

    @Test
    void tests_do_not_reach_into_coordinator_fields_reflectively() {
        // given
        ArchRule rule = forbidReflectiveFieldAccess(
                coordinatorDependentClasses(MODULE_TEST_CLASSES)
                        .and().doNotBelongToAnyOf(BootstrapCoordinatorReflectionArchTest.class)
        );

        // when
        // then
        rule.check(MODULE_TEST_CLASSES);
    }

    @Test
    void rule_scope_covers_coordinator_test_classes() {
        // given
        Set<String> scope = coordinatorDependentTopLevelClasses(MODULE_TEST_CLASSES);

        // when
        // then
        assertThat(scope).contains(
                BootstrapCoordinatorTestSupport.class.getName(),
                BootstrapCoordinatorPrepareTest.class.getName(),
                BootstrapCoordinatorShutdownTest.class.getName(),
                RuntimeEvidenceTestFixtures.class.getName()
        );
    }

    @Test
    void rule_detects_reflective_field_access_private_lookup_and_nested_helpers() {
        // given
        JavaClasses fixture = new ClassFileImporter().importClasses(
                ReflectiveCoordinatorAccess.class,
                ReflectiveCoordinatorAccess.NestedHelper.class
        );
        ArchRule rule = forbidReflectiveFieldAccess(coordinatorDependentClasses(fixture));

        // when
        EvaluationResult result = rule.evaluate(fixture);

        // then
        assertThat(result.hasViolation()).isTrue();
        assertThat(String.join("\n", result.getFailureReport().getDetails()))
                .contains("getDeclaredField")
                .contains("privateLookupIn")
                .contains(ReflectiveCoordinatorAccess.NestedHelper.class.getName());
    }

    private static ArchRule forbidReflectiveFieldAccess(GivenClassesConjunction classes) {
        return classes
                .should().callMethodWhere(reflectiveFieldLookup())
                .orShould().dependOnClassesThat().belongToAnyOf(Field.class)
                .orShould().callMethod(MethodHandles.class, "privateLookupIn", Class.class, MethodHandles.Lookup.class)
                .because("coordinator collaborators must be injected through the package-private constructor");
    }

    private static GivenClassesConjunction coordinatorDependentClasses(JavaClasses classes) {
        return noClasses().that(belongToTopLevelClassIn(coordinatorDependentTopLevelClasses(classes)));
    }

    private static Set<String> coordinatorDependentTopLevelClasses(JavaClasses classes) {
        return classes.stream()
                .filter(javaClass -> javaClass.getDirectDependenciesFromSelf().stream()
                        .anyMatch(dependency -> COORDINATOR.equals(dependency.getTargetClass().getName())))
                .map(javaClass -> topLevelClass(javaClass).getName())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static JavaClass topLevelClass(JavaClass javaClass) {
        JavaClass current = javaClass;
        while (current.getEnclosingClass().isPresent()) {
            current = current.getEnclosingClass().get();
        }

        return current;
    }

    private static DescribedPredicate<JavaClass> belongToTopLevelClassIn(Set<String> topLevelClassNames) {
        return new DescribedPredicate<>("belong to a top-level test class depending on BootstrapCoordinator") {

            @Override
            public boolean test(JavaClass javaClass) {
                return topLevelClassNames.contains(topLevelClass(javaClass).getName());
            }
        };
    }

    private static DescribedPredicate<JavaMethodCall> reflectiveFieldLookup() {
        return new DescribedPredicate<>("call Class.getDeclaredField(s) or Class.getField(s)") {

            @Override
            public boolean test(JavaMethodCall call) {
                String name = call.getTarget().getName();
                return "java.lang.Class".equals(call.getTargetOwner().getName())
                        && (name.equals("getDeclaredField")
                        || name.equals("getDeclaredFields")
                        || name.equals("getField")
                        || name.equals("getFields"));
            }
        };
    }

    @SuppressWarnings("unused")
    private static final class ReflectiveCoordinatorAccess {

        static Object readByDeclaredField(BootstrapCoordinator coordinator) throws ReflectiveOperationException {
            Field field = BootstrapCoordinator.class.getDeclaredField("diagnosticHandle");
            field.setAccessible(true);
            return field.get(coordinator);
        }

        static VarHandle readByPrivateLookup() throws ReflectiveOperationException {
            return MethodHandles.privateLookupIn(BootstrapCoordinator.class, MethodHandles.lookup())
                    .findVarHandle(BootstrapCoordinator.class, "diagnosticHandle", DiagnosticReadinessHandle.class);
        }

        private static final class NestedHelper {

            static Field[] fieldsOf(Object target) {
                return target.getClass().getDeclaredFields();
            }
        }
    }
}
