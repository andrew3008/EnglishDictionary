package space.br1440.platform.tracing.autoconfigure.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.archfixture.invalid.DependsOnSamplingJmxView;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Граница {@code *JmxView}: view не объявляется как поле / параметр / return type
 * вне корневого пакета {@code …diagnostics.jmx}.
 *
 * <p>Цепочки {@code client.sampling().observation()} закономерно создают bytecode-зависимость
 * на view (return type accessor'а фасада) — полный {@code dependOnClassesThat(*JmxView)}
 * ломает metrics/status. Конструкторы view уже package-private (Java не даёт
 * {@code new *JmxView} из другого пакета). ArchUnit защищает оставшийся канал accidental SPI:
 * удержание или декларацию view как «своего» типа снаружи корня.
 *
 * <p>{@code ..diagnostics.jmx} матчит только корневой пакет (без подпакетов).
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.autoconfigure",
        importOptions = ImportOption.DoNotIncludeTests.class)
class JmxPackageBoundaryArchTest {

    private static final DescribedPredicate<JavaClass> JMX_VIEW_TYPE =
            DescribedPredicate.describe(
                    "type with simple name ending with JmxView",
                    javaClass -> javaClass.getSimpleName().endsWith("JmxView"));

    @ArchTest
    static final ArchRule jmxViewsAreNotHeldAsFieldsOutsideRoot =
            noFields()
                    .that().areDeclaredInClassesThat().resideOutsideOfPackage("..diagnostics.jmx")
                    .should().haveRawType(JMX_VIEW_TYPE)
                    .because("*JmxView не удерживается снаружи корневого …diagnostics.jmx; "
                            + "вход — PlatformTracingJmxClient, наружу — *Observation/*Values");

    @ArchTest
    static final ArchRule jmxViewsAreNotMethodParametersOutsideRoot =
            noMethods()
                    .that().areDeclaredInClassesThat().resideOutsideOfPackage("..diagnostics.jmx")
                    .should().haveRawParameterTypes(DescribedPredicate.describe(
                            "any parameter is a *JmxView",
                            params -> params.stream().anyMatch(type -> type.getSimpleName().endsWith("JmxView"))))
                    .because("*JmxView не передаётся параметром снаружи корневого …diagnostics.jmx");

    @ArchTest
    static final ArchRule jmxViewsAreNotReturnedOutsideRoot =
            noMethods()
                    .that().areDeclaredInClassesThat().resideOutsideOfPackage("..diagnostics.jmx")
                    .and().areNotDeclaredIn(space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient.class)
                    .should().haveRawReturnType(JMX_VIEW_TYPE)
                    .because("единственный легитимный возврат *JmxView — accessors PlatformTracingJmxClient");

    @Test
    void fieldRuleRejectsHoldingJmxViewOutsideRootPackage() {
        JavaClasses classes = new ClassFileImporter().importClasses(DependsOnSamplingJmxView.class);

        assertThatThrownBy(() -> jmxViewsAreNotHeldAsFieldsOutsideRoot.check(classes))
                .hasMessageContaining("JmxView");
    }
}
