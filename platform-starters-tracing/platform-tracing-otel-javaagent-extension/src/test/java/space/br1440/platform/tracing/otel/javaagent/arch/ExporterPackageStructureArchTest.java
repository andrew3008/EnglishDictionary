package space.br1440.platform.tracing.otel.javaagent.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.otel.javaagent.exporter",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ExporterPackageStructureArchTest {

    private static final String EXPORTER = "space.br1440.platform.tracing.otel.javaagent.exporter";

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule rootHoldsOnlyTheFacade =
            classes().that().resideInAPackage(EXPORTER)
                    .should().haveSimpleName("SafeSpanExporter")
                    .orShould().haveSimpleName("ExportOutageTracker")
                    .because("the exporter root keeps only the public span exporter and its outage tracker");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule subpackagesDoNotDependOnTheRoot =
            noClasses()
                    .that().resideInAnyPackage(
                            EXPORTER + ".guard..",
                            EXPORTER + ".boundary..",
                            EXPORTER + ".response..")
                    .should().dependOnClassesThat().resideInAPackage(EXPORTER)
                    .because("components do not depend on the export facade");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule subpackagesAreFreeOfCycles =
            slices().matching(EXPORTER + ".(*)..")
                    .should().beFreeOfCycles()
                    .because("exporter subpackages must not form a cycle");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule boundaryDoesNotDependOnGuardOrResponse =
            noClasses()
                    .that().resideInAPackage(EXPORTER + ".boundary..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            EXPORTER + ".guard..",
                            EXPORTER + ".response..")
                    .because("the scrubbing boundary does not know the request size or the transport response");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule guardDoesNotDependOnBoundary =
            noClasses()
                    .that().resideInAPackage(EXPORTER + ".guard..")
                    .should().dependOnClassesThat().resideInAPackage(EXPORTER + ".boundary..")
                    .because("the request-size guard does not know the scrubbing boundary");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule boundaryIsUsedOnlyByTheFacade =
            noClasses()
                    .that().resideOutsideOfPackage(EXPORTER + ".boundary..")
                    .and().doNotHaveSimpleName("SafeSpanExporter")
                    .should().dependOnClassesThat().resideInAPackage(EXPORTER + ".boundary..")
                    .because("public boundary types stay an export-facade detail");
}
