package space.br1440.platform.datasource.config.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.datasource.postgres.internal.pool.HikariPoolsConfiguration;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@DisplayName("Postgres routing architecture rules")
class ConfigurationArchitectureTest {

    private static final String PKG_API = "..postgres.api..";
    private static final String PKG_INTERNAL = "..postgres.internal..";
    private static final String PKG_POOL = "..postgres.internal.pool..";
    private static final String PKG_INTEGRATION = "..postgres.internal.integration..";

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("space.br1440.platform.datasource.postgres");
    }

    @Test
    @DisplayName("api не зависит от internal")
    void api_doesNotDependOnInternal() {
        noClasses()
                .that().resideInAPackage(PKG_API)
                .should().dependOnClassesThat().resideInAPackage(PKG_INTERNAL)
                .check(classes);
    }

    @Test
    @DisplayName("legacy Spec/Compiler/Policy слои отсутствуют")
    void legacySpecCompilerPackages_doNotExist() {
        noClasses()
                .should().resideInAnyPackage(
                        "..configuration.model..",
                        "..configuration.policy..",
                        "..configuration.url..",
                        "..configuration.compiler..")
                .check(classes);
    }

    @Test
    @DisplayName("integration не участвует в pool materialization")
    void integration_doesNotDependOnPoolMaterialization() {
        noClasses()
                .that().resideInAPackage(PKG_INTEGRATION)
                .should().dependOnClassesThat().belongToAnyOf(HikariPoolsConfiguration.class)
                .check(classes);
    }

    @Test
    @DisplayName("pool не зависит от integration")
    void pool_doesNotDependOnIntegrationExceptLogging() {
        noClasses()
                .that().resideInAPackage(PKG_POOL)
                .and().haveSimpleNameNotContaining("Factory")
                .should().dependOnClassesThat().resideInAnyPackage("..postgres.internal.integration.health..",
                        "..postgres.internal.integration.metrics..",
                        "..postgres.internal.integration.observation..",
                        "..postgres.internal.integration.jooq..")
                .check(classes);
    }
}
