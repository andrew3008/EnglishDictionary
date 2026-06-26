package space.br1440.platform.errorhandling.webflux.testing.support;

import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.UnknownExceptionHandler;
import space.br1440.platform.errorhandling.model.ErrorEntry;

public class TestUnknownExceptionHandler implements UnknownExceptionHandler {

    @Override
    @NonNull
    public @NotNull ErrorEntry handle(@NonNull Exception exception, @NonNull ErrorHandlingContext context) {
        return ErrorEntry.builder()
                .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .errorCode("UNKNOWN_INTERNAL_ERROR")
                .message("Unknown error")
                .build();
    }
}
