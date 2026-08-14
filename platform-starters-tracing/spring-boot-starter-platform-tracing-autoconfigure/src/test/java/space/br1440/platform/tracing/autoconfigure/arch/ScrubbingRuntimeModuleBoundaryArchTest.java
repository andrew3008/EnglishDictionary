package space.br1440.platform.tracing.autoconfigure.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URISyntaxException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;

import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.arch.archfixture.invalid.DependsOnScrubbingRuntime;

class ScrubbingRuntimeModuleBoundaryArchTest {

    private static final String SCRUBBING_RUNTIME =
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime";
    private static final String SCRUBBING_BOUNDARY_METRICS =
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingBoundaryMetrics";
    private static final String MERGE_OUTCOME =
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeOutcome";
    private static final String RULE_ORIGIN =
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleOrigin";
    private static final String RUNTIME_RULE =
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuntimeRule";

    @Test
    void autoconfigure_does_not_depend_on_extension_runtime_implementation() throws URISyntaxException {
        // given
        JavaClasses classes = importAutoconfigureOutputs();

        // when
        ArchRule rule = moduleBoundaryRule();

        // then
        rule.check(classes);
    }

    @Test
    void module_boundary_rule_detects_an_isolated_invalid_fixture() {
        // given
        JavaClasses invalidFixture = new ClassFileImporter().importClasses(DependsOnScrubbingRuntime.class);

        // when
        ArchRule rule = rawModuleBoundaryRule();

        // then
        assertThatThrownBy(() -> rule.check(invalidFixture)).isInstanceOf(AssertionError.class);
    }

    private static JavaClasses importAutoconfigureOutputs() throws URISyntaxException {
        Path mainOutput = Path.of(TracingRuntimeAutoConfiguration.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        Path testOutput = Path.of(ScrubbingRuntimeModuleBoundaryArchTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        return new ClassFileImporter().importPaths(mainOutput, testOutput);
    }

    private static ArchRule moduleBoundaryRule() {
        return noClasses()
                .that().resideInAPackage("space.br1440.platform.tracing.autoconfigure..")
                .and().resideOutsideOfPackage("..archfixture.invalid..")
                .should().dependOnClassesThat().haveFullyQualifiedName(SCRUBBING_RUNTIME)
                .orShould().dependOnClassesThat().haveFullyQualifiedName(SCRUBBING_BOUNDARY_METRICS)
                .orShould().dependOnClassesThat().haveFullyQualifiedName(MERGE_OUTCOME)
                .orShould().dependOnClassesThat().haveFullyQualifiedName(RULE_ORIGIN)
                .orShould().dependOnClassesThat().haveFullyQualifiedName(RUNTIME_RULE)
                .because("Scrubbing runtime implementation belongs exclusively to the Java agent extension module");
    }

    private static ArchRule rawModuleBoundaryRule() {
        return noClasses()
                .that().resideInAPackage("space.br1440.platform.tracing.autoconfigure..")
                .should().dependOnClassesThat().haveFullyQualifiedName(SCRUBBING_RUNTIME)
                .orShould().dependOnClassesThat().haveFullyQualifiedName(SCRUBBING_BOUNDARY_METRICS)
                .orShould().dependOnClassesThat().haveFullyQualifiedName(MERGE_OUTCOME)
                .orShould().dependOnClassesThat().haveFullyQualifiedName(RULE_ORIGIN)
                .orShould().dependOnClassesThat().haveFullyQualifiedName(RUNTIME_RULE)
                .because("Scrubbing runtime implementation belongs exclusively to the Java agent extension module");
    }
}
