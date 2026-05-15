package space.br1440.platform.errorhandling.testing.assertions;

import org.assertj.core.api.AbstractAssert;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.CASE_INSENSITIVE;

public class ErrorEntryAssert extends AbstractAssert<ErrorEntryAssert, ErrorEntry> {

    private static final List<Pattern> SENSITIVE_PATTERNS = List.of(
            Pattern.compile("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b"),
            Pattern.compile("password|passwd|secret|token|credential|api[_-]?key", CASE_INSENSITIVE),
            Pattern.compile("at\\s+[\\w.$]+\\([\\w]+\\.java:\\d+\\)")
    );

    public ErrorEntryAssert(ErrorEntry actual) {
        super(actual, ErrorEntryAssert.class);
    }

    public static ErrorEntryAssert assertThat(ErrorEntry actual) {
        return new ErrorEntryAssert(actual);
    }

    public ErrorEntryAssert hasHttpStatus(HttpStatus expected) {
        isNotNull();
        if (!Objects.equals(actual.getHttpStatus(), expected)) {
            failWithMessage("Expected httpStatus <%s> but was <%s>", expected, actual.httpStatusOrNull());
        }

        return myself;
    }

    public ErrorEntryAssert hasHttpStatusCode(int expected) {
        isNotNull();
        if (actual.getHttpStatus().value() != expected) {
            failWithMessage("Expected httpStatus code <%d> but was <%d>",
                    expected, actual.getHttpStatus().value());
        }

        return myself;
    }

    public ErrorEntryAssert hasErrorCode(String expected) {
        isNotNull();
        if (!Objects.equals(actual.getErrorCode(), expected)) {
            failWithMessage("Expected errorCode <%s> but was <%s>", expected, actual.getErrorCode());
        }

        return myself;
    }

    public ErrorEntryAssert hasErrorCodeMatching(String regex) {
        isNotNull();
        if (!Pattern.compile(regex).matcher(actual.getErrorCode()).matches()) {
            failWithMessage("Expected errorCode matching <%s> but was <%s>", regex, actual.getErrorCode());
        }

        return myself;
    }

    public ErrorEntryAssert hasReason(String expected) {
        isNotNull();
        if (!Objects.equals(actual.getReason(), expected)) {
            failWithMessage("Expected reason <%s> but was <%s>", expected, actual.getReason());
        }

        return myself;
    }

    public ErrorEntryAssert hasMessage(String expected) {
        isNotNull();
        if (!Objects.equals(actual.getMessage(), expected)) {
            failWithMessage("Expected message <%s> but was <%s>", expected, actual.getMessage());
        }

        return myself;
    }

    public ErrorEntryAssert hasMessageContaining(String substring) {
        isNotNull();
        if (actual.getMessage() == null || !actual.getMessage().contains(substring)) {
            failWithMessage("Expected message containing <%s> but was <%s>", substring, actual.getMessage());
        }

        return myself;
    }

    public ErrorEntryAssert isRetryable() {
        isNotNull();
        if (!actual.isRetryable()) {
            failWithMessage("Expected ErrorEntry to be retryable (contain RetryInfoDetail), but it was not");
        }

        return myself;
    }

    public ErrorEntryAssert isNotRetryable() {
        isNotNull();
        if (actual.isRetryable()) {
            failWithMessage("Expected ErrorEntry NOT to be retryable, but it contains RetryInfoDetail");
        }

        return myself;
    }

    public ErrorEntryAssert hasRetryDelay(Duration expected) {
        isNotNull();
        isRetryable();
        Duration actualDelay = actual.getRetryDelay();
        if (!Objects.equals(actualDelay, expected)) {
            failWithMessage("Expected retryDelay <%s> but was <%s>", expected, actualDelay);
        }

        return myself;
    }

    public ErrorEntryAssert hasDetailsOfType(Class<? extends ErrorDetail> type) {
        isNotNull();
        boolean found = actual.getDetails().stream().anyMatch(type::isInstance);
        if (!found) {
            failWithMessage("Expected details to contain type <%s> but details were: %s",
                    type.getSimpleName(), actual.getDetails());
        }

        return myself;
    }

    public ErrorEntryAssert hasDetailsCount(int expected) {
        isNotNull();
        if (actual.getDetails().size() != expected) {
            failWithMessage("Expected %d details but found %d: %s",
                    expected, actual.getDetails().size(), actual.getDetails());
        }

        return myself;
    }

    /**
     * Проверяет, что публичные поля не содержат sensitive-данных (CWE-209).
     * Сканирует {@code message}, {@code reason} и {@code clientMetadata} по
     * фиксированному набору паттернов.
     */
    public ErrorEntryAssert containsNoSensitiveData() {
        isNotNull();
        checkFieldForSensitiveData("message", actual.getMessage());
        checkFieldForSensitiveData("reason", actual.getReason());

        actual.getDetails().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .forEach(info -> {
                    info.getClientMetadata().forEach((key, value) -> {
                        checkFieldForSensitiveData("clientMetadata[" + key + "]", value);
                    });
                });

        return myself;
    }

    /**
     * Навигация к первому {@link ErrorInfoDetail} для проверки dual-metadata.
     * Возвращает {@link DualMetadataAssert} с back-reference для {@code backToEntry()}.
     */
    public DualMetadataAssert extractingFirstErrorInfo() {
        isNotNull();
        ErrorInfoDetail info = actual.getDetails().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .findFirst()
                .orElse(null);
        if (info == null) {
            failWithMessage("Expected at least one ErrorInfoDetail in details, but none found. Details: %s",
                    actual.getDetails());
        }

        return new DualMetadataAssert(info, this);
    }

    public ErrorEntryAssert hasNoCause() {
        isNotNull();
        boolean hasCauseDetail = actual.getDetails().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .anyMatch(info -> info.getDiagnosticMetadata().containsKey("rootCauseClass"));
        if (hasCauseDetail) {
            failWithMessage("Expected no root cause info, but diagnosticMetadata contains 'rootCauseClass'");
        }

        return myself;
    }

    public ErrorEntryAssert hasCauseOfType(Class<?> causeType) {
        isNotNull();
        String expectedClass = causeType.getName();
        boolean found = actual.getDetails().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .anyMatch(info -> expectedClass.equals(info.getDiagnosticMetadata().get("rootCauseClass")));
        if (!found) {
            failWithMessage("Expected root cause of type <%s> in diagnosticMetadata, but not found", expectedClass);
        }

        return myself;
    }

    private void checkFieldForSensitiveData(String fieldName, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }

        for (Pattern pattern : SENSITIVE_PATTERNS) {
            if (pattern.matcher(value).find()) {
                failWithMessage("Field '%s' contains sensitive data matching pattern <%s>. Value: <%s>",
                        fieldName, pattern.pattern(), value);
            }
        }
    }

}
