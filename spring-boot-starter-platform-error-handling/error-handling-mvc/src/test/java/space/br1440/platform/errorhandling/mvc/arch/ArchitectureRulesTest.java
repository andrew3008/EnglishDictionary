package space.br1440.platform.errorhandling.mvc.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@DisplayName("Architecture rules (mvc)")
class ArchitectureRulesTest {

    private static JavaClasses mvcClasses;

    @BeforeAll
    static void importClasses() {
        mvcClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("space.br1440.platform.errorhandling");
    }

    @Test
    @DisplayName("mvc.advice не зависит от mvc.security напрямую")
    void advice_doesNotDependOnSecurity() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("space.br1440.platform.errorhandling.core.advice..")
                .should().dependOnClassesThat().resideInAPackage("space.br1440.platform.errorhandling.core.security..");

        rule.check(mvcClasses);
    }

    @Test
    @DisplayName("mvc.security не зависит от mvc.advice напрямую")
    void security_doesNotDependOnAdvice() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("space.br1440.platform.errorhandling.core.security..")
                .should().dependOnClassesThat().resideInAPackage("space.br1440.platform.errorhandling.core.advice..");

        rule.check(mvcClasses);
    }

    @Test
    @DisplayName("ни один класс platform error-handling не зависит от удалённого mvc.internal.LogSanitizer")
    void noClasses_dependOnRemovedMvcLogSanitizerFacade() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("space.br1440.platform.errorhandling.mvc.internal.LogSanitizer");

        rule.check(mvcClasses);
    }

    @Test
    @DisplayName("mvc.internal классы — package-private, кроме явных публичных компонентов")
    void internal_classesAreEncapsulated() {
        ArchRule rule = classes()
                .that().resideInAPackage("space.br1440.platform.errorhandling.mvc.internal..")
                .should().resideInAPackage("space.br1440.platform.errorhandling.mvc.internal..");

        rule.check(mvcClasses);
    }
}
