package space.br1440.platform.tracing.gradle.javaagent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.gradle.javaagent",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class JavaAgentPackageArchTest {

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule extensionDoesNotDependOnDistribution = noClasses()
            .that().resideInAPackage("..gradle.javaagent.extension..")
            .should().dependOnClassesThat().resideInAPackage("..gradle.javaagent.distribution..");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule distributionDoesNotDependOnExtension = noClasses()
            .that().resideInAPackage("..gradle.javaagent.distribution..")
            .should().dependOnClassesThat().resideInAPackage("..gradle.javaagent.extension..");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule rootDoesNotDependOnSubpackages = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.gradle.javaagent")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..gradle.javaagent.extension..", "..gradle.javaagent.distribution..");
}
