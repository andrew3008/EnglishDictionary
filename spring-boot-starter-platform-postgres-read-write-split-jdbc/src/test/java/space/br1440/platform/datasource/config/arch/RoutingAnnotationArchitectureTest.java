package space.br1440.platform.datasource.config.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import space.br1440.platform.datasource.postgres.api.routing.UseReadReplica;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

@DisplayName("Routing annotation architecture rules")
class RoutingAnnotationArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("space.br1440.platform.datasource");
    }

    @Test
    @DisplayName("@UseReadReplica и @Transactional не могут сосуществовать на одном методе")
    void useReadReplica_mustNotCoexistWithTransactional() {
        methods()
                .that().areAnnotatedWith(UseReadReplica.class)
                .should().notBeAnnotatedWith(Transactional.class)
                .because("Use @Transactional(readOnly = true) instead of @UseReadReplica - not combined "
                        + "(see RoutingAnnotationConflictValidator)")
                .allowEmptyShould(true).check(classes);
    }
}