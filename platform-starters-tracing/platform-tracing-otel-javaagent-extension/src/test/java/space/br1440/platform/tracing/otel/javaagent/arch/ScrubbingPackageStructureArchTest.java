package space.br1440.platform.tracing.otel.javaagent.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.otel.javaagent.scrubbing",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ScrubbingPackageStructureArchTest {

    private static final String SCRUBBING = "space.br1440.platform.tracing.otel.javaagent.scrubbing";

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule subpackagesAreFreeOfCycles =
            slices().matching(SCRUBBING + ".(*)..")
                    .should().beFreeOfCycles()
                    .because("подпакеты scrubbing не должны зависеть друг от друга по кругу");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule rootPackageIsEmpty =
            noClasses()
                    .should().resideInAPackage(SCRUBBING)
                    .because("корень scrubbing расселён по подпакетам: runtime, assembly, engine, loader, rules, circuitbreaker");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule runtimeDoesNotDependOnAssembly =
            noClasses()
                    .that().resideInAPackage(SCRUBBING + ".runtime..")
                    .should().dependOnClassesThat().resideInAPackage(SCRUBBING + ".assembly..")
                    .because("любое обратное ребро ловит и beFreeOfCycles; отдельное правило нужно ради сообщения об ошибке");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule engineIsASharedKernel =
            noClasses()
                    .that().resideInAPackage(SCRUBBING + ".engine..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            SCRUBBING + ".runtime..",
                            SCRUBBING + ".assembly..",
                            SCRUBBING + ".loader..")
                    .because("engine — общее ядро правил и не должен знать о фазах сборки или сессии");
}
