package space.br1440.platform.errorhandling.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.base.AbstractRootCauseExceptionStrategy;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

class RootCauseStrategyArchitectureTest {

    private static final JavaClasses CORE_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("space.br1440.platform.errorhandling.core.strategy.cause");

    @Test
    void allRootCauseStrategiesInCore_extendAbstractBase() {
        // when / then
        classes().that()
                .implement(RootCauseExceptionHandlingStrategy.class)
                .should().beAssignableTo(AbstractRootCauseExceptionStrategy.class)
                .because("all root-cause strategies in error-handling-core must extend "
                        + "AbstractRootCauseExceptionStrategy for consistent order validation, "
                        + "default logPolicy, and helper method availability")
                .check(CORE_CLASSES);
    }
}
