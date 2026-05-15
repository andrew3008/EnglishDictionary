package space.br1440.platform.errorhandling.mvc.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import space.br1440.platform.errorhandling.exception.AbstractErrorResponseException;
import space.br1440.platform.errorhandling.exception.business.BusinessException;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

@DisplayName("Architecture rules (BusinessException contract)")
class BusinessExceptionContractArchitectureTest {

    private static final String AERE_FQN = AbstractErrorResponseException.class.getName();
    private static final String BUSINESS_FQN = BusinessException.class.getName();

    private static JavaClasses mvcClasses;

    @BeforeAll
    static void importClasses() {
        mvcClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("space.br1440.platform.errorhandling");
    }

    @Test
    @DisplayName("Контроллеры не декларируют throws non-business AbstractErrorResponseException")
    void controllers_must_not_throw_non_business_AERE() {
        ArchRule rule = methods()
                .that(areDeclaredInControllerOrAdvice())
                .should(notDeclareNonBusinessPlatformExceptions())
                .because("""
                        На web-уровне допустимо явно бросать только BusinessException; \
                        non-business наследники AbstractErrorResponseException должны \
                        использоваться только как cause, иначе они уходят в \
                        DefaultUnknownExceptionHandler (HTTP 500) и обходят \
                        BusinessExceptionStrategy/ErrorEntryCauseStrategy.""");

        rule.allowEmptyShould(true).check(mvcClasses);
    }

    private static DescribedPredicate<JavaMethod> areDeclaredInControllerOrAdvice() {
        return new DescribedPredicate<>("declared in @Controller / @RestController / @ControllerAdvice / @RestControllerAdvice") {
            @Override
            public boolean test(JavaMethod method) {
                JavaClass owner = method.getOwner();
                return owner.isAnnotatedWith(Controller.class)
                        || owner.isAnnotatedWith(RestController.class)
                        || owner.isAnnotatedWith(ControllerAdvice.class)
                        || owner.isAnnotatedWith(RestControllerAdvice.class);
            }
        };
    }

    private static ArchCondition<JavaMethod> notDeclareNonBusinessPlatformExceptions() {
        return new ArchCondition<>("not declare throws of non-business AbstractErrorResponseException") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                for (JavaClass declaredThrowable : method.getThrowsClause().getTypes()) {
                    if (isPlatformException(declaredThrowable) && !isBusinessException(declaredThrowable)) {
                        String message = String.format(
                                "Method %s declares throws %s — это не-business наследник AbstractErrorResponseException; "
                                        + "на top-уровне допустимо бросать только BusinessException",
                                method.getFullName(), declaredThrowable.getName()
                        );

                        events.add(SimpleConditionEvent.violated(method, message));
                    }
                }
            }
        };
    }

    private static boolean isPlatformException(JavaClass type) {
        return type.isAssignableTo(AERE_FQN);
    }

    private static boolean isBusinessException(JavaClass type) {
        return type.isAssignableTo(BUSINESS_FQN);
    }
}
