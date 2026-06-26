package space.br1440.platform.errorhandling.testing.assertions;

import org.assertj.core.api.AbstractAssert;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.util.Objects;

public class RootCauseResolutionAssert extends AbstractAssert<RootCauseResolutionAssert, RootCauseResolution> {

    public RootCauseResolutionAssert(RootCauseResolution actual) {
        super(actual, RootCauseResolutionAssert.class);
    }

    public static RootCauseResolutionAssert assertThat(RootCauseResolution actual) {
        return new RootCauseResolutionAssert(actual);
    }

    public RootCauseResolutionAssert hasHttpStatus(HttpStatus expected) {
        isNotNull();
        if (!Objects.equals(actual.httpStatus(), expected)) {
            failWithMessage("Expected httpStatus <%s> but was <%s>", expected, actual.httpStatus());
        }

        return myself;
    }

    public RootCauseResolutionAssert hasMessage(String expected) {
        isNotNull();
        if (!Objects.equals(actual.message(), expected)) {
            failWithMessage("Expected message <%s> but was <%s>", expected, actual.message());
        }

        return myself;
    }

    public RootCauseResolutionAssert hasMessageContaining(String substring) {
        isNotNull();
        if (actual.message() == null || !actual.message().contains(substring)) {
            failWithMessage("Expected message containing <%s> but was <%s>", substring, actual.message());
        }

        return myself;
    }

    public RootCauseResolutionAssert hasDetailsCount(int expected) {
        isNotNull();
        if (actual.details().size() != expected) {
            failWithMessage("Expected %d details but found %d", expected, actual.details().size());
        }

        return myself;
    }

    public RootCauseResolutionAssert containsRetryInfo() {
        isNotNull();
        boolean found = actual.details().stream()
                .anyMatch(RetryInfoDetail.class::isInstance);
        if (!found) {
            failWithMessage("Expected details to contain RetryInfoDetail but none found");
        }

        return myself;
    }

    public RootCauseResolutionAssert hasRetryability(Retryability expected) {
        isNotNull();
        if (!Objects.equals(actual.retryability(), expected)) {
            failWithMessage("Expected retryability <%s> but was <%s>", expected, actual.retryability());
        }

        return myself;
    }

}
