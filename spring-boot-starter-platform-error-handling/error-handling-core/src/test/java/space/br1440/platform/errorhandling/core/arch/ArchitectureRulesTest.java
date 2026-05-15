package space.br1440.platform.errorhandling.core.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@DisplayName("Architecture rules (core)")
class ArchitectureRulesTest {

    private static JavaClasses coreClasses;

    @BeforeAll
    static void importClasses() {
        coreClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("space.br1440.platform.errorhandling");
    }

    @Test
    @DisplayName("core LogSanitizer не зависит ни от чего, кроме JDK")
    void logSanitizer_isPortable() {
        ArchRule rule = classes()
                .that().resideInAPackage("space.br1440.platform.errorhandling.core.util.sanitize..")
                .and().haveSimpleName("LogSanitizer")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..");

        rule.check(coreClasses);
    }

    @Test
    @DisplayName("core LogStatementWriter зависит только от JDK, SLF4J и core-extensions")
    void logStatementWriter_isPortable() {
        ArchRule rule = classes()
                .that().resideInAPackage("space.br1440.platform.errorhandling.core.util.log..")
                .and().haveSimpleName("LogStatementWriter")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "java..",
                        "org.slf4j..",
                        "org.springframework.lang..",
                        "lombok..",
                        "space.br1440.platform.errorhandling.extension..",
                        "space.br1440.platform.errorhandling.core.util.log.."
                );

        rule.check(coreClasses);
    }

    @Test
    @DisplayName("core MetricSnapshotFactory зависит только от core-internals и model-extensions")
    void metricSnapshotFactory_isPortable() {
        ArchRule rule = classes()
                .that().resideInAPackage("space.br1440.platform.errorhandling.core.dispatcher..")
                .and().haveSimpleName("MetricSnapshotFactory")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "java..",
                        "org.springframework.lang..",
                        "org.springframework.http..",
                        "lombok..",
                        "space.br1440.platform.errorhandling.core.util..",
                        "space.br1440.platform.errorhandling.extension..",
                        "space.br1440.platform.errorhandling.."
                );

        rule.check(coreClasses);
    }

    @Test
    @DisplayName("error-handling не зависит от spring-boot-autoconfigure трассировки (только tracing-api)")
    void errorHandling_doesNotDependOnTracingAutoconfigure() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("space.br1440.platform.errorhandling..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("space.br1440.platform.tracing.autoconfigure..");

        rule.check(coreClasses);
    }

    @Test
    @DisplayName("ни один класс не зависит от удалённого фасада mvc.internal.LogSanitizer (только core LogSanitizer)")
    void noClasses_dependOnRemovedMvcLogSanitizerFacade() {
        // Каноника — space.br1440.platform.errorhandling.core.util.sanitize.LogSanitizer.
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("space.br1440.platform.errorhandling.mvc.internal.LogSanitizer");

        rule.check(coreClasses);
    }
}
