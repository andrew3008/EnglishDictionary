package space.br1440.platform.tracing.otel.extension.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Guardrails для границы baggage-propagation (FULL_PROPAGATION_BOUNDARY).
 *
 * <p>G-PROP-1: запрет string-based type detection через {@code Class.getName().contains(...)}.
 * Единственный корректный способ проверки типа propagator — {@code instanceof}.
 *
 * <p>G-PROP-2: только {@code BaggagePropagatorTypeDetector} вправе зависеть от
 * {@code W3CBaggagePropagator}. Все остальные production-классы должны делегировать
 * детекцию в детектор.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.otel.extension",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class PropagationBoundaryArchTest {

    @ArchTest
    static final ArchRule no_class_getName_in_propagation =
            noClasses()
                    .that().resideInAPackage("space.br1440.platform.tracing.otel.extension.propagation..")
                    .should().callMethodWhere(isClassGetName())
                    .because("Class.getName().contains(...) в propagation-пакете — хрупкое строковое определение типа; "
                            + "используйте instanceof (BaggagePropagatorTypeDetector)");

    @ArchTest
    static final ArchRule only_detector_depends_on_w3c_baggage_propagator =
            noClasses()
                    .that().resideInAPackage("space.br1440.platform.tracing.otel.extension..")
                    .and().doNotHaveSimpleName("BaggagePropagatorTypeDetector")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator")
                    .because("знание о конкретном типе W3CBaggagePropagator изолировано в BaggagePropagatorTypeDetector; "
                            + "все остальные классы должны использовать только TextMapPropagator");

    private static DescribedPredicate<JavaMethodCall> isClassGetName() {
        return new DescribedPredicate<>("вызов java.lang.Class.getName()") {
            @Override
            public boolean test(JavaMethodCall call) {
                return "getName".equals(call.getTarget().getName())
                        && "java.lang.Class".equals(call.getTargetOwner().getName());
            }
        };
    }
}
