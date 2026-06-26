package space.br1440.platform.errorhandling.testing.assertions;

import org.assertj.core.api.Assertions;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

public class ErrorHandlingAssertions extends Assertions {

    public static ErrorEntryAssert assertThat(ErrorEntry actual) {
        return new ErrorEntryAssert(actual);
    }

    public static RootCauseResolutionAssert assertThat(RootCauseResolution actual) {
        return new RootCauseResolutionAssert(actual);
    }

    public static DualMetadataAssert assertThat(ErrorInfoDetail actual) {
        return new DualMetadataAssert(actual);
    }

}
