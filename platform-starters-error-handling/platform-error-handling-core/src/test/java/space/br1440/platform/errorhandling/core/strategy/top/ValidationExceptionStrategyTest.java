package space.br1440.platform.errorhandling.core.strategy.top;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.BadRequestDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ValidationExceptionStrategy")
class ValidationExceptionStrategyTest {

    private final CauseChainResolver causeChainResolver = new CauseChainResolver(Collections.emptyList());
    private final ValidationExceptionStrategy strategy = new ValidationExceptionStrategy(causeChainResolver);
    private final StackHintExtractor stackHintExtractor = new StackHintExtractor(false, List.of());
    private final ErrorHandlingContext context = ErrorHandlingContext.of(new ErrorHandlingProperties(), "/api/test", stackHintExtractor);

    @Nested
    @DisplayName("supports()")
    class Supports {

        @Test
        void supports_MethodArgumentNotValidException() {
            // given
            var bindingResult = new BeanPropertyBindingResult(new Object(), "request");
            var exception = new MethodArgumentNotValidException(null, bindingResult);

            // when / then
            assertThat(strategy.supports(exception)).isTrue();
        }

        @Test
        void does_not_support_RuntimeException() {
            // when / then
            assertThat(strategy.supports(new RuntimeException("unrelated"))).isFalse();
        }
    }

    @Nested
    @DisplayName("MethodArgumentNotValidException → BadRequestDetail + ErrorInfoDetail")
    class MethodArgumentNotValid {

        @Test
        @DisplayName("produces BadRequestDetail with FieldViolation for each validation error")
        void produces_BadRequestDetail_with_FieldViolations() {
            // given
            var bindingResult = new BeanPropertyBindingResult(new Object(), "request");
            bindingResult.addError(new FieldError("request", "email", "must be a valid email"));
            bindingResult.addError(new FieldError("request", "name", "must not be blank"));
            var exception = new MethodArgumentNotValidException(null, bindingResult);

            // when
            ErrorEntry entry = strategy.handle(exception, context);

            // then
            assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);

            BadRequestDetail badRequest = findDetail(entry.getDetails(), BadRequestDetail.class);
            assertThat(badRequest).isNotNull();
            assertThat(badRequest.getFieldViolations()).hasSize(2);

            BadRequestDetail.FieldViolation emailViolation = badRequest.getFieldViolations().stream()
                    .filter(fv -> "email".equals(fv.getField()))
                    .findFirst().orElseThrow();
            assertThat(emailViolation.getReason()).isEqualTo("INVALID");
            assertThat(emailViolation.getDescription()).isEqualTo("must be a valid email");
        }

        @Test
        @DisplayName("produces ErrorInfoDetail with reason=VALIDATION_FAILED, domain=validation")
        void produces_ErrorInfoDetail_with_validation_domain() {
            // given
            var bindingResult = new BeanPropertyBindingResult(new Object(), "request");
            bindingResult.addError(new FieldError("request", "age", "must be positive"));
            var exception = new MethodArgumentNotValidException(null, bindingResult);

            // when
            ErrorEntry entry = strategy.handle(exception, context);

            // then
            ErrorInfoDetail errorInfo = findDetail(entry.getDetails(), ErrorInfoDetail.class);
            assertThat(errorInfo).isNotNull();
            assertThat(errorInfo.getReason()).isEqualTo("VALIDATION_FAILED");
            assertThat(errorInfo.getDomain()).isEqualTo("validation");
            assertThat(errorInfo.getClientMetadata()).containsEntry("violationCount", "1");
        }

        @Test
        @DisplayName("both BadRequestDetail and ErrorInfoDetail are present in details")
        void details_contain_both_BadRequestDetail_and_ErrorInfoDetail() {
            // given
            var bindingResult = new BeanPropertyBindingResult(new Object(), "dto");
            bindingResult.addError(new FieldError("dto", "field1", "error1"));
            var exception = new MethodArgumentNotValidException(null, bindingResult);

            // when
            ErrorEntry entry = strategy.handle(exception, context);

            // then
            assertThat(entry.getDetails().stream().filter(BadRequestDetail.class::isInstance).count())
                    .isEqualTo(1);
            assertThat(entry.getDetails().stream().filter(ErrorInfoDetail.class::isInstance).count())
                    .isGreaterThanOrEqualTo(1);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends ErrorDetail> T findDetail(List<ErrorDetail> details, Class<T> type) {
        return (T) details.stream()
                .filter(type::isInstance)
                .findFirst()
                .orElse(null);
    }
}
