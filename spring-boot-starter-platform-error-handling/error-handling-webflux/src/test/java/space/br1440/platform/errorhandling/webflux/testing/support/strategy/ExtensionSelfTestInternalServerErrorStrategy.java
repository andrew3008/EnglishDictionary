package space.br1440.platform.errorhandling.webflux.testing.support.strategy;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.model.ErrorEntry;

public final class ExtensionSelfTestInternalServerErrorStrategy implements TopExceptionHandlingStrategy {

    @Override
    public boolean supports(@NonNull Exception exception) {
        return true;
    }

    @Override
    @NonNull
    public ErrorEntry handle(@NonNull Exception exception, @NonNull ErrorHandlingContext ctx) {
        return ErrorEntry.builder()
                .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .errorCode("EXTENSION_TEST_ISE")
                .message("extension test")
                .build();
    }

    @Override
    @NonNull
    public LogPolicy logPolicy(@NonNull Exception exception, @NonNull ErrorEntry entry) {
        return LogPolicy.ERROR;
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
