package space.br1440.platform.tracing.autoconfigure.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * S6: application-plane production modules must not depend on the Java-agent bootstrap
 * composition root ({@code ..otel.javaagent.bootstrap..}). Bootstrap is classloader-sensitive
 * Agent territory; Spring / API / otel-core consume readiness only through the JMX wire protocol
 * and shared API contracts.
 */
class AppPlaneNoJavaAgentBootstrapDependencyArchTest {

    private static final String BOOTSTRAP =
            "space.br1440.platform.tracing.otel.javaagent.bootstrap";

    private static final JavaClasses APP_PLANE = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(
                    "space.br1440.platform.tracing.api",
                    "space.br1440.platform.tracing.otel",
                    "space.br1440.platform.tracing.autoconfigure");

    @Test
    void app_plane_production_types_do_not_depend_on_javaagent_bootstrap() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        "space.br1440.platform.tracing.api..",
                        "space.br1440.platform.tracing.otel..",
                        "space.br1440.platform.tracing.autoconfigure..")
                .and().resideOutsideOfPackage("space.br1440.platform.tracing.otel.javaagent..")
                .should().dependOnClassesThat().resideInAPackage(BOOTSTRAP + "..")
                .because("application-plane modules must not depend on javaagent.bootstrap; "
                        + "Gate C1 consumes readiness via JMX wire only (S6)");

        rule.check(APP_PLANE);
    }
}
