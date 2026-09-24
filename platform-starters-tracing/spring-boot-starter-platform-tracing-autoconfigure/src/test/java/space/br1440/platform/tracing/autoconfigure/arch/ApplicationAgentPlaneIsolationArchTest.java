package space.br1440.platform.tracing.autoconfigure.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URISyntaxException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.arch.archfixture.invalid.DependsOnScrubbingRuntime;
import space.br1440.platform.tracing.test.arch.ModuleTaxonomyArchRules;
import space.br1440.platform.tracing.test.arch.OtelDirectIntegrationRules;

@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.autoconfigure",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ApplicationAgentPlaneIsolationArchTest {

    private static final String AUTOCONFIGURE_PACKAGE =
            "space.br1440.platform.tracing.autoconfigure..";

    private static final String JAVAAGENT_BOOTSTRAP_PACKAGE =
            "space.br1440.platform.tracing.otel.javaagent.bootstrap..";

    private static final String JAVAAGENT_SCRUBBING_PACKAGE =
            "space.br1440.platform.tracing.otel.javaagent.scrubbing..";

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule autoconfigureDoesNotDependOnExtensionImplementation =
            ModuleTaxonomyArchRules.AUTOCONFIGURE_MAIN_NO_OTEL_EXTENSION_IMPL;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule applicationDoesNotDependOnCoreVersionedRuntime =
            ModuleTaxonomyArchRules.APP_MODULES_NOT_DEPEND_ON_CORE_RUNTIME_VERSIONED;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule infrastructureIdentityTypesAreNotPublic =
            ModuleTaxonomyArchRules.IDENTITY_INTERNAL_TYPES_NOT_PUBLIC;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule remoteServiceNotConsumedByApplicationPlane =
            ModuleTaxonomyArchRules.REMOTE_SERVICE_NOT_CONSUMED_BY_APPLICATION_PLANE;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule noLocalCopiesOfOtelSdkClasses =
            OtelDirectIntegrationRules.NO_LOCAL_COPIES_OF_OTEL_SDK_CLASSES;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule noFakeOtelPackages =
            OtelDirectIntegrationRules.NO_FAKE_OTEL_PACKAGES;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule noLocalOtelContractNames =
            OtelDirectIntegrationRules.NO_LOCAL_OTEL_CONTRACT_NAMES;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule noRawRecordExceptionOutsideRecorder =
            OtelDirectIntegrationRules.NO_RAW_RECORD_EXCEPTION_OUTSIDE_RECORDER;

    @Test
    void applicationPlaneDoesNotDependOnJavaagentBootstrap() {
        // given
        JavaClasses applicationClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(
                        "space.br1440.platform.tracing.api",
                        "space.br1440.platform.tracing.otel",
                        "space.br1440.platform.tracing.autoconfigure");
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        "space.br1440.platform.tracing.api..",
                        "space.br1440.platform.tracing.otel..",
                        AUTOCONFIGURE_PACKAGE)
                .and().resideOutsideOfPackage("space.br1440.platform.tracing.otel.javaagent..")
                .should().dependOnClassesThat().resideInAPackage(JAVAAGENT_BOOTSTRAP_PACKAGE)
                .because("application modules must communicate with the agent through supported contracts");

        // when
        // then
        rule.check(applicationClasses);
    }

    @Test
    void autoconfigureDoesNotDependOnExtensionScrubbingRuntime() throws URISyntaxException {
        // given
        JavaClasses classes = importAutoconfigureOutputs();
        ArchRule rule = scrubbingBoundaryRule(true);

        // when
        // then
        rule.check(classes);
    }

    @Test
    void scrubbingBoundaryDetectsInvalidDependency() {
        // given
        JavaClasses invalidFixture = new ClassFileImporter().importClasses(DependsOnScrubbingRuntime.class);
        ArchRule rule = scrubbingBoundaryRule(false);

        // when
        // then
        assertThatThrownBy(() -> rule.check(invalidFixture))
                .isInstanceOf(AssertionError.class);
    }

    private static JavaClasses importAutoconfigureOutputs() throws URISyntaxException {
        Path mainOutput = Path.of(TracingRuntimeAutoConfiguration.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        Path testOutput = Path.of(ApplicationAgentPlaneIsolationArchTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        return new ClassFileImporter().importPaths(mainOutput, testOutput);
    }

    private static ArchRule scrubbingBoundaryRule(boolean excludeFixture) {
        var classes = noClasses().that().resideInAPackage(AUTOCONFIGURE_PACKAGE);
        if (excludeFixture) {
            classes = classes.and().resideOutsideOfPackage("..archfixture.invalid..");
        }
        return classes.should().dependOnClassesThat().resideInAPackage(JAVAAGENT_SCRUBBING_PACKAGE)
                .because("scrubbing runtime implementation belongs exclusively to the Java agent extension");
    }
}
