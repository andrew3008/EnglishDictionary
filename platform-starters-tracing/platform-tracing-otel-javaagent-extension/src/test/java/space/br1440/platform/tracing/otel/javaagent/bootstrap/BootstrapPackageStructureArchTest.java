package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.BootstrapFailure;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapResources;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSessionFactory;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.verification.TracePipelineSpiVerifier;

import io.opentelemetry.javaagent.extension.AgentListener;
import io.opentelemetry.javaagent.tooling.LoggingCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.otel.javaagent",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class BootstrapPackageStructureArchTest {

    private static final String ROOT_PACKAGE = "space.br1440.platform.tracing.otel.javaagent.bootstrap";
    private static final String ROOT_PACKAGE_PATTERN = "..otel.javaagent.bootstrap";
    private static final String FAILURE_PACKAGE_PATTERN = "..otel.javaagent.bootstrap.failure..";
    private static final String RESOURCES_PACKAGE_PATTERN = "..otel.javaagent.bootstrap.resources..";
    private static final String SESSION_PACKAGE_PATTERN = "..otel.javaagent.bootstrap.session..";
    private static final String VERIFICATION_PACKAGE_PATTERN = "..otel.javaagent.bootstrap.verification..";

    @ArchTest
    @SuppressWarnings("unused")
    static void leaf_subpackages_do_not_depend_on_bootstrap_root(JavaClasses classes) {
        // given
        ArchRule rule = leafIndependenceRule();

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertProductionClassesExist(
                classes,
                BootstrapResources.class,
                AutoconfigureSessionFactory.class,
                TracePipelineSpiVerifier.class,
                BootstrapCoordinator.class,
                BootstrapFailure.class
        );
        assertThat(result.getFailureReport().getDetails()).isEmpty();
    }

    @ArchTest
    @SuppressWarnings("unused")
    static void bootstrap_subpackages_are_free_of_cycles(JavaClasses classes) {
        // given
        ArchRule rule = subpackageCycleRule();

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertProductionClassesExist(
                classes,
                BootstrapResources.class,
                AutoconfigureSessionFactory.class,
                TracePipelineSpiVerifier.class
        );
        assertThat(result.getFailureReport().getDetails()).isEmpty();
    }

    @ArchTest
    @SuppressWarnings("unused")
    static void only_service_loader_entries_are_public_in_bootstrap_root(JavaClasses classes) {
        // given
        ArchRule rule = publicRootEntriesRule();

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertProductionClassesExist(
                classes,
                ActivationAgentListener.class,
                BootstrapAutoConfigurationCustomizerProvider.class,
                PlatformStartupLoggingCustomizer.class
        );
        assertThat(publicRootClasses(classes))
                .extracting(JavaClass::getSimpleName)
                .containsExactlyInAnyOrder(
                        ActivationAgentListener.class.getSimpleName(),
                        BootstrapAutoConfigurationCustomizerProvider.class.getSimpleName(),
                        PlatformStartupLoggingCustomizer.class.getSimpleName()
                );
        assertThat(result.getFailureReport().getDetails()).isEmpty();
    }

    @ArchTest
    @SuppressWarnings("unused")
    static void failure_leaf_is_accessed_only_from_bootstrap(JavaClasses classes) {
        // given
        ArchRule rule = failureLeafAccessRule();

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertProductionClassesExist(classes, BootstrapFailure.class);
        assertThat(result.getFailureReport().getDetails()).isEmpty();
    }

    private static ArchRule leafIndependenceRule() {
        return noClasses()
                .that().resideInAnyPackage(
                        FAILURE_PACKAGE_PATTERN,
                        RESOURCES_PACKAGE_PATTERN,
                        SESSION_PACKAGE_PATTERN,
                        VERIFICATION_PACKAGE_PATTERN
                )
                .should().dependOnClassesThat().resideInAPackage(ROOT_PACKAGE_PATTERN)
                .because("failure, resources, session и verification — листья ядра bootstrap; "
                        + "зависимость направлена только из корня в лист (ADR «Плоское ядро bootstrap»)");
    }

    private static ArchRule failureLeafAccessRule() {
        return noClasses()
                .that().resideOutsideOfPackages(ROOT_PACKAGE_PATTERN, FAILURE_PACKAGE_PATTERN)
                .should().dependOnClassesThat().resideInAnyPackage(FAILURE_PACKAGE_PATTERN)
                .because("словарь отказов bootstrap доступен только корню bootstrap и самому листу");
    }

    private static ArchRule subpackageCycleRule() {
        return slices()
                .matching(ROOT_PACKAGE + ".(*)..")
                .should().beFreeOfCycles()
                .because("листовые подпакеты bootstrap не должны зависеть друг от друга по кругу");
    }

    private static ArchRule publicRootEntriesRule() {
        return classes()
                .that().resideInAPackage(ROOT_PACKAGE_PATTERN)
                .and().arePublic()
                .should().implement(AgentListener.class)
                .orShould().implement(AutoConfigurationCustomizerProvider.class)
                .orShould().implement(LoggingCustomizer.class)
                .because("публичным в корне bootstrap может быть только ServiceLoader-вход; "
                        + "остальное ядро — package-private под защитой компилятора");
    }

    private static List<JavaClass> publicRootClasses(JavaClasses classes) {
        return classes.stream()
                .filter(javaClass -> ROOT_PACKAGE.equals(javaClass.getPackageName()))
                .filter(javaClass -> javaClass.getModifiers().contains(JavaModifier.PUBLIC))
                .filter(javaClass -> !javaClass.isAnonymousClass())
                .toList();
    }

    private static void assertProductionClassesExist(JavaClasses classes, Class<?>... expected) {
        for (Class<?> type : expected) {
            assertThat(classes.contain(type))
                    .as("production class %s must be part of the analyzed classes", type.getName())
                    .isTrue();
        }
    }
}
