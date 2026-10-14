package space.br1440.platform.tracing.autoconfigure.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.archfixture.invalid.DependsOnSamplingJmxView;

@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.autoconfigure",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class AutoconfigureInternalBoundaryArchTest {

    private static final DescribedPredicate<JavaClass> JMX_VIEW =
            DescribedPredicate.describe(
                    "type with simple name ending with JmxView",
                    javaClass -> javaClass.getSimpleName().endsWith("JmxView"));

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule neutralAutoconfigureDoesNotDependOnWebStacks =
            noClasses()
                    .that().resideOutsideOfPackage("space.br1440.platform.tracing.autoconfigure.servlet..")
                    .and().resideOutsideOfPackage("space.br1440.platform.tracing.autoconfigure.reactive..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework.web..",
                            "jakarta.servlet..")
                    .because("web stack integration belongs to dedicated WebMVC and WebFlux modules");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule controlProtocolDoesNotDependOnSpring =
            noClasses()
                    .that().resideInAnyPackage(
                            "..autoconfigure.control.policy..",
                            "..autoconfigure.control.jmx.protocol..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                    .because("control policy and wire protocol must remain framework neutral");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule jmxViewsAreNotHeldOutsideRoot =
            noFields()
                    .that().areDeclaredInClassesThat().resideOutsideOfPackage("..diagnostics.jmx")
                    .should().haveRawType(JMX_VIEW)
                    .because("JMX views must not become application-level state");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule jmxViewsAreNotAcceptedOutsideRoot =
            noMethods()
                    .that().areDeclaredInClassesThat().resideOutsideOfPackage("..diagnostics.jmx")
                    .should().haveRawParameterTypes(DescribedPredicate.describe(
                            "any parameter is a JmxView",
                            parameters -> parameters.stream()
                                    .anyMatch(type -> type.getSimpleName().endsWith("JmxView"))))
                    .because("JMX views must not leak into application method contracts");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule configServerDoesNotDependOnJmx =
            noClasses()
                    .that().resideInAPackage("space.br1440.platform.tracing.autoconfigure.control.configserver")
                    .should().dependOnClassesThat()
                    .resideInAPackage("space.br1440.platform.tracing.autoconfigure.control.jmx..")
                    .because("control channels are peers; the writer contract lives in control.settings");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule configServerGuardLoadsWithoutSpringCloud =
            noClasses()
                    .that().haveSimpleName("ConfigServerClasspathGuardAutoConfiguration")
                    .or().haveSimpleName("ConfigServerInitialDelivery")
                    .or().haveSimpleName("ConfigServerTracingSettingsAutoConfiguration")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework.cloud..")
                    .because("the guard and delivery configuration must load when spring-cloud-context is absent");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule onlyJmxClientReturnsJmxViews =
            noMethods()
                    .that().areDeclaredInClassesThat().resideOutsideOfPackage("..diagnostics.jmx")
                    .and().areNotDeclaredIn(PlatformTracingJmxClient.class)
                    .should().haveRawReturnType(JMX_VIEW)
                    .because("PlatformTracingJmxClient is the only JMX view entry point");

    @Test
    void jmxViewBoundaryDetectsInvalidStateHolder() {
        // given
        JavaClasses invalidFixture = new ClassFileImporter().importClasses(DependsOnSamplingJmxView.class);

        // when
        // then
        assertThatThrownBy(() -> jmxViewsAreNotHeldOutsideRoot.check(invalidFixture))
                .hasMessageContaining("JmxView");
    }

}
